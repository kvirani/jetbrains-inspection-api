# Rider Inspection API

A JetBrains Rider plugin that exposes ReSharper/C++ code inspection results over HTTP,
designed for AI assistants and automated tooling working with C#, C++, and Unreal Engine projects.

## The problem

Rider's built-in inspections (powered by the ReSharper backend) are fast and comprehensive,
but there's no API to access them programmatically. AI coding assistants can't see the red
squiggles. This plugin bridges that gap with a single HTTP endpoint.

## How it works

The plugin provides a `/api/inspection/analyze` endpoint that accepts one or more file paths
and returns the inspection results. It uses two strategies depending on file state:

**Files open in the editor** are analyzed instantly by reading the MarkupModel — the same
highlight data that Rider renders in the gutter. No re-analysis needed; the daemon has
already done the work.

**Files not open in the editor** are analyzed via `jb inspectcode`, the ReSharper CLI tool.
Multiple closed files in a single request are batched into one CLI invocation using
semicolon-separated `--include` patterns, so the expensive solution model load happens once
regardless of file count.

### Performance optimizations

The CLI path has three layers of optimization:

- **`--caches-home`** — points to a persistent directory (`~/.jb-inspectcode-cache`) so the
  solution model is cached between invocations. Cold cache is slow; warm cache is much faster.
- **`--no-swea`** — disables Solution-Wide Error Analysis, the most expensive inspection pass.
  SWEA is enabled by default in the CLI (but off in the IDE). Unnecessary for per-file analysis.
- **Local result cache** — if the same file is requested again and hasn't been modified
  (checked via `lastModified` + `fileSize`), the previous result is returned instantly with
  no process spawn.

## Quick start

### 1. Prerequisites

- **Rider** (2024.1+)
- **JetBrains ReSharper CLI tools**: `dotnet tool install -g JetBrains.ReSharper.GlobalTools`
- A `.sln` file in or near the project root

### 2. Install the plugin

Download a `.zip` from Releases, then in Rider:
`Settings` > `Plugins` > `gear icon` > `Install Plugin from Disk...`

Or build from source:
```bash
git clone <repo-url>
cd rider-inspection-api
./gradlew buildPlugin
# Plugin zip in build/distributions/
```

### 3. Configure Rider's built-in server

`Settings` > `Tools` > `Web Browsers and Preview` > **Built-in Server**:
- Set a port (e.g. `63341`)
- Check "Can accept external connections"
- Check "Allow unsigned requests"

### 4. Use the endpoint

```
GET http://localhost:63341/api/inspection/analyze?file=<path>&file=<path>
```

## API reference

### `GET /api/inspection/analyze`

Analyze one or more files for inspection problems.

**Parameters:**

| Parameter    | Default | Description |
|---|---|---|
| `file`       | *(required, repeatable)* | File path to analyze. Relative paths resolve from the project root. Can be repeated for multiple files. |
| `format`     | `md`    | Response format: `md` (markdown, default) or `json` |
| `severity`   | `all`   | Filter: `error`, `warning`, `weak_warning`, `info`, or `all` |
| `project`    | *(auto)* | Project name, if multiple projects are open |
| `timeout_ms` | `30000` | Max time to wait for analysis (1000–120000) |
| `limit`      | `200`   | Max problems returned (JSON format only) |
| `offset`     | `0`     | Pagination offset (JSON format only) |

**Markdown response** (default):
```
GET /api/inspection/analyze?file=Source/Game/Player.cpp&file=Source/Game/Enemy.cpp
```
```markdown
## Source/Game/Player.cpp
- warning: Unreachable code detected (line 42)
- info: Redundant cast (line 88)

## Source/Game/Enemy.cpp
no problems found
```

**JSON response**:
```
GET /api/inspection/analyze?file=Source/Game/Player.cpp&format=json
```
```json
{
  "status": "results_available",
  "project": "MyGame",
  "total_problems": 2,
  "problems": [
    {
      "description": "Unreachable code detected",
      "file": "C:/Projects/MyGame/Source/Game/Player.cpp",
      "line": 42,
      "column": 5,
      "severity": "warning",
      "category": "C++",
      "inspectionType": "CppUnreachableCode",
      "source": "inspectcode_cli"
    }
  ]
}
```

**Error cases:**
- No `file` params: `400` with error message
- No files exist on disk: `400` with `"None of the specified files exist on disk"`
- Invalid file in a mixed request: that file gets `"not_found"` status, valid files are still analyzed

## Limitations

### CLI overhead for closed files

The `jb inspectcode` CLI is fundamentally slower than the in-IDE experience. Even with
caching and `--no-swea`, the first invocation for a cold solution is slow because the CLI
is a standalone process that must bootstrap a .NET runtime, load all ReSharper plugins,
and build the solution model from scratch.

Inside Rider, none of this happens — the solution model is built once at startup and kept
in memory. When you right-click a file and "Inspect Code", the ReSharper backend runs
against the warm model. There is no public API to invoke this directly.

### `--include` is a report filter, not a scope filter

The `--include` flag on `jb inspectcode` filters the *output*, not the *analysis*. The CLI
still walks the entire solution to build the semantic model, then discards results for files
you didn't ask about. This means analyzing one file costs roughly the same as analyzing
the whole project on the first run. The `--caches-home` flag mitigates this on subsequent runs.

### Future: direct ReSharper backend integration

Rider communicates with the ReSharper backend over the **rd protocol**, which is
[open source](https://github.com/JetBrains/rd). The protocol defines typed models and
calls between Rider's IntelliJ-based frontend and the ReSharper analysis engine.

In theory, we could find and invoke the same protocol endpoint that Rider uses for
"Inspect Code" from the context menu — triggering ReSharper analysis on arbitrary files
without opening them, without the CLI, and at the same speed as the IDE. This would
eliminate the CLI path entirely.

This is a non-trivial reverse engineering effort (the protocol models are spread across
JetBrains' open-source repos and the ReSharper SDK), but it's the path to true
IDE-speed analysis for closed files.

## License

MIT License: see [LICENSE](LICENSE) file for details.
