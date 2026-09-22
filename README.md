# Rider LSP Server

Exposes Rider's C++ intellisense as a TCP-based LSP server so external clients (Claude Code, any LLM
console, or a regular LSP client) can query navigation, completion, hover, diagnostics, and symbol
search. Includes `rider-lsp-query.py`, a one-shot command line client.

**Results**:
![Diff](https://raw.githubusercontent.com/juaxix/Rider-LSP-plugin/refs/heads/main/Diff.jpg)

## Requirements

- Rider 2026.2 or newer (build 262+). There is no upper version bound; the plugin uses stable
  platform APIs directly and reaches Rider internals only through guarded reflection, so symbol search
  degrades gracefully instead of breaking if those internals move.
- JDK 21 to build.

## Build

```bash
# Compile against an installed Rider (fast, offline, matches exactly what you run):
./gradlew buildPlugin -PriderLocalPath="C:/Program Files/JetBrains/JetBrains Rider 2026.2"

# Or let Gradle download the Rider SDK (default version is set in build.gradle.kts; override with -PriderVersion=2026.3):
./gradlew buildPlugin
```

The archive ends up in `build/distributions/RiderOpenListener-<version>.zip`.

## Install

Either **Settings > Plugins > (gear) > Install Plugin from Disk...** and pick the zip, or run:

```bash
bash install.sh                      # newest zip in build/distributions
bash install.sh path/to/plugin.zip
```

`install.sh` copies the plugin straight into the Rider plugins directory when Rider is closed. When
Rider is running its jars are locked, so the script queues the install through the IDE's own startup
action script (the same mechanism the Plugins UI uses) and it is applied the next time Rider starts.

After installing, open your project and wait for indexing to finish. The server starts automatically
for every open project. Configure port, bind address, or disable it in
**Settings > Tools > Rider LSP Server**; changes apply immediately, no restart needed.

## Command line client

Copy `rider-lsp-query.py` next to your project (for example `.claude/RiderListenerPlugin/`). The port
defaults to 9999; pass `--port N` or set `RIDER_LSP_PORT` to match the plugin settings.

```bash
LSP="python .claude/RiderListenerPlugin/rider-lsp-query.py --port 9999"

$LSP status                                # is the server up?
$LSP symbol APlayerController              # search symbols by name
$LSP definition <file> <line> <col>        # go to definition (1-based line/col)
$LSP hover <file> <line> <col>             # signature and docs
$LSP references <file> <line> <col>        # semantic references
$LSP diagnostics <file>                    # highlighting for a file open in a Rider editor
```

## Claude Code instructions

Add something like this to your `CLAUDE.md` (or turn it into a skill):

---
### Rider LSP Intellisense

A Rider plugin exposes C++ intellisense via LSP on `localhost:9999`. The CLI tool is at
`.claude/RiderListenerPlugin/rider-lsp-query.py` (relative to this repo root):

```
LSP="python .claude/RiderListenerPlugin/rider-lsp-query.py"
```

**Always try the LSP first** when working with C++ code. It resolves macros, generated code, and
template instantiations that text search cannot:

- **Find where a class/struct/function is defined:** `$LSP symbol APlayerController`
- **Understand class relationships:** `$LSP symbol APawn` shows subclasses, overrides, related types
- **Function signatures before calling/modifying:** `$LSP hover <file> <line> <col>`
- **Navigate to definition:** `$LSP definition <file> <line> <col>`
- **Find all references:** `$LSP references <file> <line> <col>`

Workflow: run `$LSP status` first. If the server is up, use LSP for all code navigation; if it is
not (Rider closed or still indexing), fall back to grep/glob.

---

## Changelog

### 1.1.0

- **Memory leak fixed.** Every client connection used to register a project-lifetime diagnostics
  listener and bind Rider "goto" sessions to the project. Neither was released unless the client sent
  an LSP `shutdown`, which one-shot CLI clients never do, so each query left listeners, result caches
  and socket proxies behind for the life of the project. Sessions are now owned by a per-connection
  `Disposable` and freed as soon as the socket closes.
- Symbol search and location lookups no longer create editor `Document`s for engine headers (the IDE
  keeps those cached for a very long time); they read file text directly.
- Multiple clients can connect at once (parallel queries work) and the old 30-second connection cap is gone.
- Completion no longer runs the EDT call from inside a read action (deadlock risk) and picks the right
  `CompletionParameters` constructor.
- Settings changes restart the server in open projects immediately.
- Plugin no longer bundles its own Kotlin runtime; `until-build` removed.
- `rider-lsp-query.py`: `--port/--host` flags and `RIDER_LSP_PORT/HOST` env vars, sends `shutdown/exit`,
  decodes percent-encoded paths.

## Notes on Rider memory

The JVM grows the heap up to whatever `-Xmx` allows in `rider64.exe.vmoptions` before it collects
soft-referenced caches aggressively. With a very large `-Xmx` Rider can legitimately sit at many GB
on an Unreal Engine solution even without leaks; if that is undesirable, lower `-Xmx` in
**Help > Change Memory Settings**.
