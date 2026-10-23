package com.apkagent.agent

import com.apkagent.i18n.L

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import com.apkagent.i18n.AgentPrompts

interface AgentCallbacks {
    fun onAssistantContentDelta(delta: String)
    fun onAssistantTurnComplete(content: String, toolCalls: List<PendingToolCall>)
    fun onToolCallStart(call: PendingToolCall)
    suspend fun onConfirmToolCall(call: PendingToolCall): Boolean
    fun onToolCallComplete(call: ExecutedToolCall)
    fun onPhaseChange(phase: ReversePhase)
    fun onError(message: String)
    fun onFinished()
}

class AgentLoop(
    private val client: OpenAIClient,
    private val registry: ToolRegistry,
    private val model: String,
    private val temperature: Double,
    val ctx: ToolContext,
    private val callbacks: AgentCallbacks,
    private val maxRounds: Int = 50
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val messages = mutableListOf<ChatMessage>()
    private var systemInjected = false
    private val usedTools = mutableSetOf<String>()

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private fun systemPrompt(): String = AgentPrompts.systemPrompt()

    suspend fun run(userText: String): Boolean {
        _state.value = AgentState(phase = ReversePhase.PREPARE, isRunning = true)
        callbacks.onPhaseChange(ReversePhase.PREPARE)
        // 初始化审计日志
        try { AuditLogger.init(ctx.workspace ?: File("/sdcard/Download/APKAgent")) } catch (_: Exception) {}
        AuditLogger.newSession()

        val needContinue = runInternal(userText)

        if (needContinue) {
            _state.value = _state.value.copy(phase = _state.value.phase.next())
            callbacks.onPhaseChange(_state.value.phase)
            runInternal(AgentPrompts.continueRemainder())
        }

        _state.value = _state.value.copy(isRunning = false, phase = ReversePhase.DONE)
        callbacks.onPhaseChange(ReversePhase.DONE)
        return true
    }

    private suspend fun runInternal(userText: String): Boolean {
        if (!systemInjected) {
            messages.add(ChatMessage(role = "system", content = systemPrompt()))
            systemInjected = true
        }
        messages.add(ChatMessage(role = "user", content = userText))

        try {
            for (round in 0 until maxRounds) {
                // 获取当前阶段可用工具
                val phaseTools = registry.getToolsForPhase(_state.value.phase)
                val toolDefs = if (phaseTools.isNotEmpty()) {
                    registry.toToolDefs(phaseTools)
                } else {
                    registry.toToolDefs().ifEmpty { null }
                }

                val request = ChatRequest(
                    model = model, messages = messages.toList(),
                    stream = true, temperature = temperature,
                    tools = toolDefs
                )

                val streamed = StringBuilder()
                val result = client.streamChat(request) { delta ->
                    streamed.append(delta)
                    callbacks.onAssistantContentDelta(delta)
                }
                callbacks.onAssistantTurnComplete(result.content, result.toolCalls)

                if (result.toolCalls.isEmpty()) {
                    messages.add(ChatMessage(role = "assistant", content = result.content))
                    callbacks.onFinished()
                    return false
                }

                messages.add(ChatMessage(
                    role = "assistant", content = result.content.ifBlank { null },
                    toolCalls = result.toolCalls.map {
                        ToolCallRef(id = it.id, function = ToolCallFunction(name = it.name, arguments = it.arguments))
                    }
                ))

                // 记录使用过的工具
                result.toolCalls.forEach { usedTools.add(it.name) }

                // 并行执行
                val executedList = executeToolCallsConcurrently(result.toolCalls)
                for (exec in executedList) {
                    callbacks.onToolCallComplete(exec)
                    messages.add(ChatMessage(role = "tool", content = exec.result, toolCallId = exec.id))
                }

                // 更新阶段状态
                updatePhase()

                if (shouldStop(result, executedList)) {
                    callbacks.onFinished()
                    return false
                }
            }
            return true
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

    private fun shouldStop(response: StreamResult, results: List<ExecutedToolCall>): Boolean {
        if (results.all { !it.success && it.result.contains(L("拒绝", "Denied")) }) return true
        val content = response.content
        if (content.isNotEmpty()) {
            val finishKeywords = listOf(L("分析完成", "Analysis complete."), L("最终结论", "Final conclusions"), L("总结如下", "Summary"), L("已完成", "Completed"), L("全部完成", "All completed"), L("任务完成", "Mission accomplished."), L("测试 checklist", "Test checklist"), L("测试建议", "Test Recommendations"))
            if (finishKeywords.any { content.contains(it) }) return true
        }
        return false
    }

    private suspend fun executeToolCallsConcurrently(
        toolCalls: List<PendingToolCall>
    ): List<ExecutedToolCall> = coroutineScope {
        toolCalls.map { call ->
            async(Dispatchers.IO) {
                callbacks.onToolCallStart(call)
                val outcome = executeSingleTool(call)
                ExecutedToolCall(
                    id = call.id, name = call.name, arguments = call.arguments,
                    result = outcome.content, success = outcome.success,
                    confirmed = true
                )
            }
        }.awaitAll()
    }

    private suspend fun executeSingleTool(call: PendingToolCall): ToolResult {
        val tool = registry.get(call.name)
            ?: return ToolResult.err("未知工具：${call.name}")
        if (tool.sensitive && !callbacks.onConfirmToolCall(call)) {
            return ToolResult.err(L("拒绝执行", "Deny execution"))
        }
        // DANGER 级工具：执行前创建快照
        var snapshot: SnapshotManager.Snapshot? = null
        if (ToolRiskLevel.infer(call.name) == ToolRiskLevel.DANGER) {
            try {
                val ws = ctx.workspace
                if (ws != null && ws.exists()) {
                    val files = ws.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()
                    snapshot = SnapshotManager.create(call.name, files, ws)
                }
            } catch (e: Exception) {
                // 快照失败不阻止执行
            }
        }
        return try {
            val args = call.parsedArgs ?: JsonObject(emptyMap())
            tool.execute(args, ctx)
        } catch (e: Throwable) {
            // 如果有快照且执行失败，提示可回滚
            val rollbackHint = if (snapshot != null) " (可使用快照 ${snapshot!!.id} 回滚)" else ""
            ToolResult.err("工具异常：${e.message ?: e.javaClass.simpleName}$rollbackHint")
        }
    }

    fun reset() {
        messages.clear()
        systemInjected = false
        usedTools.clear()
        _state.value = AgentState()
    }
}
