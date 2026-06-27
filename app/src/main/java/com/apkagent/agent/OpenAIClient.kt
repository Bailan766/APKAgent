package com.apkagent.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 流式调用结果：累积的文本 + 工具调用列表 + 结束原因。
 */
data class StreamResult(
    val content: String,
    val toolCalls: List<PendingToolCall>,
    val finishReason: String?
)

/** 一次流式过程中累积中的工具调用（按 index 聚合增量）。 */
private data class AccToolCall(
    var id: String? = null,
    var name: String? = null,
    val args: StringBuilder = StringBuilder()
)

/**
 * OpenAI 兼容 Chat Completions 客户端。
 * 支持 SSE 流式输出与 function calling 增量拼接。
 */
class OpenAIClient(
    var baseUrl: String,
    var apiKey: String
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun endpoint(): String {
        var base = baseUrl.trim().trimEnd('/')
        if (!base.endsWith("/v1")) {
            // 兼容用户填到 /v1 或只填根域名
            if (!base.endsWith("/chat/completions")) base = "$base/v1"
        }
        return "$base/chat/completions"
    }

    /**
     * 发起流式对话。
     * @param onContentDelta 文本增量回调（用于 UI 实时显示）
     */
    suspend fun streamChat(
        request: ChatRequest,
        onContentDelta: (String) -> Unit
    ): StreamResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ChatRequest.serializer(), request)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = Request.Builder()
            .url(endpoint())
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .post(body)
            .build()

        val response = client.newCall(req).execute()
        val respBody = response.body
            ?: throw IllegalStateException("空响应体 (HTTP ${response.code})")

        if (!response.isSuccessful) {
            val errText = respBody.string()
            val msg = try {
                json.decodeFromString(ApiErrorBody.serializer(), errText).error?.message
            } catch (_: Throwable) { null } ?: "HTTP ${response.code}: ${errText.take(300)}"
            throw IllegalStateException(msg)
        }

        val content = StringBuilder()
        val acc = mutableMapOf<Int, AccToolCall>()
        var finishReason: String? = null
        val source = respBody.source()

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.isBlank()) continue
            val data = when {
                line.startsWith("data:") -> line.removePrefix("data:").trim()
                line.startsWith("data: ") -> line.removePrefix("data: ").trim()
                else -> continue
            }
            if (data == "[DONE]") break
            if (data.isEmpty()) continue

            val chunk = try {
                json.decodeFromString(StreamChunk.serializer(), data)
            } catch (_: Throwable) { continue }

            val choice = chunk.choices.firstOrNull() ?: continue
            choice.finishReason?.let { finishReason = it }
            val delta = choice.delta ?: continue

            delta.content?.let {
                if (it.isNotEmpty()) {
                    content.append(it)
                    onContentDelta(it)
                }
            }

            delta.toolCalls?.forEach { tc ->
                val a = acc.getOrPut(tc.index) { AccToolCall() }
                tc.id?.let { a.id = it }
                tc.function?.name?.let { a.name = it }
                tc.function?.arguments?.let { a.args.append(it) }
            }
        }

        val pending = acc.toSortedMap().map { (_, a) ->
            val argsStr = a.args.toString()
            val parsed = try {
                if (argsStr.isBlank()) JsonObject(emptyMap())
                else json.parseToJsonElement(argsStr) as? JsonObject
            } catch (_: Throwable) { null }
            PendingToolCall(
                id = a.id ?: "call_${System.currentTimeMillis()}",
                name = a.name ?: "unknown",
                arguments = argsStr,
                parsedArgs = parsed
            )
        }

        StreamResult(content.toString(), pending, finishReason)
    }
}
