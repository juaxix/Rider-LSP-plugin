#!/usr/bin/env python3
"""
CLI tool to query the Rider LSP Server plugin.
Used by Claude Code to access Rider's C++ intellisense.

Usage:
    python rider-lsp-query.py symbol <query>          # Search symbols
    python rider-lsp-query.py definition <file> <line> <col>  # Go to definition
    python rider-lsp-query.py hover <file> <line> <col>       # Get hover info
    python rider-lsp-query.py references <file> <line> <col>  # Find references
    python rider-lsp-query.py diagnostics <file>              # Get diagnostics
    python rider-lsp-query.py status                          # Check if server is up
"""

import json
import socket
import sys
import os
import time

HOST = "127.0.0.1"
PORT = 9999
TIMEOUT = 90

_req_id = 0
_sock = None


def _make_msg(method, params, req_id=None):
    obj = {"jsonrpc": "2.0", "method": method, "params": params}
    if req_id is not None:
        obj["id"] = req_id
    body = json.dumps(obj)
    header = f"Content-Length: {len(body)}\r\n\r\n"
    return (header + body).encode("utf-8")


def _read_msg(sock, timeout=TIMEOUT):
    sock.settimeout(timeout)
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(1)
        if not chunk:
            raise ConnectionError("Connection closed")
        data += chunk
    header_part, rest = data.split(b"\r\n\r\n", 1)
    content_length = None
    for line in header_part.decode("utf-8").split("\r\n"):
        if line.lower().startswith("content-length:"):
            content_length = int(line.split(":")[1].strip())
            break
    if content_length is None:
        raise ValueError("No Content-Length header")
    body = rest
    while len(body) < content_length:
        chunk = sock.recv(content_length - len(body))
        if not chunk:
            raise ConnectionError("Connection closed")
        body += chunk
    return json.loads(body.decode("utf-8"))


def _next_id():
    global _req_id
    _req_id += 1
    return _req_id


def _connect():
    global _sock
    if _sock is not None:
        return _sock
    _sock = socket.create_connection((HOST, PORT), timeout=5)
    # Initialize
    rid = _next_id()
    _sock.sendall(_make_msg("initialize", {
        "processId": None,
        "clientInfo": {"name": "rider-lsp-query", "version": "1.0"},
        "capabilities": {},
        "rootUri": None,
    }, rid))
    # Read until we get the initialize response
    while True:
        msg = _read_msg(_sock)
        if msg.get("id") == rid:
            break
    _sock.sendall(_make_msg("initialized", {}))
    return _sock


def _request(method, params, timeout=TIMEOUT):
    sock = _connect()
    rid = _next_id()
    sock.sendall(_make_msg(method, params, rid))
    start = time.time()
    while time.time() - start < timeout:
        msg = _read_msg(sock, timeout)
        if msg.get("id") == rid:
            if "error" in msg:
                print(f"Error: {msg['error'].get('message', 'unknown')}", file=sys.stderr)
                return None
            return msg.get("result")
    raise TimeoutError(f"No response for {method} within {timeout}s")


def _file_uri(path):
    path = os.path.abspath(path).replace("\\", "/")
    if not path.startswith("/"):
        path = "/" + path
    return f"file://{path}"


def cmd_status():
    try:
        sock = socket.create_connection((HOST, PORT), timeout=3)
        sock.close()
        print("Rider LSP server is running on 127.0.0.1:9999")
        return 0
    except (ConnectionRefusedError, OSError):
        print("Rider LSP server is NOT running")
        return 1


def cmd_symbol(query):
    result = _request("workspace/symbol", {"query": query})
    if not result:
        print("No symbols found.")
        return
    for sym in result:
        name = sym.get("name", "?")
        kind = sym.get("kind", 0)
        loc = sym.get("location", {})
        uri = loc.get("uri", "")
        line = loc.get("range", {}).get("start", {}).get("line", 0)
        # Convert URI to local path
        path = uri.replace("file:///", "").replace("file://", "")
        print(f"{path}:{line + 1}: {name} (kind={kind})")


