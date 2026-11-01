package com.apkagent.agent

import com.apkagent.apktools.Sandbox
import com.apkagent.apktools.strNonBlank
import com.apkagent.i18n.L
import com.apkagent.i18n.AgentPrompts
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

interface AgentCallbacks {
    fun onAssistantReasoningDelta(delta: String) {}
    fun onAssistantContentDelta(delta: String)
    fun onAssistantTurnComplete(content: String, toolCalls: List<PendingToolCall>, reasoning: String? = null)
    fun onToolCallStart(call: PendingToolCall)
    suspend fun onConfirmToolCall(call: PendingToolCall): Boolean
    fun onToolCallComplete(call: ExecutedToolCall)
    fun onPhaseChange(phase: ReversePhase)
    fun onError(message: String)
    fun onFinished()
}

/**
 * 现代化自主逆向智能体循环 (Modern Agent Loop)。
 * 1. 废除硬编码阶段锁：全量工具开放给大模型自主决策与调度；
 * 2. 移除关键字误杀逻辑：彻底避免“已完成”等思考字样导致意外早退；
 * 3. 事务化工具调度：读操作安全并发，写/重打包操作串行执行，杜绝文件竞争；
 * 4. 原生支持 DeepSeek-R1 独立思考流与硬取消；
 * 5. 智能轻量快照：仅备份被修改的目标文本/XML/Smali 文件，避免每次复制百兆 APK。
 */
