# APKAgent Refactor Overview

## Why this refactor exists

APKAgent has outgrown its original shape. It is no longer just an AI chat shell over APK tools; it is now a projectized reverse-engineering workbench with:

- project import and per-project workspaces
- pre-analysis summaries
- AI agent orchestration
- Python / Node / shell runtime integration
- Shizuku-based privileged execution
- export / patch / sign flows

That capability growth created architectural drift:

1. **Version drift** — build version and runtime-reported version diverged.
2. **Confirmation drift** — sensitive tools are marked as confirm-required, but the ViewModel auto-approves them.
3. **Sandbox drift** — `Sandbox` is no longer a sandbox; it is effectively a path resolver with permissive access.
4. **Runtime drift** — Python / Node / shell / Shizuku detection and execution are spread across multiple modules.
5. **ViewModel drift** — `ChatViewModel` orchestrates too many unrelated concerns.
6. **Documentation drift** — README still describes an earlier product shape.

## Refactor goals

### 1. Restore truthfulness at seams

Interfaces should match behavior. If a module is called a sandbox, it should enforce policy; if it does not, its interface and docs must say so. If a tool is marked sensitive, the user must really be asked.

### 2. Deepen the runtime module

The runtime stack should be a deep module:

- one interface for detection
- one interface for execution
- one place for Shizuku fallback rules
- one place for Python / Node / shell status

This gives leverage to Setup UI, tools, terminal features, and future automation.

### 3. Shrink orchestration surfaces

`ChatViewModel` should orchestrate conversation state, not own export logic, persistence internals, confirmation queues, and runtime concerns directly.

### 4. Align docs with the real product

The README should describe the current product truthfully:

- projectized workbench
- runtime setup requirements
- permission model
- confirmation model
- export / sign workflow

## Target module boundaries

### RuntimeManager

Responsible for:
- detecting Python / Node / shell readiness
- choosing execution route
- exposing structured status to UI
- standardizing execution results

### ToolConfirmationManager

Responsible for:
- receiving pending confirmation requests
- exposing them as observable state
- suspending tool execution until decision
- recording approve / deny decisions

### ChatHistoryManager

Responsible for:
- listing history
- loading history
- restoring last conversation
- writing history metadata and content

### ApkExportManager

Responsible for:
- discovering patched artifacts
- unpack / replace / repack / sign flow
- returning a structured export result

### ProjectAnalyzer

Responsible for:
- fast import-time analysis
- summary generation
- explicit extension seam for later deep intelligence indexing

## Implementation order

1. Architecture + plan docs
2. Version unification
3. Real tool confirmation seam
4. RuntimeManager extraction
5. ChatViewModel slimming
6. README rewrite
7. Tests and verification

## Non-goals for this pass

- rewriting all tools
- changing the full APK patching strategy
- removing Shizuku
- replacing Compose navigation
- attempting Play Store compliance

## Success criteria

- no fake confirmation path remains
- no stale version strings remain
- setup/runtime logic is centralized
- ChatViewModel is materially smaller in responsibility
- docs match reality
- tests protect the new seams
