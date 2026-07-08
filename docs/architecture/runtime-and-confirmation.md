# Runtime and Confirmation Architecture

## RuntimeManager

`RuntimeManager` is the new deep module for runtime readiness and execution entry points.

### Responsibilities
- detect Python runtime status
- detect Node.js runtime status
- expose Shizuku authorization state
- provide a normalized shell execution entry point

### Why it exists
Before this refactor, runtime concerns were spread across:
- `SetupScreen`
- `PythonRunner`
- script tools
- terminal helpers

That duplication made the UI know too much about process execution and device layout. `RuntimeManager` gives one interface to query runtime truth.

### Current scope
This pass centralizes detection and shell execution entry points. It does **not** yet rewrite every Python/Node tool to depend solely on `RuntimeManager`.

---

## ToolConfirmationManager

`ToolConfirmationManager` is the real user-confirmation seam for sensitive tool calls.

### Responsibilities
- create pending confirmation requests
- suspend tool execution until user decision
- expose pending request state to Compose UI
- resolve allow / deny decisions

### Flow
1. `AgentLoop` reaches a sensitive tool.
2. `ChatViewModel.onConfirmToolCall()` delegates to `ToolConfirmationManager.awaitDecision()`.
3. A `ConfirmationRequest` is published via `StateFlow`.
4. `ChatScreen` renders a confirmation dialog.
5. User taps allow or deny.
6. Deferred result resumes execution.

### Why this matters
Previously, the codebase had a fake seam:
- tools marked `sensitive = true`
- but `onConfirmToolCall()` always returned `true`

Now the interface matches reality.

---

## Risk model

Tool risk levels are interpreted as:

- `SAFE`: read-only or harmless
- `CAUTION`: may reveal sensitive data or create moderate side effects
- `DANGER`: write / patch / sign / install / repack level operations

This model is intentionally conservative for APK reversing workflows.

---

## Next expansion points

### Runtime
- unify Node execution routing behind RuntimeManager
- move more script tools to runtime request objects instead of ad-hoc shell/process logic
- surface richer runtime diagnostics to Settings UI

### Confirmation
- support "allow once" / "allow this tool for current session"
- show target path extraction for write/install/sign tools
- write confirmation audit trail into project logs
