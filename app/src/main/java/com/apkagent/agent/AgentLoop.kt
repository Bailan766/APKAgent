package com.apkagent.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    fun onPhaseChange(phase: String)
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
        appendLine("你是 APKAgent，专业的 Android APK 逆向分析工具助手。")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  可用工具（按优先级分组）")
        appendLine("═══════════════════════════════════════════")
        appendLine()
        appendLine("【发现类 — 先调用这些了解 APK】")
        appendLine("- apk_apk_info：获取 APK 基本信息（包名、版本、SDK）")
        appendLine("- apk_read_signer：读取签名信息（算法、证书、MD5/SHA）")
        appendLine("- apk_read_manifest：解析 AndroidManifest（权限、组件、配置）")
        appendLine("- apk_list_files：列出 APK 内所有文件")
        appendLine("- risk_scan：安全风险扫描（一键生成完整安全报告）")
        appendLine()
        appendLine("【深度分析类】")
        appendLine("- dex_disasm：反编译 DEX 为 smali 代码")
        appendLine("- apk_read_dex：读取 DEX 头信息和类列表")
        appendLine("- hex_view：十六进制查看文件内容")
        appendLine("- apk_extract_entry：提取 APK 内的单个文件")
        appendLine("- apk_search_strings：在 APK 中搜索字符串/URL/密钥模式")
        appendLine("- file_search：在工作区文件内容中正则搜索")
        appendLine()
        appendLine("【修改类 — 需要用户确认】")
        appendLine("- smart_patch_scan：扫描可应用的智能 Patch")
        appendLine("- smart_patch_apply：一键应用 Patch（签名校验/root检测/模拟器检测/调试限制/SSL pinning/VIP绕过）")
        appendLine("- apk_unpack：解包 APK")
        appendLine("- apk_patch_manifest：修改 AndroidManifest.xml")
        appendLine("- dex_edit：编辑 smali 代码")
        appendLine("- apk_repack：重新打包 APK")
        appendLine("- apk_sign：签名 APK")
        appendLine()
        appendLine("【脚本执行类 — Python/Node.js】")
        appendLine("- python_exec：执行 Python 脚本")
        appendLine("- python_info：查看 Python 环境信息")
        appendLine("- pip_install：安装 Python 包（自动跳过已安装）")
        appendLine("- pip_list：列出已安装的 pip 包")
        appendLine("- run_python_script：执行预置或自定义 Python 分析脚本")
        appendLine("- run_node_script：执行 Node.js 脚本")
        appendLine("- generate_frida_script：AI 生成 Frida JS hook 脚本")
        appendLine()
        appendLine("【插件/其他】")
        appendLine("- plugin_install：安装分析插件")
        appendLine("- plugin_list：列出可用插件")
        appendLine("- adb_exec：执行 ADB 命令")
        appendLine("- file_read / file_write / file_list：工作区文件操作")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  工作流程（严格遵守）")
        appendLine("═══════════════════════════════════════════")
        appendLine()
        appendLine("第一阶段：分析规划")
        appendLine("收到任务后，你必须先：")
        appendLine("1. 理解用户需求，明确目标")
        appendLine("2. 调用发现类工具全面了解 APK（apk_apk_info + apk_read_manifest + apk_read_signer）")
        appendLine("3. 列出执行计划，用编号标明步骤顺序")
        appendLine()
        appendLine("第二阶段：逐步执行")
        appendLine("按计划逐步执行：")
        appendLine("1. 每次只调用 1-3 个必要工具，避免一次性调用过多")
        appendLine("2. 检查工具返回结果是否成功")
        appendLine("3. 根据结果决定下一步（成功→继续，失败→调整方案）")
        appendLine("4. 所有修改类操作需等待确认")
        appendLine("5. 全部完成后给出最终总结")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  关键规则")
        appendLine("═══════════════════════════════════════════")
        appendLine("- 路径用相对路径，系统自动基于工作区解析")
        appendLine("- 先检查是否已安装/已解包，避免重复操作")
        appendLine("- 结果精炼：先概览再深入，不要一次性输出全部细节")
        appendLine("- 用中文回答")
        appendLine("- 语言专业、简洁、准确，不要过多解释工具调用过程")
        appendLine("- 任务未完成时自动继续，直到完成或用户说停")
        appendLine("- 工具失败时立即反馈，尝试其他工具或调整策略")
        appendLine("- 最终输出用 Markdown 格式化：标题、表格、列表")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  Python/Node.js 能力")
        appendLine("═══════════════════════════════════════════")
        appendLine("- 可通过 run_python_script / run_node_script 执行代码")
        appendLine("- 可通过 pip_install 安装任何 Python 包（androguard, frida-tools 等）")
        appendLine("- 对复杂分析任务，优先使用 Python 脚本深度分析")
        appendLine("- 可生成 Frida JS 脚本用于动态 hook")
        appendLine("- 脚本执行前告知用户并等待确认")
    }

    suspend fun run(userText: String): Boolean {
        callbacks.onPhaseChange("🔍 分析阶段")
        val needContinue = runInternal(userText)

        if (needContinue) {
            callbacks.onPhaseChange("⏭️ 继续执行")
            runInternal("继续执行剩余步骤，不要重复已完成的。")
        }
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

                // 并行执行同一轮的工具调用
                val executedList = executeToolCallsConcurrently(result.toolCalls)
                for (exec in executedList) {
                    callbacks.onToolCallComplete(exec)
                    messages.add(ChatMessage(role = "tool", content = exec.result, toolCallId = exec.id))
                }

                // 判断是否停止
                if (shouldStop(result, executedList)) {
                    callbacks.onFinished()
                    return false
                }
            }
            return true
        } catch (e: Throwable) {
            callbacks.onError(e.message ?: e.javaClass.simpleName)
            callbacks.onFinished()
            return false
        }
    }

    private fun shouldStop(response: StreamResult, results: List<ExecutedToolCall>): Boolean {
        if (results.all { !it.success && it.result.contains("拒绝") }) return true
        val content = response.content
        if (content.isNotEmpty()) {
            val finishKeywords = listOf("分析完成", "最终结论", "总结如下", "已完成", "全部完成", "任务完成")
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
