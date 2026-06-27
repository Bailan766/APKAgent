package com.apkagent.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apkagent.ApkAgentApp
import com.apkagent.agent.AgentCallbacks
import com.apkagent.agent.AgentLoop
import com.apkagent.agent.ExecutedToolCall
import com.apkagent.agent.OpenAIClient
import com.apkagent.agent.PendingToolCall
import com.apkagent.agent.ToolContext
import com.apkagent.store.AgentConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class Role { USER, ASSISTANT, TOOL, ERROR, DEBUG }

data class ChatItem(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String = "",
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val toolSuccess: Boolean = true,
    val streaming: Boolean = false
)

class ChatViewModel(app: Application) : AndroidViewModel(app), AgentCallbacks {

    companion object {
        private const val TAG = "APKAgent"
    }

    private val agentApp get() = getApplication<ApkAgentApp>()

    private val _messages = MutableStateFlow<List<ChatItem>>(emptyList())
    val messages: StateFlow<List<ChatItem>> = _messages.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _openApkName = MutableStateFlow<String?>(null)
    val openApkName: StateFlow<String?> = _openApkName.asStateFlow()

    private var agentLoop: AgentLoop? = null
    private var lastConfig: AgentConfig? = null
    private var currentAssistantId: String? = null

    val config: StateFlow<AgentConfig> get() = agentApp.settingsStore.config

    fun setOpenApk(file: File?) {
        agentApp.setOpenApk(file)
        _openApkName.value = file?.name
        log("📁 APK: ${file?.name ?: "null"} (${file?.length() ?: 0} bytes)")
    }

    fun send(text: String) {
        if (text.isBlank() || _isRunning.value) return
        val cfg = config.value
        if (!cfg.isValid()) {
            _messages.update { it + ChatItem(role = Role.ERROR, content = "⚠ 请先在设置页填写 API Base、API Key 和模型名。") }
            return
        }
        _messages.update { it + ChatItem(role = Role.USER, content = text) }
        _isRunning.value = true
        currentAssistantId = null

        log("🚀 send: $text")

        viewModelScope.launch {
            ensureLoop(cfg)
            agentLoop?.ctx?.updateOpenApk(agentApp.openApk.value)
            try {
                agentLoop?.run(text)
            } catch (e: Throwable) {
                log("❌ run error: ${e.message}", Log.ERROR)
                _messages.update { it + ChatItem(role = Role.ERROR, content = "运行异常：${e.message}") }
            } finally {
                _isRunning.value = false
                log("✅ run finished")
            }
        }
    }

    private fun ensureLoop(cfg: AgentConfig) {
        val ctx = ToolContext(
            appContext = agentApp,
            workspace = agentApp.workspace,
            openApk = agentApp.openApk.value
        )
        if (agentLoop == null || lastConfig != cfg || lastConfig?.apiKey != cfg.apiKey) {
            log("🔧 init AgentLoop: model=${cfg.model}")
            val client = OpenAIClient(cfg.baseUrl, cfg.apiKey)
            agentLoop = AgentLoop(
                client = client,
                registry = agentApp.toolRegistry,
                model = cfg.model,
                temperature = cfg.temperature,
                ctx = ctx,
                callbacks = this,
                maxRounds = 15
            )
            lastConfig = cfg
        }
    }

    fun stop() {
        log("⏹ stop requested")
    }

    fun clearChat() {
        agentLoop?.reset()
        agentLoop = null
        _messages.value = emptyList()
        log("🧹 chat cleared")
    }

    // —— AgentCallbacks ——
    override fun onAssistantContentDelta(delta: String) {
        _messages.update { list ->
            val id = currentAssistantId
            if (id != null && list.any { it.id == id && it.role == Role.ASSISTANT }) {
                list.map { if (it.id == id) it.copy(content = it.content + delta) else it }
            } else {
                val newId = UUID.randomUUID().toString()
                currentAssistantId = newId
                list + ChatItem(id = newId, role = Role.ASSISTANT, content = delta, streaming = true)
            }
        }
    }

    override fun onAssistantTurnComplete(content: String, toolCalls: List<PendingToolCall>) {
        _messages.update { list ->
            val id = currentAssistantId
            if (id != null) {
                list.map { if (it.id == id) it.copy(streaming = false) else it }
            } else list
        }
        currentAssistantId = null
    }

    override fun onToolCallStart(call: PendingToolCall) {
        log("🔨 tool start: ${call.name} args=${call.arguments.take(200)}")
        val item = ChatItem(
            role = Role.TOOL,
            toolName = call.name,
            toolArgs = call.arguments,
            streaming = true
        )
        _messages.update { it + item }
    }

    /** 默认允许所有敏感操作，不弹窗确认 */
    override suspend fun onConfirmToolCall(call: PendingToolCall): Boolean {
        log("🟢 auto-approve: ${call.name}")
        return true
    }

    override fun onToolCallComplete(call: ExecutedToolCall) {
        val status = if (call.success) "✅" else "❌"
        log("🔨 tool done: ${call.name} $status result=${call.result.take(200)}")
        _messages.update { list ->
            val idx = list.indexOfLast { it.role == Role.TOOL && it.streaming && it.toolName == call.name }
            if (idx >= 0) {
                list.toMutableList().also { it[idx] = it[idx].copy(toolResult = call.result, toolSuccess = call.success, streaming = false) }
            } else list
        }
    }

    override fun onError(message: String) {
        log("❌ error: $message", Log.ERROR)
        _messages.update { it + ChatItem(role = Role.ERROR, content = "⚠ $message") }
    }

    override fun onFinished() {
        _isRunning.value = false
        log("🏁 agent finished")
    }

    /** Debug 日志：同时输出到 logcat 和聊天消息 */
    private fun log(msg: String, level: Int = Log.DEBUG) {
        Log.println(level, TAG, msg)
        _messages.update { it + ChatItem(role = Role.DEBUG, content = "[debug] $msg") }
    }
}
