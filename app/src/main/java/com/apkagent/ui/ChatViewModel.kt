package com.apkagent.ui

import android.app.Application
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
import com.apkagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    private val agentApp get() = getApplication<ApkAgentApp>()

    private val _messages = MutableStateFlow<List<ChatItem>>(emptyList())
    val messages: StateFlow<List<ChatItem>> = _messages.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _openApkName = MutableStateFlow<String?>(null)
    val openApkName: StateFlow<String?> = _openApkName.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private var agentLoop: AgentLoop? = null
    private var lastConfig: AgentConfig? = null
    private var currentAssistantId: String? = null

    val config: StateFlow<AgentConfig> get() = agentApp.settingsStore.config

    fun setOpenApk(file: File?) {
        agentApp.setOpenApk(file)
        _openApkName.value = file?.name
        Logger.i("VM", "📁 APK: ${file?.name} size=${file?.length() ?: 0}")
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

        val t0 = System.currentTimeMillis()
        Logger.i("VM", "━━━ 开始 ━━━ 用户: ${text.take(100)}")
        Logger.heartbeat("VM")

        viewModelScope.launch(Dispatchers.IO) {
            ensureLoop(cfg)
            agentLoop?.ctx?.updateOpenApk(agentApp.openApk.value)
            try {
                withTimeout(5 * 60 * 1000L) {
                    agentLoop?.run(text)
                }
                val elapsed = System.currentTimeMillis() - t0
                Logger.i("VM", "━━━ 完成 ━━━ 耗时: ${elapsed}ms")
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Logger.e("VM", "⏰ 超时（5分钟）")
                _messages.update { it + ChatItem(role = Role.ERROR, content = "⚠ 运行超时（5分钟），已自动中断") }
            } catch (e: Throwable) {
                Logger.e("VM", "异常", e)
                _messages.update { it + ChatItem(role = Role.ERROR, content = "运行异常：${e.message}") }
            } finally {
                _isRunning.value = false
                Logger.heartbeat("VM")
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
            Logger.i("VM", "🔧 AgentLoop 创建: model=${cfg.model} url=${cfg.baseUrl}")
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

    fun stop() { Logger.i("VM", "⏹ 用户停止") }

    /** 一键导出破解后的 APK：自动搜索工作区 → 合并 → 重打包 → 签名 → 保存到 Downloads */
    fun exportPatchedApk(onResult: (Boolean, String) -> Unit) {
        if (_isExporting.value) return
        _isExporting.value = true
        Logger.i("VM", "📦 开始导出...")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ws = agentApp.workspace
                val originalApk = File(ws, "imported.apk")
                if (!originalApk.exists()) {
                    withContext(Dispatchers.Main) { onResult(false, "未找到原始 APK，请先导入") }
                    return@launch
                }

                // 1. 搜索 patched DEX 目录
                val patchedDirs = ws.listFiles { f -> f.isDirectory && f.name.startsWith("patched_") }
                    ?.sortedByDescending { it.lastModified() }
                val smaliDirs = ws.listFiles { f -> f.isDirectory && f.name.startsWith("smali_out_") }
                    ?.sortedByDescending { it.lastModified() }

                if (patchedDirs.isNullOrEmpty() && smaliDirs.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        onResult(false, "未找到破解产物。\n请先让 AI 执行签名校验扫描和 patch。\n例如：\"扫描签名校验并 patch\"")
                    }
                    return@launch
                }

                Logger.i("VM", "📦 发现: patched=${patchedDirs?.map{it.name}}, smali=${smaliDirs?.map{it.name}}")

                // 2. 解包原始 APK 到临时目录
                val unpackDir = File(ws, "_export_unpacked")
                if (unpackDir.exists()) unpackDir.deleteRecursively()
                val unpack = com.apkagent.apktools.smali.ApkRepackSigner.unpackApk(originalApk, unpackDir)
                if (!unpack.success) {
                    withContext(Dispatchers.Main) { onResult(false, "解包失败: ${unpack.message}") }
                    return@launch
                }
                Logger.i("VM", "📦 解包完成: ${unpack.fileCount} 个文件")

                // 3. 替换 DEX 文件
                if (!patchedDirs.isNullOrEmpty()) {
                    for (dir in patchedDirs) {
                        dir.listFiles()?.filter { it.extension == "dex" }?.forEach { patchedDex ->
                            val targetName = patchedDex.name
                            val target = File(unpackDir, targetName)
                            patchedDex.copyTo(target, overwrite = true)
                            Logger.i("VM", "📦 替换 DEX: $targetName → ${target.length()} bytes")
                        }
                    }
                }

                // 若有 smali 目录且没有 patched DEX，尝试回编译
                if ((patchedDirs.isNullOrEmpty() || patchedDirs.flatMap { it.listFiles()?.toList() ?: emptyList() }.isEmpty()) &&
                    !smaliDirs.isNullOrEmpty()) {
                    for (dir in smaliDirs) {
                        val dexName = dir.name.removePrefix("smali_out_") + ".dex"
                        if (dexName == ".dex") continue
                        val outDex = File(unpackDir, if (dexName.startsWith(".")) "classes$dexName" else dexName)
                        val r = com.apkagent.apktools.smali.SmaliEngine.assembleSmali(dir, outDex)
                        if (r.success) {
                            Logger.i("VM", "📦 smali→DEX: $dexName")
                        }
                    }
                }

                // 4. 重打包
                val repacked = File(ws, "_export_unsigned.apk")
                val repack = com.apkagent.apktools.smali.ApkRepackSigner.repack(unpackDir, repacked)
                if (!repack.success) {
                    withContext(Dispatchers.Main) { onResult(false, "重打包失败: ${repack.message}") }
                    return@launch
                }
                Logger.i("VM", "📦 重打包: ${repacked.length()} bytes")

                // 5. 签名
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val name = originalApk.nameWithoutExtension + "_patched.apk"
                val finalApk = File(downloadsDir, name)
                finalApk.parentFile?.mkdirs()

                val sign = com.apkagent.apktools.smali.ApkRepackSigner.signApk(repacked, finalApk, v1Enabled = true, v2Enabled = true)
                if (!sign.success) {
                    withContext(Dispatchers.Main) { onResult(false, "签名失败: ${sign.message}") }
                    return@launch
                }

                // 6. 清理临时文件
                unpackDir.deleteRecursively()
                repacked.delete()

                val schemes = sign.schemes?.joinToString("+") ?: "v1+v2"
                val msg = "✅ 导出成功！\n${finalApk.absolutePath}\n${finalApk.length() / 1024}KB | 签名: $schemes"
                Logger.i("VM", "📦 $msg")
                withContext(Dispatchers.Main) { onResult(true, msg) }
            } catch (e: Throwable) {
                Logger.e("VM", "导出失败", e)
                withContext(Dispatchers.Main) { onResult(false, "导出失败: ${e.message}") }
            } finally {
                _isExporting.value = false
            }
        }
    }

    fun clearChat() {
        agentLoop?.reset()
        agentLoop = null
        _messages.value = emptyList()
        Logger.i("VM", "🧹 清空对话")
    }

    // ── AgentCallbacks ──

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
            if (id != null) list.map { if (it.id == id) it.copy(streaming = false) else it } else list
        }
        currentAssistantId = null
        Logger.i("VM", "🤖 AI: ${content.take(200)}${if (content.length > 200) "..." else ""}")
        if (toolCalls.isNotEmpty()) {
            Logger.i("VM", "🔧 本轮工具调用: ${toolCalls.size}个 → ${toolCalls.joinToString { it.name }}")
        }
    }

    override fun onToolCallStart(call: PendingToolCall) {
        Logger.i("VM", "🔨 开始 ${call.name} args=${call.arguments.take(300)}")
        _messages.update { it + ChatItem(role = Role.TOOL, toolName = call.name, toolArgs = call.arguments, streaming = true) }
    }

    override suspend fun onConfirmToolCall(call: PendingToolCall): Boolean {
        Logger.i("VM", "🟢 自动授权: ${call.name}")
        return true
    }

    override fun onToolCallComplete(call: ExecutedToolCall) {
        val status = if (call.success) "✅" else "❌"
        val brief = call.result.take(300).replace('\n', ' ')
        Logger.i("VM", "🔨 完成 ${call.name} $status (${call.result.length}字符) $brief")
        if (!call.success) {
            Logger.w("VM", "失败详情: ${call.name} | ${call.result.take(500)}")
        }
        _messages.update { list ->
            val idx = list.indexOfLast { it.role == Role.TOOL && it.streaming && it.toolName == call.name }
            if (idx >= 0) {
                list.toMutableList().also { it[idx] = it[idx].copy(toolResult = call.result, toolSuccess = call.success, streaming = false) }
            } else list
        }
    }

    override fun onError(message: String) {
        Logger.e("VM", "Agent 错误: $message")
        _messages.update { it + ChatItem(role = Role.ERROR, content = "⚠ $message") }
    }

    override fun onFinished() {
        _isRunning.value = false
        Logger.i("VM", "🏁 Agent 任务结束")
    }

    fun getLogPath(): String? = Logger.getLogPath()
}
