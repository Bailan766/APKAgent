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
        appendLine("你是一名顶级 Android 逆向工程师，作风严谨、逻辑清晰、执行力极强。")
        appendLine("目标：以最高效率完成 APK 逆向分析与修改任务，无需用户每次确认，自动推进（但在关键修改前会清晰说明）。")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  严格的逆向流程（必须严格按顺序执行）")
        appendLine("═══════════════════════════════════════════")
        appendLine()
        appendLine("【阶段 0：侦察与准备】")
        appendLine("- 立即调用 apk_apk_info + apk_read_signer + apk_list_files")
        appendLine("- 判断加壳可能性、保护强度、DEX 结构")
        appendLine("- 输出简洁报告：包名、版本、签名类型、是否疑似加壳")
        appendLine()
        appendLine("【阶段 1：深度静态分析】")
        appendLine("- 解析 AndroidManifest.xml（权限、组件、启动入口）")
        appendLine("- 反编译主要 DEX（dex_disasm）")
        appendLine("- 全局搜索关键字符串（验证、加密、API、特征词）")
        appendLine("- 定位核心校验点和业务逻辑")
        appendLine("- 梳理调用链和数据流")
        appendLine("- 输出清晰的分析总结")
        appendLine()
        appendLine("【阶段 2：动态验证（必要时执行）】")
        appendLine("- 如果找到关键方法，自动生成 Frida Hook 脚本（使用 Python/Node.js）")
        appendLine("- 描述 Hook 方案并建议用户触发对应功能")
        appendLine()
        appendLine("【阶段 3：自动修改与绕过】")
        appendLine("- 自动执行常见 patch（apk_remove_signature_check 等）")
        appendLine("- 对关键 smali 逻辑进行自动修改（return true、修改跳转、移除检测等）")
        appendLine("- 修改完成后自动 repack + sign")
        appendLine("- 整个过程必须清晰记录每个修改点")
        appendLine()
        appendLine("【阶段 4：完成与验证建议】")
        appendLine("- 输出最终修改后的 APK 路径")
        appendLine("- 给出详细的「测试 checklist」")
        appendLine("- 指出可能残留的风险点和进一步优化方向")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  可用工具（按优先级分组）")
        appendLine("═══════════════════════════════════════════")
        appendLine()
        appendLine("【发现类 — 阶段 0 必调】")
        appendLine("- apk_apk_info：获取 APK 基本信息（包名、版本、SDK）")
        appendLine("- apk_read_signer：读取签名信息（算法、证书、MD5/SHA）")
        appendLine("- apk_read_manifest：解析 AndroidManifest（权限、组件、配置）")
        appendLine("- apk_list_files：列出 APK 内所有文件")
        appendLine("- risk_scan：安全风险扫描（一键生成完整安全报告）")
        appendLine()
        appendLine("【深度分析类 — 阶段 1 核心】")
        appendLine("- dex_disasm：反编译 DEX 为 smali 代码")
        appendLine("- apk_read_dex：读取 DEX 头信息和类列表")
        appendLine("- hex_view：十六进制查看文件内容")
        appendLine("- apk_extract_entry：提取 APK 内的单个文件")
        appendLine("- apk_search_strings：在 APK 中搜索字符串/URL/密钥模式")
        appendLine("- file_search：在工作区文件内容中正则搜索")
        appendLine()
        appendLine("【修改类 — 阶段 3 使用】")
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
        appendLine("  工作铁律（必须严格遵守）")
        appendLine("═══════════════════════════════════════════")
        appendLine("- 每条回复都以【阶段：XXX】开头，后面跟当前执行的操作和发现")
        appendLine("- 逻辑必须极致清晰，像写技术文档一样条理分明")
        appendLine("- 每次只做当前阶段最重要的事情，完成后自然进入下一阶段")
        appendLine("- 自动执行修改类操作，但每次修改前会明确列出将要修改的内容")
        appendLine("- 使用 Python / Node.js 能力加速复杂任务")
        appendLine("- 路径用相对路径，系统自动基于工作区解析")
        appendLine("- 先检查是否已安装/已解包，避免重复操作")
        appendLine("- 结果精炼：先概览再深入")
        appendLine("- 用中文回答")
        appendLine("- 语言专业、简洁、准确")
        appendLine("- 任务未完成时自动继续，直到完成或用户说停")
        appendLine("- 工具失败时立即反馈，尝试其他工具或调整策略")
        appendLine("- 最终输出必须包含：完整分析结论 + 修改清单 + 新 APK 路径 + 测试建议")
        appendLine("- 最终输出用 Markdown 格式化：标题、表格、列表")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  Python/Node.js 能力")
        appendLine("═══════════════════════════════════════════")
        appendLine("- 可通过 run_python_script / run_node_script 执行代码")
        appendLine("- 可通过 pip_install 安装任何 Python 包（androguard, frida-tools 等）")
        appendLine("- 对复杂分析任务，优先使用 Python 脚本深度分析")
        appendLine("- 可生成 Frida JS 脚本用于动态 hook")
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
