package com.apkagent.agent

import com.apkagent.tools.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 工具注册表。集中管理所有 Agent 可调用的工具。
 */
class ToolRegistry {
    private val tools: MutableMap<String, Tool> = linkedMapOf()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun get(name: String): Tool? = tools[name]

    fun all(): List<Tool> = tools.values.toList()

    /** 转为 OpenAI tools 参数格式 */
    fun toToolDefs(): List<ToolDef> = tools.values.map { t ->
        ToolDef(
            function = ToolFunctionDef(
                name = t.name,
                description = t.description,
                parameters = t.parameters
            )
        )
    }

    /** 为指定工具列表生成 API tool definitions */
    fun toToolDefs(toolList: List<Tool>): List<ToolDef> = toolList.map { t ->
        ToolDef(
            function = ToolFunctionDef(
                name = t.name,
                description = t.description,
                parameters = t.parameters
            )
        )
    }

    /**
     * 按阶段过滤可用工具
     * 阶段 0 (PREPARE): 只暴露侦察工具
     * 阶段 1 (STATIC_ANALYSIS): 侦察 + 分析工具
     * 阶段 2 (DYNAMIC): 分析 + 脚本工具
     * 阶段 3+ (MODIFICATION/REPACK/DONE): 所有工具
     */
    fun getToolsForPhase(phase: ReversePhase): List<Tool> {
        val prepareTools = setOf(
            "apk_apk_info", "apk_read_signer", "apk_read_manifest", "apk_list_files", "risk_scan"
        )
        val analysisTools = prepareTools + setOf(
            "dex_disasm", "apk_read_dex", "hex_view", "apk_extract_entry",
            "apk_search_strings", "file_search", "smart_search", "analyze_obfuscation"
        )
        val dynamicTools = analysisTools + setOf(
            "run_python_script", "run_node_script", "generate_frida_script", "generate_hook",
            "python_exec", "python_info", "pip_install", "pip_list"
        )
        val allToolNames = tools.keys.toSet()

        val allowed = when (phase) {
            ReversePhase.PREPARE -> prepareTools
            ReversePhase.STATIC_ANALYSIS -> analysisTools
            ReversePhase.DYNAMIC -> dynamicTools
            ReversePhase.MODIFICATION -> allToolNames
            ReversePhase.REPACK -> allToolNames
            ReversePhase.DONE -> allToolNames
        }
        return tools.values.filter { it.name in allowed }
    }

    /** 工具名 -> 简要描述 */
    fun describeAll(): String =
        tools.values.joinToString("\n") { "- ${it.name}: ${it.description}" }
}

// —— JSON Schema 构建小工具 ——

fun schemaObject(properties: Map<String, JsonObject>, required: List<String> = emptyList()): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        properties.forEach { (k, v) -> put(k, v) }
    })
    if (required.isNotEmpty()) {
        put("required", buildJsonArray { required.forEach { add(it) } })
    }
}

fun strProp(desc: String, enum: List<String>? = null): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", desc)
    enum?.let { put("enum", buildJsonArray { it.forEach { e -> add(e) } }) }
}

fun intProp(desc: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", desc)
}

fun boolProp(desc: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", desc)
}
