package com.apkagent.agent

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

    /** 工具名 -> 简要描述（供系统提示词列举） */
    fun describeAll(): String =
        tools.values.joinToString("\n") { "- ${it.name}: ${it.description}" }
}

// —— JSON Schema 构建小工具，便于在 Tool 实现里声明参数 ——
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
