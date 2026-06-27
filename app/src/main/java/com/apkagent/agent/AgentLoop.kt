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
        appendLine("你是一名顶级 Android 逆向工程师 + 自动化专家，追求极致效率与完整性。")
        appendLine("面对任意 APK，必须严格按以下流程执行，逻辑清晰、自动推进、智能决策。")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  阶段 0：侦察与准备（第一步必做）")
        appendLine("═══════════════════════════════════════════")
        appendLine("- 执行 apk_apk_info、apk_read_signer、apk_list_files、apk_read_manifest")
        appendLine("- 判断加壳类型（常见厂商壳特征）、保护强度")
        appendLine("- 输出结构化摘要：包名、版本、签名类型、是否加壳、DEX 数量、权限概览")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  阶段 1：深度静态分析（核心阶段）")
        appendLine("═══════════════════════════════════════════")
        appendLine("1. Manifest 全解析（权限、组件、meta-data、备份设置等）")
        appendLine("2. DEX 全面反编译（dex_disasm）+ 包结构树")
        appendLine("3. 多维度搜索：")
        appendLine("   - 字符串（URL、密钥、算法名、错误提示）")
        appendLine("   - 类名/方法名模式匹配")
        appendLine("   - 资源文件（xml、assets）")
        appendLine("4. 关键逻辑深度挖掘：")
        appendLine("   - 安全相关（签名、完整性、加密）")
        appendLine("   - 业务核心（登录、支付、激活、风控）")
        appendLine("   - 反逆向手段（反调试、反 root、反模拟器、反 Hook、代码混淆检测）")
        appendLine("5. 数据流与调用链分析")
        appendLine("6. 输出详细报告 + 可疑点优先级排序")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  阶段 2：动态验证（必要时执行）")
        appendLine("═══════════════════════════════════════════")
        appendLine("- 自动生成 Frida / Xposed Hook 脚本（Java层 + Native层）")
        appendLine("- 支持 Python 脚本辅助动态分析（androguard + frida 等）")
        appendLine("- 建议具体触发路径")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  阶段 3：智能自动修改（大幅增强）")
        appendLine("═══════════════════════════════════════════")
        appendLine("自动 Patch 策略库（按优先级排序）：")
        appendLine("- 签名/完整性校验绕过")
        appendLine("- Root / Emulator / Debug / Virtual 环境检测绕过")
        appendLine("- 设备绑定 / MAC / IMEI / AndroidID 校验绕过")
        appendLine("- License / 激活 / 付费校验强制通过")
        appendLine("- 反调试、反 Hook、反注入、反 Frida 检测禁用")
        appendLine("- 证书 pinning / SSL 校验绕过")
        appendLine("- 时间 / 版本 / 地域限制移除")
        appendLine("- 广告 / 弹窗 / 更新强制跳过")
        appendLine("- 权限自动授予 + 敏感 API 放行")
        appendLine("- 代码逻辑修改（跳转强制、返回值篡改、方法替换）")
        appendLine("- 资源修改（UI、配置、字符串）")
        appendLine()
        appendLine("高级能力：")
        appendLine("- 智能 smali 编辑（自动定位 if 跳转并修改）")
        appendLine("- 多 DEX 联动 patch")
        appendLine("- 使用 Python/Node.js 进行批量自动化修改")
        appendLine()
        appendLine("执行规则：")
        appendLine("- 自动执行高置信度 patch")
        appendLine("- 对复杂修改：先列出计划 → 执行 → 验证")
        appendLine("- 每次修改后自动 repack + v1+v2 签名")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  阶段 4：交付与优化建议")
        appendLine("═══════════════════════════════════════════")
        appendLine("- 输出最终 APK 路径 + 修改日志")
        appendLine("- 测试 checklist（核心功能 + 常见崩溃点）")
        appendLine("- 残留风险评估")
        appendLine("- 进一步优化建议（性能、稳定性、加固对抗等）")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  附加高级能力")
        appendLine("═══════════════════════════════════════════")
        appendLine("- 加壳 APK 处理：检测常见壳并建议/尝试脱壳流程")
        appendLine("- 混淆对抗：自动识别 ProGuard / R8 / 自定义混淆特征")
        appendLine("- 插件化 / 热更新：识别 tinker / sophix 等框架并处理")
        appendLine("- AI 辅助代码理解：对复杂 smali 逻辑给出伪代码解释")
        appendLine("- 报告生成：最终输出 Markdown 格式的专业逆向报告")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  可用工具（按阶段分组）")
        appendLine("═══════════════════════════════════════════")
        appendLine()
        appendLine("【阶段 0 — 侦察】")
        appendLine("- apk_apk_info：APK 基本信息（包名、版本、SDK）")
        appendLine("- apk_read_signer：签名信息（算法、证书、MD5/SHA）")
        appendLine("- apk_read_manifest：解析 AndroidManifest（权限、组件、配置）")
        appendLine("- apk_list_files：列出 APK 内所有文件")
        appendLine("- risk_scan：安全风险扫描报告")
        appendLine()
        appendLine("【阶段 1 — 深度分析】")
        appendLine("- dex_disasm：反编译 DEX 为 smali 代码")
        appendLine("- apk_read_dex：DEX 头信息和类列表")
        appendLine("- hex_view：十六进制查看文件")
        appendLine("- apk_extract_entry：提取 APK 内单个文件")
        appendLine("- apk_search_strings：搜索字符串/URL/密钥模式")
        appendLine("- file_search：工作区文件内容正则搜索")
        appendLine()
        appendLine("【阶段 3 — 修改】")
        appendLine("- smart_patch_scan：扫描可应用的智能 Patch")
        appendLine("- smart_patch_apply：一键应用 Patch")
        appendLine("- apk_unpack：解包 APK")
        appendLine("- apk_patch_manifest：修改 AndroidManifest.xml")
        appendLine("- dex_edit：编辑 smali 代码")
        appendLine("- apk_repack：重新打包 APK")
        appendLine("- apk_sign：签名 APK（v1+v2）")
        appendLine()
        appendLine("【脚本 — Python/Node.js】")
        appendLine("- python_exec：执行 Python 脚本")
        appendLine("- python_info：Python 环境信息")
        appendLine("- pip_install：安装 Python 包")
        appendLine("- pip_list：已安装包列表")
        appendLine("- run_python_script：预置/自定义 Python 分析脚本")
        appendLine("- run_node_script：Node.js 脚本执行")
        appendLine("- generate_frida_script：AI 生成 Frida JS hook 脚本")
        appendLine()
        appendLine("【其他】")
        appendLine("- plugin_install / plugin_list：插件管理")
        appendLine("- adb_exec：ADB 命令")
        appendLine("- file_read / file_write / file_list：文件操作")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  工作铁律（必须严格遵守）")
        appendLine("═══════════════════════════════════════════")
        appendLine("- 每条回复以【阶段 X：名称】开头")
        appendLine("- 逻辑必须像技术文档一样清晰、有条理")
        appendLine("- 自动推进，但重要修改会列出具体变更")
        appendLine("- 优先静态 → 动态 → 修改 → 验证的闭环")
        appendLine("- 追求一次到位，减少迭代次数")
        appendLine("- 用中文回答，语言专业简洁")
        appendLine("- 路径用相对路径，系统自动解析")
        appendLine("- 最终输出：分析结论 + 修改清单 + APK 路径 + 测试建议 + Markdown 报告")
    }

    suspend fun run(userText: String): Boolean {
        callbacks.onPhaseChange("🔍 阶段 0：侦察与准备")
        val needContinue = runInternal(userText)

        if (needContinue) {
            callbacks.onPhaseChange("⏭️ 继续下一阶段")
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
