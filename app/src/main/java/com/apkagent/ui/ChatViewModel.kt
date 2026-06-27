package com.apkagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apkagent.ApkAgentApp
import com.apkagent.agent.*
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

enum class Role { USER, ASSISTANT, TOOL, ERROR, DEBUG }

@Serializable
data class HistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val time: Long = System.currentTimeMillis(),
    val apkName: String = "",
    val userInput: String = "",
    val messageCount: Int = 0
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

    private val _messages = MutableStateFlow<List<ChatItem>>(emptyList())
    val messages: StateFlow<List<ChatItem>> = _messages.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _openApkName = MutableStateFlow<String?>(null)
    val openApkName: StateFlow<String?> = _openApkName.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _historyList = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyList: StateFlow<List<HistoryItem>> = _historyList.asStateFlow()

    private var agentLoop: AgentLoop? = null
    private var lastConfig: AgentConfig? = null
    private var currentAssistantId: String? = null

    val config: StateFlow<AgentConfig> get() = agentApp.settingsStore.config

    init { loadHistoryList() }

    fun setOpenApk(file: File?) {
        agentApp.setOpenApk(file)
        _openApkName.value = file?.name
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
        viewModelScope.launch(Dispatchers.IO) {
            ensureLoop(cfg)
            agentLoop?.ctx?.updateOpenApk(agentApp.openApk.value)
            try {
                withTimeout(5 * 60 * 1000L) { agentLoop?.run(text) }
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
            }
        }
    }

    private fun ensureLoop(cfg: AgentConfig) {
        val ctx = ToolContext(appContext = agentApp, workspace = agentApp.workspace, openApk = agentApp.openApk.value)
        if (agentLoop == null || lastConfig != cfg) {
            Logger.i("VM", "AgentLoop: ${cfg.providerId}/${cfg.model}")
            agentLoop = AgentLoop(OpenAIClient(cfg.baseUrl, cfg.apiKey), agentApp.toolRegistry, cfg.model, cfg.temperature, ctx, this, maxRounds = 50)
            lastConfig = cfg
        }
    }

    fun stop() { Logger.i("VM", "停止") }
    fun clearChat() { agentLoop?.reset(); agentLoop = null; _messages.value = emptyList(); Logger.i("VM", "清空") }

    // ── Export ──
    fun exportPatchedApk(onResult: (Boolean, String) -> Unit) {
        if (_isExporting.value) return
        _isExporting.value = true
        Logger.i("VM", "导出开始")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ws = agentApp.workspace
                val original = File(ws, "imported.apk")
                if (!original.exists()) { withContext(Dispatchers.Main) { onResult(false, "未找到原始 APK") }; return@launch }

                val patched = ws.listFiles { f -> f.isDirectory && f.name.startsWith("patched_") }?.sortedByDescending { it.lastModified() }
                val smali = ws.listFiles { f -> f.isDirectory && f.name.startsWith("smali_out_") }?.sortedByDescending { it.lastModified() }

                if (patched.isNullOrEmpty() && smali.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) { onResult(false, "未找到破解产物。请先让 AI 执行签名校验 patch。") }; return@launch
                }

                val unpackDir = File(ws, "_export").apply { if (exists()) deleteRecursively() }
                com.apkagent.apktools.smali.ApkRepackSigner.unpackApk(original, unpackDir)

                patched?.forEach { d -> d.listFiles()?.filter { it.extension == "dex" }?.forEach { it.copyTo(File(unpackDir, it.name), overwrite = true) } }
                smali?.forEach { d -> com.apkagent.apktools.smali.SmaliEngine.assembleSmali(d, File(unpackDir, "classes.dex")) }

                val repacked = File(ws, "_unsigned.apk")
                com.apkagent.apktools.smali.ApkRepackSigner.repack(unpackDir, repacked)

                val dl = File(android.os.Environment.getExternalStorageDirectory(), "APKAgent/build")
                val out = File(dl, "${original.nameWithoutExtension}_patched.apk").apply { parentFile?.mkdirs() }
                val sign = com.apkagent.apktools.smali.ApkRepackSigner.signApk(repacked, out, true, true)
                unpackDir.deleteRecursively(); repacked.delete()

                if (sign.success) {
                    val msg = "导出成功\n${out.absolutePath}\n${out.length()/1024}KB | ${sign.schemes?.joinToString("+") ?: "v1+v2"}"
                    Logger.i("VM", msg); withContext(Dispatchers.Main) { onResult(true, msg) }
                } else { withContext(Dispatchers.Main) { onResult(false, sign.message) } }
            } catch (e: Throwable) {
                Logger.e("VM", "导出失败", e); withContext(Dispatchers.Main) { onResult(false, "导出失败: ${e.message}") }
            } finally { _isExporting.value = false }
        }
    }

    // ── History ──
    private fun historyDir(): File = File(agentApp.workspace, "history").apply { if (!exists()) mkdirs() }

    fun loadHistoryList() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = historyDir().listFiles { f -> f.extension == "json" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(50)
                    ?.mapNotNull { f ->
                        try { json.decodeFromString<HistoryItem>(f.readText()) } catch (_: Throwable) { null }
                    } ?: emptyList()
                _historyList.value = list
            } catch (_: Throwable) {}
        }
    }

    fun loadHistory(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(historyDir(), "$id.json")
                if (!file.exists()) return@launch
                // 简单实现：读取对话文件名标记
                // 完整实现需要序列化 ChatItem 列表
                Logger.i("VM", "加载历史: $id")
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
                val file = File(historyDir(), "${item.id}.json")
                file.writeText(json.encodeToString(item))
                _historyList.update { listOf(item) + it }
                Logger.i("VM", "历史已保存: ${item.messageCount}条消息")
            } catch (e: Throwable) {
                Logger.w("VM", "保存历史失败: ${e.message}")
            }
        }
    }

    fun getLogPath(): String? = Logger.getLogPath()

    // ── Callbacks ──
    override fun onAssistantContentDelta(delta: String) {
        _messages.update { list ->
            val id = currentAssistantId
            if (id != null && list.any { it.id == id && it.role == Role.ASSISTANT })
                list.map { if (it.id == id) it.copy(content = it.content + delta) else it }
            else {
                val newId = UUID.randomUUID().toString()
                currentAssistantId = newId
                list + ChatItem(id = newId, role = Role.ASSISTANT, content = delta, streaming = true)
            }
        }
    }
    override fun onAssistantTurnComplete(content: String, toolCalls: List<PendingToolCall>) {
        _messages.update { list -> val id = currentAssistantId; if (id != null) list.map { if (it.id == id) it.copy(streaming = false) else it } else list }
        currentAssistantId = null
    }
    override fun onToolCallStart(call: PendingToolCall) {
        Logger.i("VM", "[${call.name}] ${call.arguments.take(200)}")
        _messages.update { it + ChatItem(role = Role.TOOL, toolCallId = call.id, toolName = call.name, toolArgs = call.arguments, streaming = true) }
    }
    override suspend fun onConfirmToolCall(call: PendingToolCall): Boolean = true
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
}
