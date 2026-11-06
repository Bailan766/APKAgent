package com.apkagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apkagent.ApkAgentApp
import com.apkagent.agent.*
import com.apkagent.chat.ChatHistoryManager
import com.apkagent.export.ApkExportManager
import com.apkagent.i18n.AgentPrompts
import com.apkagent.i18n.L
import com.apkagent.i18n.LocaleManager
import com.apkagent.project.ReverseProject
import com.apkagent.service.KeepAliveManager
import com.apkagent.service.KeepAliveService
import com.apkagent.store.AgentConfig
import com.apkagent.store.ExecutionMode
import com.apkagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

enum class Role { USER, ASSISTANT, TOOL, ERROR, DEBUG, SYSTEM }

@Serializable
data class HistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val time: Long = System.currentTimeMillis(),
    val apkName: String = "",
    val userInput: String = "",
    val messageCount: Int = 0
)

@Serializable
data class SerializableChatItem(
    val id: String = "",
    val role: String = "",
    val content: String = "",
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val toolSuccess: Boolean = true,
    val reasoningContent: String? = null
)

data class ChatItem(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String = "",
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val toolSuccess: Boolean = true,
    val streaming: Boolean = false,
    val reasoningContent: String? = null,
    val isThinking: Boolean = false
)

class ChatViewModel(app: Application) : AndroidViewModel(app), AgentCallbacks {

    private val agentApp get() = getApplication<ApkAgentApp>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
    private val confirmationManager = ToolConfirmationManager()
    private val historyManager by lazy { ChatHistoryManager(agentApp.workspace, agentApp.projectStore, json) }
    private val exportManager by lazy { ApkExportManager() }

    private val _messages = MutableStateFlow<List<ChatItem>>(emptyList())
    val messages: StateFlow<List<ChatItem>> = _messages.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _openApkName = MutableStateFlow<String?>(null)
    val openApkName: StateFlow<String?> = _openApkName.asStateFlow()

    private val _currentProject = MutableStateFlow<ReverseProject?>(agentApp.currentProject.value)
    val currentProject: StateFlow<ReverseProject?> = _currentProject.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _historyList = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyList: StateFlow<List<HistoryItem>> = _historyList.asStateFlow()

    val pendingConfirmation: StateFlow<ToolConfirmationManager.ConfirmationRequest?> = confirmationManager.pendingRequest

    val executionMode = MutableStateFlow(agentApp.settingsStore.getExecutionMode())

    fun setExecutionMode(mode: ExecutionMode) {
        agentApp.settingsStore.setExecutionMode(mode)
        executionMode.value = mode
    }

    fun toggleExecutionMode() {
        val next = if (executionMode.value == ExecutionMode.FAST) ExecutionMode.SAFE else ExecutionMode.FAST
        setExecutionMode(next)
    }

    // 保留旧方法但不再由确认卡调用（避免一键永久关闭安全保护）
    fun approveAndSwitchToFastMode() {
        setExecutionMode(ExecutionMode.FAST)
        confirmationManager.approve()
    }

    private var currentExecutionJob: Job? = null
    private var historyLoadJob: Job? = null
    private var agentLoop: AgentLoop? = null
    private var lastConfig: AgentConfig? = null
    private var currentAssistantId: String? = null
    private var lastSelectedProjectId: String? = null
    private var hasSelected = false

    private val contentBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private var lastUpdateTime = 0L
    private val updateIntervalMs = 40L // 40ms 平滑节流，兼顾高帧率与极低延迟

    val config: StateFlow<AgentConfig> get() = agentApp.settingsStore.config

    init {
        selectProject(agentApp.currentProject.value)
    }

