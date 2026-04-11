Exposes Rider's C++ intellisense as a TCP-based LSP server.
Allows external LSP clients to query navigation, completion, hover, diagnostics, and symbol search.
Includes a python script to use with Claude Code or any other LLM console commandline.

Build plugin with `gradlew.bat buildPlugin`, install it in Rider 2026.1+ , open the project wait for the cache to finish and be ready. Place the python script in your .claude and add instructions in your CLAUDE.MD (or turn it to a SKILL). 

**Results**:
![Diff](https://raw.githubusercontent.com/juaxix/Rider-LSP-plugin/refs/heads/main/Diff.jpg)

Instructions:
---
## Rider LSP Intellisense

A Rider plugin exposes C++ intellisense via LSP on `localhost:9999`. The CLI tool is at `.claude/RiderListenerPlugin/rider-lsp-query.py` (relative to this repo root). Alias for examples below:

```
LSP="python .claude/RiderListenerPlugin/rider-lsp-query.py"
```

### When to use the LSP (prefer over grep/glob for C++)

**Always try the LSP first** when working with C++ code. It resolves macros, generated code, and template instantiations that text search cannot. Use it to:

- **Find where a class/struct/function is defined:** `$LSP symbol APlayerController` - returns class hierarchy, constructors, related functions with exact file:line
- **Understand class relationships and inheritance:** `$LSP symbol APawn` - shows subclasses, overrides, and related types
- **Find function signatures before calling/modifying:** `$LSP hover <file> <line> <col>` - returns full signature and documentation
- **Navigate to definition:** `$LSP definition <file> <line> <col>` - resolves through macros and generated code
- **Find all references:** `$LSP references <file> <line> <col>` - semantic references, not text matches

### Commands

```bash
# Check if Rider LSP is available (do this first)
$LSP status

# Search symbols by name (classes, structs, functions, enums)
$LSP symbol <query>

# Go to definition (line and column are 1-based)
$LSP definition <file> <line> <col>

# Get hover info (signature, docs)
$LSP hover <file> <line> <col>

# Find all references to symbol at position
$LSP references <file> <line> <col>
```

### Workflow

1. Before modifying C++ code, run `$LSP status`. If the server is up, use LSP for all code navigation.
2. To understand a class: `$LSP symbol ClassName` - gives you definitions, constructors, subclasses, and related functions in one query.
3. To understand a function before changing it: `$LSP hover <file> <line> <col>` for the signature, then `$LSP references <file> <line> <col>` to see callers.
4. If the server is not running (Rider not open), fall back to grep/glob - but note that grep returns raw text matches (14k+ for common types) vs LSP's structured semantic results (10-20 relevant symbols).
once Rider started just wait for the cache&indexing to finish
you may need to restart the claude session 
--- 
