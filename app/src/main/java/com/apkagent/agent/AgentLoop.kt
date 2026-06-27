package com.apkagent.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

interface AgentCallbacks {
    fun onAssistantContentDelta(delta: String)
    fun onAssistantTurnComplete(content: String, toolCalls: List<PendingToolCall>)
    fun onToolCallStart(call: PendingToolCall)
    suspend fun onConfirmToolCall(call: PendingToolCall): Boolean
    fun onToolCallComplete(call: ExecutedToolCall)
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

    private fun systemPrompt(): String = buildString {
        appendLine("你是 APKAgent，Android APK 逆向分析助手。")
        appendLine("用工具分析/操作 APK 文件，能力类似 MT 管理器。")
        appendLine()
        appendLine("可用工具：")
        appendLine(registry.describeAll())
        appendLine()
        appendLine("注意事项：")
        appendLine("- 路径尽量用相对路径，系统自动基于工作区解析")
        appendLine("- 所有操作默认允许执行")
        appendLine("- 结果精炼，先概览再深入")
        appendLine("- 用中文回答")
        appendLine("- 任务未完成时自动继续，直到完成或用户说停")
    }

    suspend fun run(userText: String): Boolean {
        val needContinue = runInternal(userText)
        // 如果需要继续（达到轮数上限），自动追加 continue 命令
        if (needContinue) {
            callbacks.onError("达到单轮上限($maxRounds)，自动继续...")
            runInternal("继续完成剩余任务，不要遗漏")
        }
        return true
    }

    /** 返回 true 表示需要继续 */
    private suspend fun runInternal(userText: String): Boolean {
        if (!systemInjected) {
            messages.add(ChatMessage(role = "system", content = systemPrompt()))
            systemInjected = true
        }
        messages.add(ChatMessage(role = "user", content = userText))

        try {
            for (round in 0 until maxRounds) {
                val request = ChatRequest(
                    model = model, messages = messages.toList(),
                    stream = true, temperature = temperature,
                    tools = registry.toToolDefs().ifEmpty { null }
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

                for (call in result.toolCalls) {
                    callbacks.onToolCallStart(call)
                    val tool = registry.get(call.name)
                    val outcome: ToolResult = if (tool == null) {
                        ToolResult.err("未知工具：${call.name}")
                    } else {
                        if (tool.sensitive && !callbacks.onConfirmToolCall(call)) {
                            ToolResult.err("拒绝执行")
                        } else {
                            try {
                                withContext(Dispatchers.IO) {
                                    val args = call.parsedArgs ?: JsonObject(emptyMap())
                                    tool.execute(args, ctx)
                                }
                            } catch (e: Throwable) {
                                ToolResult.err("工具异常：${e.message ?: e.javaClass.simpleName}")
                            }
                        }
                    }
                    callbacks.onToolCallComplete(ExecutedToolCall(
                        id = call.id, name = call.name, arguments = call.arguments,
                        result = outcome.content, success = outcome.success,
                        confirmed = tool?.sensitive != true
                    ))
                    messages.add(ChatMessage(role = "tool", content = outcome.content, toolCallId = call.id))
                }
            }
            return true // 达到上限，需要继续
        } catch (e: Throwable) {
            callbacks.onError(e.message ?: e.javaClass.simpleName)
            callbacks.onFinished()
            return false
        }
    }

    fun reset() { messages.clear(); systemInjected = false }
}