class AgentLoop(
    private val client: OpenAIClient,
    private val registry: ToolRegistry,
    private val model: String,
    private val temperature: Double,
    val ctx: ToolContext,
    private val callbacks: AgentCallbacks,
    private val maxRounds: Int = 50,
    private val systemExtra: String = ""
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val messages = mutableListOf<ChatMessage>()
    private var systemInjected = false
    private val usedTools = mutableSetOf<String>()

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    companion object {
        private const val MAX_TOOL_OUTPUT_CHARS = 24_000
    }

    private fun systemPrompt(): String {
        val base = AgentPrompts.systemPrompt()
        return if (systemExtra.isNotBlank()) "$base\n\n## 用户附加指令\n$systemExtra" else base
    }

    /** 立即取消当前 Agent 的执行与网络连接 */
    fun cancel() {
        client.cancelCurrentCall()
        _state.value = _state.value.copy(isRunning = false)
    }

    suspend fun run(userText: String, initialSystemContext: String? = null): Boolean {
        _state.value = AgentState(phase = ReversePhase.PREPARE, isRunning = true)
        callbacks.onPhaseChange(ReversePhase.PREPARE)

        try {
            AuditLogger.init(ctx.workspace)
            AuditLogger.newSession()
        } catch (_: Exception) {}

        val needContinue = runInternal(userText, initialSystemContext)

        if (needContinue) {
            _state.value = _state.value.copy(phase = _state.value.phase.next())
            callbacks.onPhaseChange(_state.value.phase)
            runInternal(AgentPrompts.continueRemainder(), null)
        }

        _state.value = _state.value.copy(isRunning = false, phase = ReversePhase.DONE)
        callbacks.onPhaseChange(ReversePhase.DONE)
        return true
    }

    private suspend fun runInternal(userText: String, initialSystemContext: String?): Boolean {
        // 1. 初始置顶上下文（系统提示词 + 项目特征）
        if (!systemInjected) {
            val fullSystemPrompt = if (!initialSystemContext.isNullOrBlank()) {
                "${systemPrompt()}\n\n$initialSystemContext"
            } else {
                systemPrompt()
            }
            messages.add(ChatMessage(role = "system", content = fullSystemPrompt))
            systemInjected = true
        }

        // 用户指令增量（若上一轮因异常中断残留了末尾 user 消息，则直接替换避免连续 user 报错）
        if (messages.lastOrNull()?.role == "user") {
            messages[messages.lastIndex] = ChatMessage(role = "user", content = userText)
        } else {
            messages.add(ChatMessage(role = "user", content = userText))
        }

        try {
            for (round in 0 until maxRounds) {
                currentCoroutineContext().ensureActive()
                AuditLogger.nextRound()

                val toolDefs = registry.toToolDefs().ifEmpty { null }

                val request = ChatRequest(
                    model = model,
                    messages = messages.toList(),
                    stream = true,
                    temperature = temperature,
                    tools = toolDefs
                )

                val result = client.streamChat(
                    request = request,
                    onReasoningDelta = { delta -> callbacks.onAssistantReasoningDelta(delta) },
                    onContentDelta = { delta -> callbacks.onAssistantContentDelta(delta) }
                )

                callbacks.onAssistantTurnComplete(result.content, result.toolCalls, result.reasoningContent)

                // 无工具调用：本次回合大模型直接回答完成
                if (result.toolCalls.isEmpty()) {
                    messages.add(ChatMessage(role = "assistant", content = result.content))
                    callbacks.onFinished()
                    return false
                }

                messages.add(
                    ChatMessage(
                        role = "assistant",
                        content = result.content.ifBlank { null },
                        toolCalls = result.toolCalls.map {
                            ToolCallRef(id = it.id, function = ToolCallFunction(name = it.name, arguments = it.arguments))
                        }
                    )
                )

                result.toolCalls.forEach { usedTools.add(it.name) }
                updatePhase()

                // 智能调度执行工具：读操作并发，写操作串行安全调度
                val executedList = executeToolsSafely(result.toolCalls)
                for (exec in executedList) {
                    callbacks.onToolCallComplete(exec)
                    val cappedResult = if (exec.result.length > MAX_TOOL_OUTPUT_CHARS) {
                        exec.result.take(MAX_TOOL_OUTPUT_CHARS) + "\n…(输出过长，已截断至 $MAX_TOOL_OUTPUT_CHARS 字符)"
                    } else {
                        exec.result
                    }
                    messages.add(ChatMessage(role = "tool", content = cappedResult, toolCallId = exec.id))
                }

                updatePhase()

                // 用户在权限对话框全部拒绝时停止
                if (executedList.isNotEmpty() && executedList.all { !it.success && it.result.contains(L("拒绝", "Deny")) }) {
                    callbacks.onFinished()
                    return false
                }
            }
            return true
        } catch (e: CancellationException) {
            _state.value = _state.value.copy(isRunning = false)
            callbacks.onFinished()
            throw e
        } catch (e: Throwable) {
            _state.value = _state.value.copy(error = e.message, isRunning = false)
            callbacks.onError(e.message ?: e.javaClass.simpleName)
            callbacks.onFinished()
            return false
        }
    }

    private fun updatePhase() {
        val detected = ReversePhase.fromToolsUsed(usedTools)
        if (detected != _state.value.phase) {
            _state.value = _state.value.copy(phase = detected, toolsExecuted = usedTools.size)
            callbacks.onPhaseChange(detected)
        }
    }

    /**
     * 安全调度工具：
     * 修改类写操作按序串行执行，避免解包/修补/重打包文件冲突；读取/分析类工具并发加速。
     */
    private suspend fun executeToolsSafely(
        toolCalls: List<PendingToolCall>
    ): List<ExecutedToolCall> = coroutineScope {
        val writeToolNames = setOf(
            "apk_unpack", "apk_repack", "apk_sign", "apk_enhanced_sign", "apk_zipalign",
            "apk_patch_manifest", "apk_remove_signature_check",
            "dex_edit", "dex_assemble", "smart_patch", "smart_patch_apply", "auto_patch",
            "file_write", "ext_file_write", "create_script", "run_script"
        )

        val hasWriteTool = toolCalls.any { it.name in writeToolNames }

        if (hasWriteTool || toolCalls.size == 1) {
            val results = mutableListOf<ExecutedToolCall>()
            for (call in toolCalls) {
                ensureActive()
                callbacks.onToolCallStart(call)
                val outcome = executeSingleTool(call)
                results.add(
                    ExecutedToolCall(
                        id = call.id,
                        name = call.name,
                        arguments = call.arguments,
                        result = outcome.content,
                        success = outcome.success,
                        confirmed = true
                    )
                )
            }
            results
        } else {
            toolCalls.map { call ->
                async(Dispatchers.IO) {
                    callbacks.onToolCallStart(call)
                    val outcome = executeSingleTool(call)
                    ExecutedToolCall(
                        id = call.id,
                        name = call.name,
                        arguments = call.arguments,
                        result = outcome.content,
                        success = outcome.success,
                        confirmed = true
                    )
                }
            }.awaitAll()
        }
    }

    private suspend fun executeSingleTool(call: PendingToolCall): ToolResult {
        val tool = registry.get(call.name)
            ?: return ToolResult.err("未知工具：${call.name}")

        if (tool.sensitive && !callbacks.onConfirmToolCall(call)) {
            return ToolResult.err(L("拒绝执行", "Deny execution"))
        }

        val riskLevel = ToolRiskLevel.infer(call.name)
        val args = call.parsedArgs ?: JsonObject(emptyMap())

        // DANGER 级工具：仅备份即将修改的目标小文件（排除 .apk/.dex/.so 等大二进制文件，避免性能卡顿）
        var snapshot: SnapshotManager.Snapshot? = null
        if (riskLevel == ToolRiskLevel.DANGER) {
            try {
                val ws = ctx.workspace
                if (ws.exists()) {
                    val candidates = mutableListOf<File>()
                    listOf("path", "smali_path", "manifest_path", "script_path").forEach { key ->
                        args.strNonBlank(key)?.let { p ->
                            val f = Sandbox.resolve(File(p), ws)
                            if (f.exists() && f.isFile && f.length() < 2_000_000) candidates.add(f)
                        }
                    }
                    if (candidates.isEmpty()) {
                        ws.walkTopDown()
                            .maxDepth(3)
                            .onEnter { it.name != ".snapshots" && it.name != "audit" && it.name != "projects" }
                            .filter {
                                it.isFile && it.length() < 1_000_000 &&
                                    it.extension.lowercase() in setOf("xml", "smali", "py", "js", "sh", "json")
                            }
                            .take(25)
                            .forEach { candidates.add(it) }
                    }
                    snapshot = SnapshotManager.create(call.name, candidates.distinct(), ws)
                }
            } catch (_: Exception) {}
        }

        val startMs = System.currentTimeMillis()
        return try {
            val res = tool.execute(args, ctx)
            val durationMs = System.currentTimeMillis() - startMs
            runCatching {
                AuditLogger.log(call.name, call.arguments, res.content, res.success, durationMs, riskLevel)
            }
            res
        } catch (e: Throwable) {
            val durationMs = System.currentTimeMillis() - startMs
            val rollbackHint = if (snapshot != null) " (可使用快照 ${snapshot.id} 回滚)" else ""
            val errMsg = "工具异常：${e.message ?: e.javaClass.simpleName}$rollbackHint"
            runCatching {
                AuditLogger.log(call.name, call.arguments, errMsg, false, durationMs, riskLevel)
            }
            ToolResult.err(errMsg)
        }
    }

    fun reset() {
        messages.clear()
        systemInjected = false
        usedTools.clear()
        _state.value = AgentState()
    }
}