    fun selectProject(project: ReverseProject?) {
        // 从设置/编辑器返回或旋转时 LaunchedEffect 会用同一个 project 重跑，
        // 仅在 id 真正变化时才清空会话/重新加载，避免误清用户消息。
        if (hasSelected && lastSelectedProjectId == project?.id) return
        hasSelected = true
        lastSelectedProjectId = project?.id

        // 切换项目：先硬停止在途执行并取消在途的历史加载，避免 A→B 串台。
        stop()
        historyLoadJob?.cancel()

        agentApp.setCurrentProject(project)
        _currentProject.value = project
        _openApkName.value = project?.name ?: project?.importedApkPath?.let { File(it).name }

        agentLoop?.ctx?.updateWorkspace(agentApp.currentWorkspace())
        agentLoop?.ctx?.updateOpenApk(agentApp.currentOpenApk())

        agentLoop?.reset()
        agentLoop = null
        _messages.value = emptyList()
        loadHistoryList()
        restoreLastConversation(project)
    }

    fun setOpenApk(file: File?) {
        agentApp.setOpenApk(file)
        selectProject(agentApp.currentProject.value)
        Logger.i("VM", "APK: ${file?.name} size=${file?.length() ?: 0}")
    }

    fun send(text: String) {
        if (text.isBlank() || _isRunning.value) return
        val cfg = config.value
        if (!cfg.isValid()) {
            _messages.update { it + ChatItem(role = Role.ERROR, content = LocaleManager.strings.needApiKey) }
            return
        }

        val priorContext = buildRestoredHistorySummary(_messages.value)
        _messages.update { it + ChatItem(role = Role.USER, content = text) }
        _isRunning.value = true
        currentAssistantId = null
        contentBuffer.clear()
        reasoningBuffer.clear()

        Logger.i("VM", "开始执行: $text")
        val targetName = _openApkName.value.orEmpty().ifBlank {
            _currentProject.value?.name.orEmpty().ifBlank { "APKAgent" }
        }
        KeepAliveService.currentApkName = targetName
        KeepAliveService.capsuleStatus = if (executionMode.value == ExecutionMode.FAST) "⚡ 快速模式" else "🛡️ 安全模式"
        KeepAliveService.roundCount = 0
        KeepAliveManager.acquire(agentApp)

        // 项目/工作区/APK 以参数快照传入，避免异步执行期间切到新项目后串台、写错历史
        val projectSnapshot = agentApp.currentProject.value
        val workspaceSnapshot = agentApp.currentWorkspace()
        val openApkSnapshot = agentApp.currentOpenApk()

        currentExecutionJob = viewModelScope.launch(Dispatchers.IO) {
            val thisJob = coroutineContext[Job]
            ensureLoop(cfg, workspaceSnapshot, openApkSnapshot)
            agentLoop?.ctx?.updateWorkspace(workspaceSnapshot)
            agentLoop?.ctx?.updateOpenApk(openApkSnapshot)

            try {
                withTimeout(5 * 60 * 1000L) {
                    val sysContext = getProjectInitialSystemContext(projectSnapshot, priorContext)
                    agentLoop?.run(
                        userText = text,
                        initialSystemContext = sysContext
                    )
                    // 如果因提供商未切换导致鉴权失败 (401)，自动探测匹配的预设提供商并无感重试一次
                    val err = agentLoop?.state?.value?.error.orEmpty()
                    if (err.isNotEmpty() && cfg.providerId != "custom" &&
                        (err.contains("鉴权失败") || err.contains("401") || err.contains("invalid", ignoreCase = true) || err.contains("auth", ignoreCase = true))
                    ) {
                        val probed = OpenAIClient.probeWorkingProvider(cfg.apiKey)
                        if (probed != null && probed.first.id != cfg.providerId) {
                            val (matchedProvider, matchedModels) = probed
                            val newModel = matchedProvider.defaultModel.takeIf { it in matchedModels }
                                ?: matchedModels.firstOrNull()
                                ?: matchedProvider.defaultModel
                            val fixedCfg = cfg.copy(
                                providerId = matchedProvider.id,
                                baseUrl = matchedProvider.baseUrl,
                                model = newModel
                            )
                            agentApp.settingsStore.save(fixedCfg)
                            _messages.update { list ->
                                val cleaned = if (list.lastOrNull()?.role == Role.ERROR) list.dropLast(1) else list
                                cleaned + ChatItem(
                                    role = Role.SYSTEM,
                                    content = "✅ 已自动识别您的 API Key 属于【${matchedProvider.label}】，已切换至 $newModel 并继续执行"
                                )
                            }
                            agentLoop = null
                            ensureLoop(fixedCfg, workspaceSnapshot, openApkSnapshot)
                            agentLoop?.run(
                                userText = text,
                                initialSystemContext = sysContext
                            )
                        }
                    }
                }
                Logger.i("VM", "执行完成")
                saveHistory(projectSnapshot, text)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Logger.e("VM", "超时")
                _messages.update { it + ChatItem(role = Role.ERROR, content = LocaleManager.strings.timeout5min) }
                cancelAndResetLoop()
            } catch (_: kotlinx.coroutines.CancellationException) {
                Logger.i("VM", "执行被手动取消")
                cancelAndResetLoop()
            } catch (e: Throwable) {
                Logger.e("VM", "异常", e)
                _messages.update { it + ChatItem(role = Role.ERROR, content = LocaleManager.strings.fmt(LocaleManager.strings.exceptionPrefix, "msg" to e.message)) }
                cancelAndResetLoop()
            } finally {
                // 状态与 job 引用只在这里维护：置空前确认仍属于本次执行，
                // 避免旧 job 的 finally 清掉新 job 引用、误放输入或提前释放保活。
                if (currentExecutionJob === thisJob) {
                    currentExecutionJob = null
                    _isRunning.value = false
                    KeepAliveManager.release()
                }
            }
        }
    }

