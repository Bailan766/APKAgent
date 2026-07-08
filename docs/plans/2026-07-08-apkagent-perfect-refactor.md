# APKAgent Perfect Refactor Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 把 APKAgent 从“功能堆叠中的强工具”收敛成“项目化 APK 逆向工作台”，同时修掉当前最危险的安全、版本、运行时与架构问题。

**Architecture:** 以“深模块”重构为主线：把运行时检测/执行统一进 RuntimeManager，把敏感操作确认升级成真实的确认 seam，把 ChatViewModel 中的导出/历史/会话职责拆分成独立模块，并让版本信息与文档统一来源。保留现有功能面，但降低耦合与误导性抽象。

**Tech Stack:** Kotlin, Jetpack Compose, Android SDK 35, OkHttp SSE, Shizuku, JUnit4, kotlinx-coroutines-test.

---

## Desired end state

1. 敏感工具调用不再默认放行，真正进入确认队列。
2. 版本号从 BuildConfig 读取，日志/文档/构建一致。
3. Python/Node/Shell/Shizuku 执行能力由 RuntimeManager 统一调度。
4. ChatViewModel 只保留 UI 编排职责，导出/历史/确认拆出。
5. README 与当前产品形态一致，新增架构文档与重构路线。
6. 为 ProjectStore / ProjectAnalyzer / OpenAIClient / 敏感确认补单测。

---

## Task 1: 记录当前架构与目标文档

**Objective:** 把当前问题与目标架构写入仓库，作为后续改造依据。

**Files:**
- Create: `docs/architecture/apkagent-refactor-overview.md`
- Modify: `docs/plans/2026-07-08-apkagent-perfect-refactor.md`

**Step 1:** 写当前问题、目标模块图、阶段性改造顺序。
**Step 2:** 标注当前已知风险：版本漂移、确认失效、Sandbox 语义漂移、Runtime 分散、文档过时。
**Step 3:** 完成后提交文档。

---

## Task 2: 统一版本来源

**Objective:** 消除硬编码版本漂移。

**Files:**
- Modify: `app/src/main/java/com/apkagent/ApkAgentApp.kt`
- Modify: `README.md`

**Step 1:** 把启动日志改为读取 `BuildConfig.VERSION_NAME` / `BuildConfig.VERSION_CODE`。
**Step 2:** 检查仓库内其他硬编码版本字符串并修正。
**Step 3:** 验证不再出现 `v3.5.0` 旧字符串。

---

## Task 3: 实现真实敏感操作确认 seam

**Objective:** 不再让敏感工具自动通过。

**Files:**
- Create: `app/src/main/java/com/apkagent/agent/ToolConfirmationManager.kt`
- Modify: `app/src/main/java/com/apkagent/ui/ChatViewModel.kt`
- Modify: `app/src/main/java/com/apkagent/ui/ChatScreen.kt`
- Modify: `app/src/main/java/com/apkagent/agent/ToolRiskLevel.kt`

**Step 1:** 建立确认请求数据模型与 StateFlow 队列。
**Step 2:** `onConfirmToolCall()` 改成挂起等待用户决策，而不是直接 `true`。
**Step 3:** 在 UI 中显示确认卡片/弹窗，包含工具名、风险级别、参数摘要。
**Step 4:** 支持 `CAUTION` 与 `DANGER` 的不同提示文案。

---

## Task 4: 修正 Sandbox 命名与职责

**Objective:** 让抽象名称和真实行为一致。

**Files:**
- Modify: `app/src/main/java/com/apkagent/apktools/Sandbox.kt`
- Modify: all references discovered by search

**Step 1:** 保持兼容前提下，把注释改成“路径解析/访问策略”。
**Step 2:** 如果不大规模改名，则至少引入清晰文档，说明当前不是沙箱。
**Step 3:** 为未来真正策略层预留接口。

---

## Task 5: 引入 RuntimeManager 深模块

**Objective:** 统一 Python / Node / Shell / Shizuku 的检测与执行入口。

**Files:**
- Create: `app/src/main/java/com/apkagent/runtime/RuntimeManager.kt`
- Modify: `app/src/main/java/com/apkagent/ui/SetupScreen.kt`
- Modify: `app/src/main/java/com/apkagent/apktools/PythonRunner.kt`
- Modify: `app/src/main/java/com/apkagent/tools/ScriptTools.kt`
- Modify: `app/src/main/java/com/apkagent/terminal/TerminalManager.kt`
- Modify: `app/src/main/java/com/apkagent/ApkAgentApp.kt`

**Step 1:** 抽象 runtime 状态与执行请求/结果模型。
**Step 2:** 将检测逻辑统一到 RuntimeManager。
**Step 3:** 将 Node/Python 执行入口统一，减少 SetupScreen/工具层重复判断。
**Step 4:** 保留现有能力，不做大范围行为倒退。

---

## Task 6: 拆出 ChatViewModel 的导出与历史职责

**Objective:** 降低 ChatViewModel 耦合度。

**Files:**
- Create: `app/src/main/java/com/apkagent/chat/ChatHistoryManager.kt`
- Create: `app/src/main/java/com/apkagent/export/ApkExportManager.kt`
- Modify: `app/src/main/java/com/apkagent/ui/ChatViewModel.kt`

**Step 1:** 抽离历史列表/加载/恢复逻辑。
**Step 2:** 抽离导出 patched APK 逻辑。
**Step 3:** ViewModel 只保留 orchestration。

---

## Task 7: 提升 ProjectAnalyzer 为双层分析入口

**Objective:** 保留快速摘要，同时预留深分析索引能力。

**Files:**
- Modify: `app/src/main/java/com/apkagent/project/ProjectAnalyzer.kt`
- Possibly create: `app/src/main/java/com/apkagent/project/ProjectIntelligence.kt`

**Step 1:** 把“快速摘要”逻辑显式命名。
**Step 2:** 增加扩展点，允许后续异步深分析。
**Step 3:** 不强推重度扫描，但先把接口设计好。

---

## Task 8: 重写 README 与补 architecture 文档

**Objective:** 文档与真实产品一致。

**Files:**
- Modify: `README.md`
- Create: `docs/architecture/runtime-and-confirmation.md`

**Step 1:** 说明项目化工作台定位。
**Step 2:** 说明权限模型、运行时模型、确认模型。
**Step 3:** 更新功能列表、架构图、已知限制。

---

## Task 9: 补测试

**Objective:** 给高风险 seam 加回归保护。

**Files:**
- Create/Modify tests under `app/src/test/java/...`

**Targets:**
- `ProjectStoreTest`
- `ProjectAnalyzerTest`
- `OpenAIClient` tool call aggregation parsing test（抽可测逻辑）
- `ToolConfirmationManagerTest`

---

## Task 10: 验证、代码审查、提交

**Objective:** 用真实命令验证，并留下清晰提交。

**Step 1:** 运行单测与必要的 Gradle 校验。
**Step 2:** 做独立 reviewer 审查。
**Step 3:** 修正问题后提交。

---

## Verification commands

```bash
cd /tmp/APKAgent
./gradlew test
./gradlew :app:testDebugUnitTest
git diff --stat
```

## Success criteria

- 敏感工具调用默认不再自动执行
- 启动日志版本与 Gradle 版本一致
- SetupScreen 不再自己拼 Node/Python 执行探测细节
- ChatViewModel 明显瘦身
- README 与当前实际产品一致
- 新增测试通过