def cmd_definition(filepath, line, col):
    uri = _file_uri(filepath)
    result = _request("textDocument/definition", {
        "textDocument": {"uri": uri},
        "position": {"line": int(line) - 1, "character": int(col) - 1},
    })
    if not result:
        print("No definition found.")
        return
    for loc in result:
        path = loc.get("uri", "").replace("file:///", "").replace("file://", "")
        rng = loc.get("range", {}).get("start", {})
        print(f"{path}:{rng.get('line', 0) + 1}:{rng.get('character', 0) + 1}")


def cmd_hover(filepath, line, col):
    uri = _file_uri(filepath)
    result = _request("textDocument/hover", {
        "textDocument": {"uri": uri},
        "position": {"line": int(line) - 1, "character": int(col) - 1},
    })
    if not result:
        print("No hover info.")
        return
    contents = result.get("contents", {})
    if isinstance(contents, dict):
        print(contents.get("value", ""))
    else:
        print(str(contents))


def cmd_references(filepath, line, col):
    uri = _file_uri(filepath)
    result = _request("textDocument/references", {
        "textDocument": {"uri": uri},
        "position": {"line": int(line) - 1, "character": int(col) - 1},
        "context": {"includeDeclaration": True},
    })
    if not result:
        print("No references found.")
        return
    for loc in result:
        path = loc.get("uri", "").replace("file:///", "").replace("file://", "")
        rng = loc.get("range", {}).get("start", {})
        print(f"{path}:{rng.get('line', 0) + 1}:{rng.get('character', 0) + 1}")


def cmd_diagnostics(filepath):
    # Open the file to trigger diagnostics, then wait for them
    uri = _file_uri(filepath)
    sock = _connect()
    try:
        content = open(filepath, "r", errors="replace").read()
    except FileNotFoundError:
        print(f"File not found: {filepath}", file=sys.stderr)
        return
    sock.sendall(_make_msg("textDocument/didOpen", {
        "textDocument": {
            "uri": uri,
            "languageId": "cpp",
            "version": 1,
            "text": content,
        }
    }))
    # Wait for publishDiagnostics notification for this file
    start = time.time()
    while time.time() - start < 30:
        msg = _read_msg(sock, 30)
        if msg.get("method") == "textDocument/publishDiagnostics":
            params = msg.get("params", {})
            if params.get("uri") == uri:
                diags = params.get("diagnostics", [])
                if not diags:
                    print("No diagnostics.")
                for d in diags:
                    sev = {1: "error", 2: "warning", 3: "info", 4: "hint"}.get(d.get("severity", 4), "?")
                    rng = d.get("range", {}).get("start", {})
                    line = rng.get("line", 0) + 1
                    col = rng.get("character", 0) + 1
                    print(f"{line}:{col} [{sev}] {d.get('message', '')}")
                return
    print("Timed out waiting for diagnostics.")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    cmd = sys.argv[1]

    try:
        if cmd == "status":
            sys.exit(cmd_status())
        elif cmd == "symbol" and len(sys.argv) >= 3:
            cmd_symbol(" ".join(sys.argv[2:]))
        elif cmd == "definition" and len(sys.argv) >= 5:
            cmd_definition(sys.argv[2], sys.argv[3], sys.argv[4])
        elif cmd == "hover" and len(sys.argv) >= 5:
            cmd_hover(sys.argv[2], sys.argv[3], sys.argv[4])
        elif cmd == "references" and len(sys.argv) >= 5:
            cmd_references(sys.argv[2], sys.argv[3], sys.argv[4])
        elif cmd == "diagnostics" and len(sys.argv) >= 3:
            cmd_diagnostics(sys.argv[2])
        else:
            print(__doc__)
            sys.exit(1)
    except ConnectionRefusedError:
        print("Error: Rider LSP server is not running. Start Rider with a project open.", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