    private fun ensureLoop(cfg: AgentConfig, workspace: File, openApk: File?) {
        val ctx = ToolContext(
            appContext = agentApp,
            workspace = workspace,
            openApk = openApk
        )
        if (agentLoop == null || lastConfig != cfg) {
            Logger.i("VM", "创建 AgentLoop: ${cfg.providerId}/${cfg.model}")
            agentLoop = AgentLoop(
                client = OpenAIClient(cfg.baseUrl, cfg.apiKey),
                registry = agentApp.toolRegistry,
                model = cfg.model,
                temperature = cfg.temperature,
                ctx = ctx,
                callbacks = this,
                maxRounds = cfg.maxRounds,
                systemExtra = cfg.systemExtra
            )
            lastConfig = cfg
        }
    }

    /** 超时/异常/取消时取消网络请求并丢弃半截对话，防止带入下一轮 */
    private fun cancelAndResetLoop() {
        try { agentLoop?.cancel() } catch (_: Throwable) {}
        try { agentLoop?.reset() } catch (_: Throwable) {}
        agentLoop = null
        lastConfig = null
    }

    /** 真正的硬中断停止：取消协程 Job、关闭网络 Socket 流、重置状态 */
    fun stop() {
        Logger.i("VM", "手动停止任务")
        try {
            agentLoop?.cancel()
            currentExecutionJob?.cancel()
        } catch (_: Throwable) {}
        currentExecutionJob = null
        confirmationManager.clearPending()
        _isRunning.value = false
        KeepAliveManager.release()

        _messages.update { list ->
            val last = list.lastOrNull()
            if (last != null && last.streaming) {
                list.dropLast(1) + last.copy(streaming = false, isThinking = false)
            } else list
        }
    }

    fun clearChat() {
        stop()
        agentLoop?.reset()
        agentLoop = null
        _messages.value = emptyList()
        // 同步移除「最近会话」标记，否则返回/旋转后 restoreLastConversation 会把已清空会话恢复回来
        runCatching {
            File(historyManager.historyDir(agentApp.currentProject.value), "last_id.txt").delete()
        }
        Logger.i("VM", "清空会话")
    }

