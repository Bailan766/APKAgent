package com.apkagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apkagent.ApkAgentApp
import com.apkagent.agent.*
import com.apkagent.chat.ChatHistoryManager
import com.apkagent.export.ApkExportManager
import com.apkagent.project.ReverseProject
import com.apkagent.store.AgentConfig
import com.apkagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

import com.apkagent.service.KeepAliveService
import com.apkagent.service.KeepAliveManager

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
    val toolSuccess: Boolean = true
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
    val streaming: Boolean = false
)

class ChatViewModel(app: Application) : AndroidViewModel(app), AgentCallbacks {

    private val agentApp get() = getApplication<ApkAgentApp>()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
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

    private var agentLoop: AgentLoop? = null
    private var lastConfig: AgentConfig? = null
    private var currentAssistantId: String? = null
    private val contentBuffer = StringBuilder()
    private var lastUpdateTime = 0L
    private val updateIntervalMs = 50L // 50ms 节流，保证流畅又不卡顿

    val config: StateFlow<AgentConfig> get() = agentApp.settingsStore.config

    init {
        selectProject(agentApp.currentProject.value)
    }

    fun selectProject(project: ReverseProject?) {
        agentApp.setCurrentProject(project)
        _currentProject.value = project
        _openApkName.value = project?.name ?: project?.importedApkPath?.let { File(it).name }
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
            _messages.update { it + ChatItem(role = Role.ERROR, content = "请先在设置页选择 AI 提供商并填写 API Key") }
            return
        }
        _messages.update { it + ChatItem(role = Role.USER, content = text) }
        _isRunning.value = true
        currentAssistantId = null

