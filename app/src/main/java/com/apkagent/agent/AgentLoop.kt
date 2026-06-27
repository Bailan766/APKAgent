package com.apkagent.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        appendLine("你是 APKAgent，Android APK 逆向分析工具 v3.4。")
        appendLine("专业 APK 分析 · 反编译 · 修改 · 重签名。用工具分析/操作 APK 文件。")
        appendLine()
        appendLine("可用工具：")
        appendLine(registry.describeAll())
        appendLine()
        appendLine("核心能力：")
        appendLine("- APK 信息解析、AndroidManifest 分析、DEX/签名读取")
        appendLine("- smali 反编译/回编译、签名校验扫描+patch")
        appendLine("- 智能 Patch 库：一键移除签名校验/root检测/emulator检测/调试限制")
        appendLine("- 安全风险扫描：危险权限/导出组件/WebView风险/硬编码密钥/证书pinning")
        appendLine("- 可自行安装 Python pip 包（pip_install）和分析插件（plugin_install）")
        appendLine("- Python 环境可执行任意分析脚本（python_exec）")
        appendLine("- 内置终端可执行 shell 命令")
        appendLine()
        appendLine("注意事项：")
        appendLine("- 路径尽量用相对路径，系统自动基于工作区解析")
        appendLine("- 先检查插件/pip包是否已安装，避免重复")
        appendLine("- 结果精炼，先概览再深入")
        appendLine("- 用中文回答")
        appendLine("- 缺少工具时主动 pip_install 安装")
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

                // ── 多线程优化：同一轮 AI 发出的工具调用互不依赖，并行执行 ──
                val executedList = executeToolCallsConcurrently(result.toolCalls)
                for (exec in executedList) {
                    callbacks.onToolCallComplete(exec)
                    messages.add(ChatMessage(role = "tool", content = exec.result, toolCallId = exec.id))
                }
            }
            return true // 达到上限，需要继续
        } catch (e: Throwable) {
            callbacks.onError(e.message ?: e.javaClass.simpleName)
            callbacks.onFinished()
            return false
        }
    }

    /**
     * 并行执行同一轮中的多个工具调用。
     * AI 在同一次 API 响应中发出的所有 tool_calls 之间无依赖关系
     * （模型在发出前无法看到任何工具结果），因此可以安全并行。
     */
    private suspend fun executeToolCallsConcurrently(
        toolCalls: List<PendingToolCall>
    ): List<ExecutedToolCall> = coroutineScope {
        toolCalls.map { call ->
            async(Dispatchers.IO) {
                // 1. 通知 UI 工具开始
                callbacks.onToolCallStart(call)

                val outcome = executeSingleTool(call)

                ExecutedToolCall(
                    id = call.id, name = call.name, arguments = call.arguments,
                    result = outcome.content, success = outcome.success,
                    confirmed = true
                )
            }
        }.awaitAll()  // 保持原始顺序 → 消息顺序与 AI 发出的一致
    }

    private suspend fun executeSingleTool(call: PendingToolCall): ToolResult {
        val tool = registry.get(call.name)
        if (tool == null) {
            return ToolResult.err("未知工具：${call.name}")
        }
        if (tool.sensitive && !callbacks.onConfirmToolCall(call)) {
            return ToolResult.err("拒绝执行")
        }
        return try {
            val args = call.parsedArgs ?: JsonObject(emptyMap())
            tool.execute(args, ctx)
        } catch (e: Throwable) {
            ToolResult.err("工具异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun reset() { messages.clear(); systemInjected = false }
}
