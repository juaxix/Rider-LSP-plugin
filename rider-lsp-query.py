#!/usr/bin/env python3
"""
CLI tool to query the Rider LSP Server plugin.
Used by Claude Code to access Rider's C++ intellisense.

Usage:
    python rider-lsp-query.py [--port N] symbol <query>                 # Search symbols
    python rider-lsp-query.py [--port N] definition <file> <line> <col> # Go to definition
    python rider-lsp-query.py [--port N] hover <file> <line> <col>      # Get hover info
    python rider-lsp-query.py [--port N] references <file> <line> <col> # Find references
    python rider-lsp-query.py [--port N] diagnostics <file>             # Get diagnostics
    python rider-lsp-query.py [--port N] status                         # Check if server is up

The port defaults to 9999 and can be changed with --port or the RIDER_LSP_PORT
environment variable (RIDER_LSP_HOST for the host). It must match
Settings > Tools > Rider LSP Server in Rider.
"""

import json
import os
import socket
import sys
import time
from urllib.parse import unquote

HOST = os.environ.get("RIDER_LSP_HOST", "127.0.0.1")
PORT = int(os.environ.get("RIDER_LSP_PORT", "9999"))
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
    rid = _next_id()
    _sock.sendall(_make_msg("initialize", {
        "processId": os.getpid(),
        "clientInfo": {"name": "rider-lsp-query", "version": "1.1"},
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


def _disconnect():
    """Tell the server we are done so it can release its resources right away."""
    global _sock
    if _sock is None:
        return
    try:
        rid = _next_id()
        _sock.sendall(_make_msg("shutdown", None, rid))
        deadline = time.time() + 2
        while time.time() < deadline:
            msg = _read_msg(_sock, 2)
            if msg.get("id") == rid:
                break
        _sock.sendall(_make_msg("exit", None))
    except Exception:
        pass
    try:
        _sock.close()
    except Exception:
        pass
    _sock = None


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


def _uri_to_path(uri):
    path = uri.replace("file:///", "").replace("file://", "")
    return unquote(path)


def cmd_status():
    try:
        sock = socket.create_connection((HOST, PORT), timeout=3)
        sock.close()
        print(f"Rider LSP server is running on {HOST}:{PORT}")
        return 0
    except (ConnectionRefusedError, OSError):
        print(f"Rider LSP server is NOT running on {HOST}:{PORT}")
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
        path = _uri_to_path(loc.get("uri", ""))
        line = loc.get("range", {}).get("start", {}).get("line", 0)
        print(f"{path}:{line + 1}: {name} (kind={kind})")


def _print_locations(result, empty_message):
    if not result:
        print(empty_message)
        return
    for loc in result:
        path = _uri_to_path(loc.get("uri", ""))
        rng = loc.get("range", {}).get("start", {})
        print(f"{path}:{rng.get('line', 0) + 1}:{rng.get('character', 0) + 1}")


def cmd_definition(filepath, line, col):
    result = _request("textDocument/definition", {
        "textDocument": {"uri": _file_uri(filepath)},
        "position": {"line": int(line) - 1, "character": int(col) - 1},
    })
    _print_locations(result, "No definition found.")


def cmd_hover(filepath, line, col):
    result = _request("textDocument/hover", {
        "textDocument": {"uri": _file_uri(filepath)},
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
    result = _request("textDocument/references", {
        "textDocument": {"uri": _file_uri(filepath)},
        "position": {"line": int(line) - 1, "character": int(col) - 1},
        "context": {"includeDeclaration": True},
    })
    _print_locations(result, "No references found.")


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
            if _uri_to_path(params.get("uri", "")).lower() == _uri_to_path(uri).lower():
                diags = params.get("diagnostics", [])
                if not diags:
                    print("No diagnostics (file must be open in a Rider editor to be analyzed).")
                for d in diags:
                    sev = {1: "error", 2: "warning", 3: "info", 4: "hint"}.get(d.get("severity", 4), "?")
                    rng = d.get("range", {}).get("start", {})
                    line = rng.get("line", 0) + 1
                    col = rng.get("character", 0) + 1
                    print(f"{line}:{col} [{sev}] {d.get('message', '')}")
                return
    print("Timed out waiting for diagnostics.")


def main():
    global HOST, PORT
    argv = sys.argv[1:]

    # Optional connection flags before the command
    while argv and argv[0] in ("--port", "--host"):
        if len(argv) < 2:
            print(__doc__)
            sys.exit(1)
        if argv[0] == "--port":
            PORT = int(argv[1])
        else:
            HOST = argv[1]
        argv = argv[2:]

    if not argv:
        print(__doc__)
        sys.exit(1)

    cmd = argv[0]
    args = argv[1:]

    try:
        if cmd == "status":
            sys.exit(cmd_status())
        elif cmd == "symbol" and len(args) >= 1:
            cmd_symbol(" ".join(args))
        elif cmd == "definition" and len(args) >= 3:
            cmd_definition(args[0], args[1], args[2])
        elif cmd == "hover" and len(args) >= 3:
            cmd_hover(args[0], args[1], args[2])
        elif cmd == "references" and len(args) >= 3:
            cmd_references(args[0], args[1], args[2])
        elif cmd == "diagnostics" and len(args) >= 1:
            cmd_diagnostics(args[0])
        else:
            print(__doc__)
            sys.exit(1)
    except ConnectionRefusedError:
        print(f"Error: Rider LSP server is not running on {HOST}:{PORT}. "
              "Start Rider with a project open (and check the port in Settings > Tools > Rider LSP Server).",
              file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
    finally:
        _disconnect()


if __name__ == "__main__":
    main()
