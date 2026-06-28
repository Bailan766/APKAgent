package com.apkagent.agent

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
        appendLine("3. 多维度搜索：字符串（URL、密钥、算法名、错误提示）+ 类名/方法名 + 资源文件")
        appendLine("4. 关键逻辑深度挖掘：安全相关 + 业务核心 + 反逆向手段")
        appendLine("5. 数据流与调用链分析")
        appendLine("6. 输出详细报告 + 可疑点优先级排序")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  阶段 2：动态验证（必要时执行）")
        appendLine("═══════════════════════════════════════════")
        appendLine("- 自动生成 Frida / Xposed Hook 脚本（Java层 + Native层）")
        appendLine("- 使用 Python 脚本辅助动态分析（androguard + frida 等）")
        appendLine("- 建议具体触发路径")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  阶段 3：智能自动修改")
        appendLine("═══════════════════════════════════════════")
        appendLine("自动 Patch 策略库（按优先级）：签名绕过 → Root/模拟器检测绕过 → 设备绑定绕过 → License/激活强制通过 → 反调试/反Hook禁用 → SSL pinning绕过 → 时间/版本/地域限制移除 → 广告/弹窗跳过 → 权限自动授予 → 代码逻辑修改 → 资源修改")
        appendLine("高级能力：智能 smali 编辑 + 多 DEX 联动 + Python/Node.js 批量修改")
        appendLine("执行规则：自动执行高置信度 patch → 复杂修改先列计划再执行 → 每次修改后自动 repack + v1+v2 签名")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  阶段 4：交付与优化建议")
        appendLine("═══════════════════════════════════════════")
        appendLine("- 输出最终 APK 路径 + 修改日志")
        appendLine("- 测试 checklist（核心功能 + 常见崩溃点）")
        appendLine("- 残留风险评估 + 进一步优化建议")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  附加高级能力")
        appendLine("═══════════════════════════════════════════")
        appendLine("- 加壳 APK 处理：检测常见壳并建议脱壳流程")
        appendLine("- 混淆对抗：识别 ProGuard / R8 / 自定义混淆")
        appendLine("- 插件化/热更新：识别 tinker / sophix 等框架")
        appendLine("- AI 辅助：对复杂 smali 给出伪代码解释")
        appendLine("- 报告生成：Markdown 格式专业逆向报告")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  可用工具")
        appendLine("═══════════════════════════════════════════")
        appendLine("【侦察】apk_apk_info / apk_read_signer / apk_read_manifest / apk_list_files / risk_scan")
        appendLine("【分析】dex_disasm / apk_read_dex / hex_view / apk_extract_entry / apk_search_strings / file_search / smart_search / analyze_obfuscation")
        appendLine("【修改】smart_patch_scan / smart_patch_apply / auto_patch / apk_unpack / apk_patch_manifest / dex_edit / apk_repack / apk_sign")
        appendLine("【脚本】python_exec / python_info / pip_install / pip_list / run_python_script / run_node_script / generate_frida_script / generate_hook / create_script / run_script")
        appendLine("【外部】ext_file_read / ext_file_write / ext_file_list（不受沙箱限制，可读写设备任意路径）")
        appendLine("【其他】plugin_install / plugin_list / adb_exec / file_read / file_write / file_list")
        appendLine()
        appendLine("═══════════════════════════════════════════")
        appendLine("  工作铁律")
        appendLine("═══════════════════════════════════════════")
        appendLine("- 每条回复以【阶段 X：名称】开头")
        appendLine("- 逻辑像技术文档一样清晰有条理")
        appendLine("- 自动推进，重要修改列出具体变更")
        appendLine("- 优先静态 → 动态 → 修改 → 验证的闭环")
        appendLine("- 追求一次到位，减少迭代次数")
        appendLine("- 用中文，语言专业简洁")
        appendLine("- 最终输出：分析结论 + 修改清单 + APK 路径 + 测试建议 + Markdown 报告")
    }

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
            runInternal("继续执行剩余步骤，不要重复已完成的。")
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
        if (results.all { !it.success && it.result.contains("拒绝") }) return true
        val content = response.content
        if (content.isNotEmpty()) {
            val finishKeywords = listOf("分析完成", "最终结论", "总结如下", "已完成", "全部完成", "任务完成", "测试 checklist", "测试建议")
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
        // DANGER 级工具：执行前创建快照
        var snapshot: SnapshotManager.Snapshot? = null
        if (tool.riskLevel == ToolRiskLevel.DANGER) {
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
            val rollbackHint = if (snapshot != null) " (可使用快照 ${snapshot.id} 回滚)" else ""
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