    // ── Export ──
    fun exportPatchedApk(onResult: (Boolean, String) -> Unit) {
        if (_isExporting.value) return
        _isExporting.value = true
        Logger.i("VM", "导出开始")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ws = agentApp.currentWorkspace()
                val result = exportManager.export(ws, agentApp.currentOpenApk())
                withContext(Dispatchers.Main) { onResult(result.success, result.message) }
            } catch (e: Throwable) {
                Logger.e("VM", "导出失败", e)
                withContext(Dispatchers.Main) { onResult(false, LocaleManager.strings.fmt(LocaleManager.strings.exportFailed, "msg" to e.message)) }
            } finally {
                _isExporting.value = false
            }
        }
    }

    // ── Context Prompt ──
    private fun getProjectInitialSystemContext(
        project: ReverseProject?,
        restoredHistorySummary: String? = null
    ): String? {
        val baseContext = if (project != null) {
            val summaryFile = agentApp.projectStore.getAnalysisMarkdownFile(project.id)
            val summary = runCatching { if (summaryFile.exists()) summaryFile.readText().take(10000) else "" }.getOrDefault("")
            if (summary.isNotBlank()) {
                AgentPrompts.projectContextHeader(
                    projectName = project.name,
                    projectDir = agentApp.projectStore.getProjectDir(project.id).absolutePath,
                    apkPath = project.importedApkPath,
                    analysisMarkdown = summary,
                    userTask = ""
                )
            } else null
        } else null

        return when {
            baseContext != null && !restoredHistorySummary.isNullOrBlank() -> "$baseContext\n\n$restoredHistorySummary"
            baseContext != null -> baseContext
            !restoredHistorySummary.isNullOrBlank() -> restoredHistorySummary
            else -> null
        }
    }

    private fun buildRestoredHistorySummary(existingMessages: List<ChatItem>): String? {
        val turns = existingMessages.filter { it.role == Role.USER || it.role == Role.ASSISTANT }
            .takeLast(6)
        if (turns.isEmpty()) return null
        // 用 L() 每次取当前语言，切语言后重新发送即按新语言生成，不缓存旧语言快照
        return buildString {
            appendLine(L("## 历史会话上下文摘要", "## Restored conversation context"))
            turns.forEach { item ->
                val speaker = if (item.role == Role.USER) L("用户", "User") else L("助手", "Assistant")
                appendLine("- $speaker: ${item.content.take(400)}")
            }
        }
    }

    // ── History ──
    fun loadHistoryList() {
        val project = agentApp.currentProject.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = historyManager.loadHistoryList(project)
                if (_currentProject.value?.id == project?.id) _historyList.value = list
            } catch (e: Exception) {
                Logger.e("VM", "加载历史失败: ${e.message}")
            }
        }
    }

    fun loadHistory(id: String) {
        val project = agentApp.currentProject.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = historyManager.loadHistory(project, id) ?: return@launch
                if (_currentProject.value?.id != project?.id) return@launch
                agentLoop?.reset()
                agentLoop = null
                _messages.value = items.map { s ->
                    ChatItem(
                        id = s.id,
                        role = runCatching { Role.valueOf(s.role) }.getOrDefault(Role.ASSISTANT),
                        content = s.content,
                        toolCallId = s.toolCallId,
                        toolName = s.toolName,
                        toolArgs = s.toolArgs,
                        toolResult = s.toolResult,
                        toolSuccess = s.toolSuccess,
                        reasoningContent = s.reasoningContent
                    )
                }
            } catch (e: Exception) {
                Logger.e("VM", "加载历史记录失败: ${e.message}")
            }
        }
    }

    private fun restoreLastConversation(project: ReverseProject?) {
        historyLoadJob?.cancel()
        historyLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = historyManager.restoreLastConversation(project) ?: return@launch
                if (_currentProject.value?.id != project?.id) return@launch
                _messages.value = items.map { s ->
                    ChatItem(
                        id = s.id,
                        role = runCatching { Role.valueOf(s.role) }.getOrDefault(Role.ASSISTANT),
                        content = s.content,
                        toolCallId = s.toolCallId,
                        toolName = s.toolName,
                        toolArgs = s.toolArgs,
                        toolResult = s.toolResult,
                        toolSuccess = s.toolSuccess,
                        reasoningContent = s.reasoningContent
                    )
                }
            } catch (e: Exception) {
                Logger.e("VM", "恢复会话失败: ${e.message}")
            }
        }
    }

    private fun saveHistory(project: ReverseProject?, userInput: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val msgs = _messages.value
                if (msgs.isEmpty()) return@launch
                val apkName = project?.name ?: "Unknown"
                val item = HistoryItem(
                    apkName = apkName,
                    userInput = userInput.take(50),
                    messageCount = msgs.size
                )
                val serializable = msgs.map {
                    SerializableChatItem(
                        id = it.id,
                        role = it.role.name,
                        content = it.content,
                        toolCallId = it.toolCallId,
                        toolName = it.toolName,
                        toolArgs = it.toolArgs,
                        toolResult = it.toolResult,
                        toolSuccess = it.toolSuccess,
                        reasoningContent = it.reasoningContent
                    )
                }
                historyManager.saveConversation(project, item, serializable)
                if (_currentProject.value?.id == project?.id) _historyList.update { listOf(item) + it }
            } catch (e: Exception) {
                Logger.e("VM", "保存历史失败: ${e.message}")
            }
        }
    }

    fun getLogPath(): String? = Logger.getLogPath()

    // ── Callbacks ──
    override fun onAssistantReasoningDelta(delta: String) {
        reasoningBuffer.append(delta)
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime >= updateIntervalMs) {
            lastUpdateTime = now
            val fullReasoning = reasoningBuffer.toString()
            _messages.update { list ->
                val id = currentAssistantId
                if (id != null && list.any { it.id == id && it.role == Role.ASSISTANT }) {
                    list.map {
                        if (it.id == id) it.copy(reasoningContent = fullReasoning, isThinking = true, streaming = true)
                        else it
                    }
                } else {
                    val newId = UUID.randomUUID().toString()
                    currentAssistantId = newId
                    list + ChatItem(
                        id = newId,
                        role = Role.ASSISTANT,
                        content = "",
                        reasoningContent = fullReasoning,
                        isThinking = true,
                        streaming = true
                    )
                }
            }
        }
    }

    override fun onAssistantContentDelta(delta: String) {
        contentBuffer.append(delta)
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime >= updateIntervalMs || delta.contains('\n')) {
            lastUpdateTime = now
            val fullContent = contentBuffer.toString()
            val fullReasoning = reasoningBuffer.toString().ifBlank { null }
            _messages.update { list ->
                val id = currentAssistantId
                if (id != null && list.any { it.id == id && it.role == Role.ASSISTANT }) {
                    list.map {
                        if (it.id == id) it.copy(
                            content = fullContent,
                            reasoningContent = fullReasoning,
                            isThinking = false,
                            streaming = true
                        ) else it
                    }
                } else {
                    val newId = UUID.randomUUID().toString()
                    currentAssistantId = newId
                    list + ChatItem(
                        id = newId,
                        role = Role.ASSISTANT,
                        content = fullContent,
                        reasoningContent = fullReasoning,
                        isThinking = false,
                        streaming = true
                    )
                }
            }
        }
    }

    override fun onAssistantTurnComplete(content: String, toolCalls: List<PendingToolCall>, reasoning: String?) {
        val fullContent = contentBuffer.toString().ifBlank { content }
        val fullReasoning = reasoningBuffer.toString().ifBlank { reasoning }
        _messages.update { list ->
            val id = currentAssistantId
            // 找不到对应 id 时不能只做 map（会吞掉整轮回复），必须补一条气泡
            if (id != null && list.any { it.id == id && it.role == Role.ASSISTANT }) {
                list.map {
                    if (it.id == id) it.copy(
                        content = fullContent,
                        reasoningContent = fullReasoning,
                        isThinking = false,
                        streaming = false
                    ) else it
                }
            } else if (fullContent.isNotBlank() || !fullReasoning.isNullOrBlank()) {
                // 响应极短（小于 40ms 节流窗口且不含换行）或气泡已丢失时补齐助手消息
                list + ChatItem(
                    role = Role.ASSISTANT,
                    content = fullContent,
                    reasoningContent = fullReasoning,
                    isThinking = false,
                    streaming = false
                )
            } else list
        }
        contentBuffer.clear()
        reasoningBuffer.clear()
        currentAssistantId = null
    }

    override fun onToolCallStart(call: PendingToolCall) {
        Logger.i("VM", "[${call.name}] ${call.arguments.take(200)}")
        KeepAliveService.currentTask = call.name
        val toolDesc = KeepAliveService.humanReadableTool(call.name)
        KeepAliveManager.updateTask(
            taskName = toolDesc,
            apkName = KeepAliveService.currentApkName,
            status = if (executionMode.value == ExecutionMode.FAST) "⚡ 快速执行" else "🛡️ 待确认"
        )
        _messages.update {
            it + ChatItem(
                role = Role.TOOL,
                toolCallId = call.id,
                toolName = call.name,
                toolArgs = call.arguments,
                streaming = true
            )
        }
    }

    override suspend fun onConfirmToolCall(call: PendingToolCall): Boolean {
        if (executionMode.value == ExecutionMode.FAST) {
            Logger.i("VM", "⚡ 快速模式：已自动放行敏感操作 [${call.name}]")
            return true
        }
        val risk = ToolRiskLevel.infer(call.name)
        KeepAliveManager.updateTask(
            taskName = "⚠️ 敏感操作待确认: ${call.name}",
            apkName = KeepAliveService.currentApkName,
            status = "🛡️ 待确认"
        )
        return confirmationManager.awaitDecision(call, risk)
    }

    override fun onToolCallComplete(call: ExecutedToolCall) {
        val ok = if (call.success) "OK" else "FAIL"
        Logger.i("VM", "[${call.name}] $ok (${call.result.length}chars)")
        val toolDesc = KeepAliveService.humanReadableTool(call.name)
        KeepAliveManager.updateTask(
            taskName = if (call.success) "✅ $toolDesc" else "❌ $toolDesc 失败",
            apkName = KeepAliveService.currentApkName,
            status = if (executionMode.value == ExecutionMode.FAST) "⚡ 快速执行" else "🛡️ 安全审计"
        )
        _messages.update { list ->
            val idx = list.indexOfLast { it.role == Role.TOOL && it.streaming && it.toolCallId == call.id }
            if (idx >= 0) {
                list.toMutableList().also {
                    it[idx] = it[idx].copy(
                        toolResult = call.result,
                        toolSuccess = call.success,
                        streaming = false
                    )
                }
            } else list
        }
    }

    override fun onError(message: String) {
        Logger.e("VM", "Error: $message")
        _messages.update { it + ChatItem(role = Role.ERROR, content = message) }
    }

    override fun onFinished() {
        // 运行状态与 job 引用统一由 send() 的 finally 维护：
        // 若此处提前置 _isRunning=false，旧 job 的 finally 会清掉新 job 引用并提前释放保活。
    }

    override fun onPhaseChange(phase: ReversePhase) {
        Logger.i("VM", "Phase: $phase")
        _messages.update { it + ChatItem(role = Role.SYSTEM, content = phase.displayText()) }
    }

    fun approvePendingToolCall() = confirmationManager.approve()
    fun denyPendingToolCall() = confirmationManager.deny()
}
