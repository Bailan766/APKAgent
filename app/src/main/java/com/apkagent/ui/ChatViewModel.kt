package com.apkagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apkagent.ApkAgentApp
import com.apkagent.agent.*
import com.apkagent.chat.ChatHistoryManager
import com.apkagent.export.ApkExportManager
import com.apkagent.i18n.AgentPrompts
import com.apkagent.i18n.LocaleManager
import com.apkagent.project.ReverseProject
import com.apkagent.service.KeepAliveManager
import com.apkagent.service.KeepAliveService
import com.apkagent.store.AgentConfig
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

    private var currentExecutionJob: Job? = null
    private var agentLoop: AgentLoop? = null
    private var lastConfig: AgentConfig? = null
    private var currentAssistantId: String? = null

    private val contentBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private var lastUpdateTime = 0L
    private val updateIntervalMs = 40L // 40ms 平滑节流，兼顾高帧率与极低延迟

    val config: StateFlow<AgentConfig> get() = agentApp.settingsStore.config

    init {
        selectProject(agentApp.currentProject.value)
    }

    fun selectProject(project: ReverseProject?) {
        agentApp.setCurrentProject(project)
        _currentProject.value = project
        _openApkName.value = project?.name ?: project?.importedApkPath?.let { File(it).name }

        // 工作区安全更新，避免跨项目文件污染
        agentLoop?.ctx?.updateWorkspace(agentApp.currentWorkspace())
        agentLoop?.ctx?.updateOpenApk(agentApp.currentOpenApk())

        agentLoop?.reset()
        agentLoop = null
        _messages.value = emptyList()
        loadHistoryList()
        restoreLastConversation()
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

        _messages.update { it + ChatItem(role = Role.USER, content = text) }
        _isRunning.value = true
        currentAssistantId = null
        contentBuffer.clear()
        reasoningBuffer.clear()

        Logger.i("VM", "开始执行: $text")
        KeepAliveManager.acquire(agentApp)
        KeepAliveService.roundCount = 0

        currentExecutionJob = viewModelScope.launch(Dispatchers.IO) {
            ensureLoop(cfg)
            agentLoop?.ctx?.updateWorkspace(agentApp.currentWorkspace())
            agentLoop?.ctx?.updateOpenApk(agentApp.currentOpenApk())

            try {
                withTimeout(5 * 60 * 1000L) {
                    agentLoop?.run(
                        userText = text,
                        initialSystemContext = getProjectInitialSystemContext()
                    )
                }
                Logger.i("VM", "执行完成")
                saveHistory(text)
            } catch (_: kotlinx.coroutines.CancellationException) {
                Logger.i("VM", "执行被手动取消")
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Logger.e("VM", "超时")
                _messages.update { it + ChatItem(role = Role.ERROR, content = LocaleManager.strings.timeout5min) }
            } catch (e: Throwable) {
                Logger.e("VM", "异常", e)
                _messages.update { it + ChatItem(role = Role.ERROR, content = LocaleManager.strings.fmt(LocaleManager.strings.exceptionPrefix, "msg" to e.message)) }
            } finally {
                _isRunning.value = false
                currentExecutionJob = null
                KeepAliveManager.release()
            }
        }
    }

    private fun ensureLoop(cfg: AgentConfig) {
        val ctx = ToolContext(
            appContext = agentApp,
            workspace = agentApp.currentWorkspace(),
            openApk = agentApp.currentOpenApk()
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
                maxRounds = cfg.maxRounds
            )
            lastConfig = cfg
        }
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

        // 如果最后一条助手消息还在 streaming，将其标记为完成
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
    private fun getProjectInitialSystemContext(): String? {
        val project = agentApp.currentProject.value ?: return null
        val summaryFile = agentApp.projectStore.getAnalysisMarkdownFile(project.id)
        val summary = runCatching { if (summaryFile.exists()) summaryFile.readText().take(10000) else "" }.getOrDefault("")
        if (summary.isBlank()) return null
        return AgentPrompts.projectContextHeader(
            projectName = project.name,
            projectDir = agentApp.projectStore.getProjectDir(project.id).absolutePath,
            apkPath = project.importedApkPath,
            analysisMarkdown = summary,
            userTask = ""
        )
    }

    // ── History ──
    fun loadHistoryList() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _historyList.value = historyManager.loadHistoryList(agentApp.currentProject.value)
            } catch (e: Exception) {
                Logger.e("VM", "加载历史失败: ${e.message}")
            }
        }
    }

    fun loadHistory(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = historyManager.loadHistory(agentApp.currentProject.value, id) ?: return@launch
                _messages.value = items.map { s ->
                    ChatItem(
                        id = s.id,
                        role = Role.valueOf(s.role),
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

    private fun saveHistory(userInput: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val msgs = _messages.value
                if (msgs.isEmpty()) return@launch
                val apkName = agentApp.currentProject.value?.name ?: "Unknown"
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
                historyManager.saveConversation(agentApp.currentProject.value, item, serializable)
                _historyList.update { listOf(item) + it }
            } catch (e: Exception) {
                Logger.e("VM", "保存历史失败: ${e.message}")
            }
        }
    }

    private fun restoreLastConversation() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = historyManager.restoreLastConversation(agentApp.currentProject.value) ?: return@launch
                _messages.value = items.map { s ->
                    ChatItem(
                        id = s.id,
                        role = Role.valueOf(s.role),
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
            if (id != null) {
                list.map {
                    if (it.id == id) it.copy(
                        content = fullContent,
                        reasoningContent = fullReasoning,
                        isThinking = false,
                        streaming = false
                    ) else it
                }
            } else list
        }
        contentBuffer.clear()
        reasoningBuffer.clear()
        currentAssistantId = null
    }

    override fun onToolCallStart(call: PendingToolCall) {
        Logger.i("VM", "[${call.name}] ${call.arguments.take(200)}")
        KeepAliveService.currentTask = call.name
        KeepAliveManager.updateTask("轮次 ${KeepAliveService.roundCount} · ${call.name}")
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
        val risk = ToolRiskLevel.infer(call.name)
        return confirmationManager.awaitDecision(call, risk)
    }

    override fun onToolCallComplete(call: ExecutedToolCall) {
        val ok = if (call.success) "OK" else "FAIL"
        Logger.i("VM", "[${call.name}] $ok (${call.result.length}chars)")
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
        _isRunning.value = false
    }

    override fun onPhaseChange(phase: ReversePhase) {
        Logger.i("VM", "Phase: $phase")
        _messages.update { it + ChatItem(role = Role.SYSTEM, content = phase.displayText()) }
    }

    fun approvePendingToolCall() = confirmationManager.approve()
    fun denyPendingToolCall() = confirmationManager.deny()
}
