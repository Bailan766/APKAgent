package com.apkagent.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Agent 循环事件回调（由 UI/ViewModel 实现，回调在 Main 线程安全执行）。
 */
interface AgentCallbacks {
    fun onAssistantContentDelta(delta: String)
    fun onAssistantTurnComplete(content: String, toolCalls: List<PendingToolCall>)
    fun onToolCallStart(call: PendingToolCall)
    suspend fun onConfirmToolCall(call: PendingToolCall): Boolean
    fun onToolCallComplete(call: ExecutedToolCall)
    fun onError(message: String)
    fun onFinished()
}

/**
 * Agent 主循环。全部 I/O 密集操作（网络、文件、smali 编译）在 IO 线程执行。
 * 仅回调通过 Main 线程分发，确保 UI 不卡顿。
 */
class AgentLoop(
    private val client: OpenAIClient,
    private val registry: ToolRegistry,
    private val model: String,
    private val temperature: Double,
    val ctx: ToolContext,
    private val callbacks: AgentCallbacks,
    private val maxRounds: Int = 12
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val messages = mutableListOf<ChatMessage>()
    private var systemInjected = false

    private fun systemPrompt(): String = buildString {
        appendLine("你是 APKAgent，一个运行在 Android 设备上的 APK 逆向分析助手。")
        appendLine("你可以通过调用工具来分析、操作用户导入的 APK 文件，能力类似 MT 管理器。")
        appendLine("工作模式：用户用自然语言提需求，你决定调用哪些工具，工具执行后把结果返回给你，你再继续推理，直到完成任务并给出结论。")
        appendLine()
        appendLine("可用工具：")
        appendLine(registry.describeAll())
        appendLine()
        appendLine("注意事项：")
        appendLine("- 路径参数尽量用相对路径（如 \"smali_classes\"），系统会自动基于工作区解析。")
        appendLine("- 也可用绝对路径。路径越界会被沙箱拒绝。")
        appendLine("- APK 内条目通过 apk:// 协议或 entry_in_apk 参数访问。")
        appendLine("- 敏感操作（修改/删除/重打包）默认允许执行。")
        appendLine("- 解析结果尽量精炼，先列概览再按需深入。")
        appendLine("- 用中文回答。")
    }

    /**
     * 执行一轮对话。整段运行在调用方协程上下文中（应为 Dispatchers.IO）。
     * 网络请求已内置 IO 切换，工具执行在 IO 线程。
     */
    suspend fun run(userText: String) {
        if (!systemInjected) {
            messages.add(ChatMessage(role = "system", content = systemPrompt()))
            systemInjected = true
        }
        messages.add(ChatMessage(role = "user", content = userText))

        try {
            for (round in 0 until maxRounds) {
                val request = ChatRequest(
                    model = model,
                    messages = messages.toList(),
                    stream = true,
                    temperature = temperature,
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
                    return
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

                // 逐个执行工具 — 显式切到 IO 线程执行重度 I/O
                for (call in result.toolCalls) {
                    callbacks.onToolCallStart(call)
                    val tool = registry.get(call.name)
                    val outcome: ToolResult = if (tool == null) {
                        ToolResult.err("未知工具：${call.name}")
                    } else {
                        if (tool.sensitive && !callbacks.onConfirmToolCall(call)) {
                            ToolResult.err("用户拒绝执行该操作。")
                        } else {
                            try {
                                // 工具在 IO 线程执行（smali 编译/ZIP 解析/签名校验等重 I/O）
                                withContext(Dispatchers.IO) {
                                    val args = call.parsedArgs ?: JsonObject(emptyMap())
                                    tool.execute(args, ctx)
                                }
                            } catch (e: Throwable) {
                                ToolResult.err("工具执行异常：${e.message ?: e.javaClass.simpleName}")
                            }
                        }
                    }
                    callbacks.onToolCallComplete(
                        ExecutedToolCall(
                            id = call.id,
                            name = call.name,
                            arguments = call.arguments,
                            result = outcome.content,
                            success = outcome.success,
                            confirmed = tool?.sensitive != true
                        )
                    )
                    messages.add(
                        ChatMessage(
                            role = "tool",
                            content = outcome.content,
                            toolCallId = call.id
                        )
                    )
                }
            }
            callbacks.onError("已达到最大推理轮数（$maxRounds），任务可能未完成。")
            callbacks.onFinished()
        } catch (e: Throwable) {
            callbacks.onError(e.message ?: e.javaClass.simpleName)
            callbacks.onFinished()
        }
    }

    fun reset() {
        messages.clear()
        systemInjected = false
    }
}
