package com.apkagent.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * OpenAI 兼容 Chat Completions API 的数据模型。
 * 支持流式输出与 function calling（tool_calls）。
 */

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val name: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallRef>? = null
)

@Serializable
data class ToolCallRef(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction
)

@Serializable
data class ToolCallFunction(
    val name: String,
    val arguments: String
)

/** 请求体 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val temperature: Double = 0.6,
    val tools: List<ToolDef>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null
)

@Serializable
data class ToolDef(
    val type: String = "function",
    val function: ToolFunctionDef
)

@Serializable
data class ToolFunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonElement
)

/** —— 流式响应增量 —— */
@Serializable
data class StreamChunk(
    val id: String? = null,
    val choices: List<StreamChoice> = emptyList()
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: Delta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeltaToolCall>? = null
)

@Serializable
data class DeltaToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: DeltaToolFunction? = null
)

@Serializable
data class DeltaToolFunction(
    val name: String? = null,
    val arguments: String? = null
)

/** 非流式错误体（容错解析） */
@Serializable
data class ApiErrorBody(
    val error: ApiErrorDetail? = null
)

@Serializable
data class ApiErrorDetail(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)

/** 工具执行结果 */
data class ToolResult(
    val success: Boolean,
    val content: String,
    val data: JsonObject? = null
) {
    companion object {
        fun ok(text: String, data: JsonObject? = null) = ToolResult(true, text, data)
        fun err(text: String) = ToolResult(false, text, null)
    }
}