        Logger.i("VM", "开始: $text")
        KeepAliveManager.acquire(agentApp)
        KeepAliveService.roundCount = 0
        viewModelScope.launch(Dispatchers.IO) {
            ensureLoop(cfg)
            agentLoop?.ctx?.updateOpenApk(agentApp.currentOpenApk())
            try {
                withTimeout(5 * 60 * 1000L) { agentLoop?.run(buildProjectAwarePrompt(text)) }
                Logger.i("VM", "完成")
                saveHistory(text)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Logger.e("VM", "超时")
                _messages.update { it + ChatItem(role = Role.ERROR, content = "超时（5分钟）") }
            } catch (e: Throwable) {
                Logger.e("VM", "异常", e)
                _messages.update { it + ChatItem(role = Role.ERROR, content = "异常: ${e.message}") }
            } finally {
                _isRunning.value = false
                KeepAliveManager.release()
            }
        }
    }

    private fun ensureLoop(cfg: AgentConfig) {
        val ctx = ToolContext(appContext = agentApp, workspace = agentApp.currentWorkspace(), openApk = agentApp.currentOpenApk())
        if (agentLoop == null || lastConfig != cfg) {
            Logger.i("VM", "AgentLoop: ${cfg.providerId}/${cfg.model}")
            agentLoop = AgentLoop(OpenAIClient(cfg.baseUrl, cfg.apiKey), agentApp.toolRegistry, cfg.model, cfg.temperature, ctx, this, maxRounds = cfg.maxRounds)
            lastConfig = cfg
        }
    }

    fun stop() { Logger.i("VM", "停止"); confirmationManager.clearPending() }
    fun clearChat() { agentLoop?.reset(); agentLoop = null; _messages.value = emptyList(); confirmationManager.clearPending(); Logger.i("VM", "清空") }

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
                withContext(Dispatchers.Main) { onResult(false, "导出失败: ${e.message}") }
            } finally {
                _isExporting.value = false
            }
        }
    }

    // ── History ──
    private fun historyDir(): File = historyManager.historyDir(agentApp.currentProject.value)

    private fun buildProjectAwarePrompt(userText: String): String {
        val project = agentApp.currentProject.value ?: return userText
        val summaryFile = agentApp.projectStore.getAnalysisMarkdownFile(project.id)
        val summary = runCatching { if (summaryFile.exists()) summaryFile.readText().take(12000) else "" }.getOrDefault("")
        if (summary.isBlank()) return userText
        return """
当前逆向项目：${project.name}
项目目录：${agentApp.projectStore.getProjectDir(project.id).absolutePath}
APK路径：${project.importedApkPath}

以下是导入后自动预分析结果。回答和工具调用必须基于这些真实上下文，不要瞎猜；如信息不足，先调用工具读取项目文件再判断。

$summary

用户任务：
$userText
""".trimIndent()
    }

    fun loadHistoryList() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _historyList.value = historyManager.loadHistoryList(agentApp.currentProject.value)
            } catch (_: Throwable) {}
        }
    }

    fun loadHistory(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = historyManager.loadHistory(agentApp.currentProject.value, id) ?: return@launch
                val restored = items.map { m ->
                    ChatItem(
                        id = m.id, role = Role.valueOf(m.role), content = m.content,
                        toolCallId = m.toolCallId, toolName = m.toolName,
                        toolArgs = m.toolArgs, toolResult = m.toolResult, toolSuccess = m.toolSuccess
                    )
                }
                _messages.value = restored
                Logger.i("VM", "加载历史: $id (${restored.size}条)")
            } catch (_: Throwable) {}
        }
    }

    private fun saveHistory(userInput: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val msgs = _messages.value
                val item = HistoryItem(
                    id = UUID.randomUUID().toString(),
                    time = System.currentTimeMillis(),
                    apkName = _openApkName.value ?: "",
                    userInput = userInput.take(80),
                    messageCount = msgs.size
                )
                val serializable = msgs.map { m ->
                    SerializableChatItem(
                        id = m.id, role = m.role.name, content = m.content,
                        toolCallId = m.toolCallId, toolName = m.toolName,
                        toolArgs = m.toolArgs, toolResult = m.toolResult, toolSuccess = m.toolSuccess
                    )
                }
                historyManager.saveConversation(agentApp.currentProject.value, item, serializable)
                _historyList.update { listOf(item) + it }
                Logger.i("VM", "历史已保存: ${item.messageCount}条消息")
            } catch (e: Throwable) {
                Logger.w("VM", "保存历史失败: ${e.message}")
            }
        }
    }

    private fun restoreLastConversation() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = historyManager.restoreLastConversation(agentApp.currentProject.value) ?: return@launch
                val restored = items.map { m ->
                    ChatItem(
                        id = m.id, role = Role.valueOf(m.role), content = m.content,
                        toolCallId = m.toolCallId, toolName = m.toolName,
                        toolArgs = m.toolArgs, toolResult = m.toolResult, toolSuccess = m.toolSuccess
                    )
                }
                if (restored.isNotEmpty()) {
                    _messages.value = restored
                    Logger.i("VM", "已恢复上次对话: ${restored.size}条消息")
                }
            } catch (e: Throwable) {
                Logger.w("VM", "恢复对话失败: ${e.message}")
            }
        }
    }

    fun getLogPath(): String? = Logger.getLogPath()

    // ── Callbacks ──
    override fun onAssistantContentDelta(delta: String) {
        contentBuffer.append(delta)
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime >= updateIntervalMs || delta.contains('\n')) {
            lastUpdateTime = now
            val fullContent = contentBuffer.toString()
            _messages.update { list ->
                val id = currentAssistantId
                if (id != null && list.any { it.id == id && it.role == Role.ASSISTANT })
                    list.map { if (it.id == id) it.copy(content = fullContent) else it }
                else {
                    val newId = UUID.randomUUID().toString()
                    currentAssistantId = newId
                    list + ChatItem(id = newId, role = Role.ASSISTANT, content = fullContent, streaming = true)
                }
            }
        }
    }
    override fun onAssistantTurnComplete(content: String, toolCalls: List<PendingToolCall>) {
        // 最终同步一次完整内容
        val fullContent = contentBuffer.toString()
        _messages.update { list ->
            val id = currentAssistantId
            if (id != null) list.map { if (it.id == id) it.copy(content = fullContent, streaming = false) else it } else list
        }
        contentBuffer.clear()
        currentAssistantId = null
    }
    override fun onToolCallStart(call: PendingToolCall) {
        Logger.i("VM", "[${call.name}] ${call.arguments.take(200)}")
        KeepAliveService.currentTask = call.name
        KeepAliveManager.updateTask("轮次 ${KeepAliveService.roundCount} · ${call.name}")
        _messages.update { it + ChatItem(role = Role.TOOL, toolCallId = call.id, toolName = call.name, toolArgs = call.arguments, streaming = true) }
    }
    override suspend fun onConfirmToolCall(call: PendingToolCall): Boolean {
        val risk = ToolRiskLevel.infer(call.name)
        return confirmationManager.awaitDecision(call, risk)
    }
    override fun onToolCallComplete(call: ExecutedToolCall) {
        val ok = if (call.success) "OK" else "FAIL"
        Logger.i("VM", "[${call.name}] $ok (${call.result.length}chars)")
        _messages.update { list ->
            // 并行执行时用 toolCallId 精确匹配，防止同名工具匹配错误
            val idx = list.indexOfLast { it.role == Role.TOOL && it.streaming && it.toolCallId == call.id }
            if (idx >= 0) list.toMutableList().also { it[idx] = it[idx].copy(toolResult = call.result, toolSuccess = call.success, streaming = false) } else list
        }
    }
    override fun onError(message: String) {
        Logger.e("VM", "Error: $message")
        _messages.update { it + ChatItem(role = Role.ERROR, content = message) }
    }
    override fun onFinished() { _isRunning.value = false }
    override fun onPhaseChange(phase: ReversePhase) {
        Logger.i("VM", "Phase: $phase")
        _messages.update { it + ChatItem(role = Role.SYSTEM, content = phase.displayText()) }
    }

    fun approvePendingToolCall() = confirmationManager.approve()
    fun denyPendingToolCall() = confirmationManager.deny()
}
