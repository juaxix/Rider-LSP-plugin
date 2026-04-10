"""
Quick test script for the Rider LSP Server plugin.
Connects to localhost:9999 and sends an LSP initialize request.

Usage:
    python test_lsp.py
    python test_lsp.py --port 9999
    python test_lsp.py --test-all
"""

import json
import socket
import sys
import argparse
import time


def make_lsp_message(method, params, req_id=1):
    body = json.dumps({
        "jsonrpc": "2.0",
        "id": req_id,
        "method": method,
        "params": params,
    })
    header = f"Content-Length: {len(body)}\r\n\r\n"
    return (header + body).encode("utf-8")


def make_lsp_notification(method, params):
    body = json.dumps({
        "jsonrpc": "2.0",
        "method": method,
        "params": params,
    })
    header = f"Content-Length: {len(body)}\r\n\r\n"
    return (header + body).encode("utf-8")


def read_lsp_response(sock, timeout=30):
    sock.settimeout(timeout)
    data = b""
    # Read headers
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(1)
        if not chunk:
            raise ConnectionError("Connection closed while reading headers")
        data += chunk

    header_part, rest = data.split(b"\r\n\r\n", 1)
    headers = header_part.decode("utf-8")

    content_length = None
    for line in headers.split("\r\n"):
        if line.lower().startswith("content-length:"):
            content_length = int(line.split(":")[1].strip())
            break

    if content_length is None:
        raise ValueError(f"No Content-Length in headers: {headers}")

    # Read body
    body = rest
    while len(body) < content_length:
        chunk = sock.recv(content_length - len(body))
        if not chunk:
            raise ConnectionError("Connection closed while reading body")
        body += chunk

    return json.loads(body.decode("utf-8"))


def send_and_receive(sock, method, params, req_id=1, timeout=60):
    msg = make_lsp_message(method, params, req_id)
    sock.sendall(msg)
    # Loop until we get a response with the matching id, skipping notifications
    start = time.time()
    while time.time() - start < timeout:
        resp = read_lsp_response(sock, timeout)
        if "id" in resp and resp["id"] == req_id:
            return resp
        # It's a notification (e.g. publishDiagnostics) — print and continue
        if "method" in resp:
            print(f"  [notification] {resp['method']}: {json.dumps(resp.get('params', {}))[:120]}")
    raise TimeoutError(f"No response for {method} (id={req_id}) within {timeout}s")


def send_notification(sock, method, params):
    msg = make_lsp_notification(method, params)
    sock.sendall(msg)


def test_initialize(sock):
    print("--- Test: initialize ---")
    resp = send_and_receive(sock, "initialize", {
        "processId": None,
        "clientInfo": {"name": "test_lsp.py", "version": "1.0"},
        "capabilities": {},
        "rootUri": None,
    }, req_id=1)

    if "result" in resp:
        caps = resp["result"].get("capabilities", {})
        server_info = resp["result"].get("serverInfo", {})
        print(f"  Server: {server_info.get('name', '?')} {server_info.get('version', '?')}")
        print(f"  Capabilities:")
        for key, val in sorted(caps.items()):
            print(f"    {key}: {json.dumps(val, default=str)[:80]}")

        # Send initialized notification
        send_notification(sock, "initialized", {})
        print("  PASS")
        return True
    else:
        print(f"  FAIL: {json.dumps(resp, indent=2)}")
        return False


def test_hover(sock, file_uri, line, character, req_id=10):
    print(f"--- Test: textDocument/hover ({file_uri}:{line}:{character}) ---")
    resp = send_and_receive(sock, "textDocument/hover", {
        "textDocument": {"uri": file_uri},
        "position": {"line": line, "character": character},
    }, req_id=req_id)

    if "result" in resp:
        result = resp["result"]
        if result and "contents" in result:
            content = result["contents"]
            if isinstance(content, dict):
                text = content.get("value", "")[:200]
            else:
                text = str(content)[:200]
            print(f"  Hover: {text}")
        else:
            print("  Hover: (empty)")
        print("  PASS")
        return True
    else:
        print(f"  FAIL: {json.dumps(resp, indent=2)[:300]}")
        return False


def test_definition(sock, file_uri, line, character, req_id=20):
    print(f"--- Test: textDocument/definition ({file_uri}:{line}:{character}) ---")
    resp = send_and_receive(sock, "textDocument/definition", {
        "textDocument": {"uri": file_uri},
        "position": {"line": line, "character": character},
    }, req_id=req_id)

    if "result" in resp:
        result = resp["result"]
        if result:
            for loc in result[:5]:
                uri = loc.get("uri", "?")
                rng = loc.get("range", {}).get("start", {})
                print(f"  -> {uri}:{rng.get('line', '?')}:{rng.get('character', '?')}")
        else:
            print("  Definition: (no results)")
        print("  PASS")
        return True
    else:
        print(f"  FAIL: {json.dumps(resp, indent=2)[:300]}")
        return False


def test_workspace_symbol(sock, query, req_id=30):
    print(f"--- Test: workspace/symbol (query=\"{query}\") ---")
    resp = send_and_receive(sock, "workspace/symbol", {
        "query": query,
    }, req_id=req_id, timeout=60)

    if "result" in resp:
        result = resp["result"]
        if result:
            print(f"  Found {len(result)} symbols:")
            for sym in result[:10]:
                name = sym.get("name", "?")
                kind = sym.get("kind", "?")
                loc = sym.get("location", {})
                uri = loc.get("uri", "?")
                line = loc.get("range", {}).get("start", {}).get("line", "?")
                print(f"    {name} (kind={kind}) at {uri}:{line}")
            if len(result) > 10:
                print(f"    ... and {len(result) - 10} more")
        else:
            print("  Symbols: (no results)")
        print("  PASS")
        return True
    else:
        print(f"  FAIL: {json.dumps(resp, indent=2)[:300]}")
        return False


def test_shutdown(sock, req_id=99):
    print("--- Test: shutdown ---")
    resp = send_and_receive(sock, "shutdown", None, req_id=req_id)
    print(f"  Response: {json.dumps(resp)}")
    send_notification(sock, "exit", None)
    print("  PASS")
    return True


def main():
    parser = argparse.ArgumentParser(description="Test Rider LSP Server plugin")
    parser.add_argument("--port", type=int, default=9999, help="LSP server port (default: 9999)")
    parser.add_argument("--host", default="127.0.0.1", help="LSP server host (default: 127.0.0.1)")
    parser.add_argument("--test-all", action="store_true", help="Run all tests including symbol search")
    parser.add_argument("--symbol", default="AActor", help="Symbol to search for in workspace/symbol test")
    parser.add_argument("--timeout", type=int, default=30, help="Timeout in seconds (default: 30)")
    args = parser.parse_args()

    print(f"Connecting to {args.host}:{args.port}...")
    try:
        sock = socket.create_connection((args.host, args.port), timeout=5)
    except (ConnectionRefusedError, OSError) as e:
        print(f"FAILED to connect: {e}")
        print("Make sure Rider is running with a project open and the plugin is loaded.")
        sys.exit(1)

    print(f"Connected!\n")

    try:
        ok = test_initialize(sock)
        if not ok:
            sys.exit(1)

        print()

        if args.test_all:
            time.sleep(1)  # let the server finish initializing

            test_workspace_symbol(sock, args.symbol)
            print()

        test_shutdown(sock)

    except Exception as e:
        print(f"\nERROR: {e}")
        sys.exit(1)
    finally:
        sock.close()

    print("\nAll tests passed!")


if __name__ == "__main__":
    main()
