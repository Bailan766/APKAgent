package com.apkagent.agent

import com.apkagent.store.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 流式调用结果：累积的文本 + 工具调用列表 + 结束原因 + 思考过程。
 */
data class StreamResult(
    val content: String,
    val toolCalls: List<PendingToolCall>,
    val finishReason: String?,
    val reasoningContent: String? = null
)

/** 一次流式过程中累积中的工具调用（按 index 聚合增量）。 */
private data class AccToolCall(
    var id: String? = null,
    var name: String? = null,
    val args: StringBuilder = StringBuilder()
)

/**
 * OpenAI 兼容 Chat Completions 客户端。
 * 支持 SSE 流式输出、DeepSeek-R1 / 推理模型 reasoning_content 独立思考流、
 * 智能端点规范化、API Key 净化与跨平台自动探测。
 */
class OpenAIClient(
    var baseUrl: String,
    var apiKey: String,
    private val circuitBreaker: CircuitBreaker = CircuitBreaker()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeCall: Call? = null

    /** 立即切断当前正在进行的网络调用与流 */
    fun cancelCurrentCall() {
        try {
            activeCall?.cancel()
        } catch (_: Throwable) {}
        activeCall = null
    }

    fun endpoint(): String = buildChatEndpoint(baseUrl)

    private fun modelsEndpoint(): String = buildModelsEndpoint(baseUrl)

    private fun Request.Builder.applyAuthHeaders(targetUrl: String, rawKey: String): Request.Builder {
        val cleanKey = sanitizeApiKey(rawKey)
        if (cleanKey.isNotEmpty()) {
            header("Authorization", "Bearer $cleanKey")
            if (targetUrl.contains("anthropic.com", ignoreCase = true)) {
                header("x-api-key", cleanKey)
                header("anthropic-version", "2023-06-01")
            }
        }
        if (targetUrl.contains("openrouter.ai", ignoreCase = true)) {
            header("HTTP-Referer", "https://github.com/Bailan766/APKAgent")
            header("X-Title", "APKAgent")
        }
        return this
    }

    private fun parseModelsJson(respBody: String, targetUrl: String): List<String> {
        val jsonObj = runCatching { json.parseToJsonElement(respBody) as? JsonObject }.getOrNull()
            ?: return emptyList()
        val array = (jsonObj["data"] as? JsonArray) ?: (jsonObj["models"] as? JsonArray) ?: return emptyList()
        val isGemini = targetUrl.contains("generativelanguage.googleapis.com", ignoreCase = true)
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val rawId = (obj["id"] ?: obj["name"])?.toString()?.trim('"')?.trim()
            if (rawId.isNullOrEmpty()) null
            else if (isGemini) rawId.removePrefix("models/")
            else rawId
        }.distinct().sorted()
    }

    /**
     * 当提供商不支持 GET /models 端点（如返回 404/405）时，通过极简非流式对话请求验证 Key 有效性。
     */
    private fun verifyKeyViaMinimalChat(testModel: String): Boolean {
        val chatUrl = endpoint()
        if (chatUrl.isEmpty() || testModel.isBlank()) return false
        val payload = buildJsonObject {
            put("model", testModel)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "ping")
                })
            })
            put("max_tokens", 1)
            put("stream", false)
        }.toString()

        val req = Request.Builder()
            .url(chatUrl)
            .applyAuthHeaders(chatUrl, apiKey)
            .header("Accept", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                true
            } else if (resp.code == 401 || resp.code == 403) {
                val errBody = resp.body?.string().orEmpty()
                val msg = extractErrorMessage(resp.code, errBody, chatUrl)
                throw NonRetryableApiException(resp.code, msg)
            } else {
                // 如果返回 400 (如模型名需要调整) 但非 401/403，说明鉴权本身已通过
                val errBody = resp.body?.string().orEmpty()
                val lower = errBody.lowercase()
                if (lower.contains("invalid api key") || lower.contains("api key not valid") || lower.contains("unauthorized") || lower.contains("authentication")) {
                    throw NonRetryableApiException(resp.code, extractErrorMessage(resp.code, errBody, chatUrl))
                }
                true
            }
        }
    }

    private fun extractErrorMessage(code: Int, respBody: String, url: String): String {
        val apiDetail = try {
            json.decodeFromString(ApiErrorBody.serializer(), respBody).error?.message
        } catch (_: Throwable) { null }
        val baseMsg = apiDetail?.takeIf { it.isNotBlank() } ?: "HTTP $code: ${respBody.take(200)}"
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        return if (code == 401 || code == 403 || baseMsg.contains("key", ignoreCase = true) || baseMsg.contains("auth", ignoreCase = true)) {
            "鉴权失败 ($host): $baseMsg — 请确认 API Key 与当前所选提供商/Base URL 匹配"
        } else {
            "$baseMsg ($host)"
        }
    }

    /**
     * 获取可用模型列表并验证 API Key。
     * 若服务端不支持 /models 端点 (404/405)，则自动回退到预设模型并通过轻量 ping 校验 Key。
     */
    suspend fun fetchAvailableModels(
        fallbackModels: List<String> = emptyList(),
        testModel: String = ""
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val cleanKey = sanitizeApiKey(apiKey)
            apiKey = cleanKey
            val modelsUrl = modelsEndpoint()
            if (modelsUrl.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("请先填写有效的 API Base URL"))
            }

            val models = circuitBreaker.execute<List<String>> {
                val req = Request.Builder()
                    .url(modelsUrl)
                    .applyAuthHeaders(modelsUrl, cleanKey)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                val call = client.newCall(req)
                activeCall = call
                val response = try {
                    call.execute()
                } finally {
                    if (activeCall == call) activeCall = null
                }

                val respBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    // 部分平台（如智谱 GLM / 部分中转站）未实现 /models 接口（返回 404/405）
                    val probeModel = testModel.ifBlank { fallbackModels.firstOrNull().orEmpty() }
                    if ((response.code == 404 || response.code == 405 || response.code == 501) && probeModel.isNotBlank()) {
                        if (verifyKeyViaMinimalChat(probeModel)) {
                            return@execute fallbackModels.ifEmpty { listOf(probeModel) }
                        }
                    }
                    val errorMsg = extractErrorMessage(response.code, respBody, modelsUrl)
                    if (response.code in 400..499 && response.code != 429) {
                        throw NonRetryableApiException(response.code, errorMsg)
                    }
                    throw IllegalStateException(errorMsg)
                }

                val parsed = parseModelsJson(respBody, modelsUrl)
                parsed.ifEmpty {
                    fallbackModels.ifEmpty { listOfNotNull(testModel.takeIf { it.isNotBlank() }) }
                }
            }
            Result.success(models)
        } catch (e: CircuitOpenException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 发起流式对话。
     * @param onReasoningDelta 推理思考过程增量回调（独立通道）
     * @param onContentDelta 最终文本增量回调
     */
    suspend fun streamChat(
        request: ChatRequest,
        onReasoningDelta: ((String) -> Unit)? = null,
        onContentDelta: (String) -> Unit
    ): StreamResult = withContext(Dispatchers.IO) {
        apiKey = sanitizeApiKey(apiKey)
        val chatUrl = endpoint()
        if (chatUrl.isEmpty()) {
            throw NonRetryableApiException(400, "API Base URL 为空，请在设置页配置有效的接口地址")
        }

        circuitBreaker.retryWithBackoff(maxRetries = 3) {
            val body = json.encodeToString(ChatRequest.serializer(), request)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val req = Request.Builder()
                .url(chatUrl)
                .applyAuthHeaders(chatUrl, apiKey)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .post(body)
                .build()

            val call = client.newCall(req)
            activeCall = call
            val response = try {
                call.execute()
            } catch (e: Throwable) {
                if (activeCall == call) activeCall = null
                throw e
            }

            val respBody = response.body
                ?: throw IllegalStateException("空响应体 (HTTP ${response.code})")

            if (!response.isSuccessful) {
                val errText = try { respBody.string() } finally { runCatching { respBody.close() } }
                if (activeCall == call) activeCall = null
                val msg = extractErrorMessage(response.code, errText, chatUrl)
                if (response.code in 400..499 && response.code != 429) {
                    throw NonRetryableApiException(response.code, msg)
                }
                throw IllegalStateException(msg)
            }

            val content = StringBuilder()
            val reasoning = StringBuilder()
            val acc = mutableMapOf<Int, AccToolCall>()
            var finishReason: String? = null
            val source = respBody.source()

            try {
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val data = when {
                        line.startsWith("data:") -> line.removePrefix("data:").trim()
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

                    // 1. 原生隔离 reasoning_content
                    delta.reasoningContent?.let {
                        if (it.isNotEmpty()) {
                            reasoning.append(it)
                            onReasoningDelta?.invoke(it)
                        }
                    }

                    // 2. 正常回答 content
                    delta.content?.let {
                        if (it.isNotEmpty()) {
                            content.append(it)
                            onContentDelta(it)
                        }
                    }

                    // 3. 聚合工具调用参数
                    delta.toolCalls?.forEach { tc ->
                        val a = acc.getOrPut(tc.index) { AccToolCall() }
                        tc.id?.let { a.id = it }
                        tc.function?.name?.let { a.name = it }
                        tc.function?.arguments?.let { a.args.append(it) }
                    }
                }
            } finally {
                try { respBody.close() } catch (_: Throwable) {}
                if (activeCall == call) activeCall = null
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

            StreamResult(
                content = content.toString(),
                toolCalls = pending,
                finishReason = finishReason,
                reasoningContent = reasoning.toString().ifBlank { null }
            )
        }
    }

    companion object {
        private val probeClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        fun sanitizeApiKey(raw: String): String {
            var s = raw.trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .removeSurrounding("`")
                .removeSurrounding("“", "”")
                .removeSurrounding("‘", "’")
                .trim()
            s = s.replace(Regex("^(?i)(bearer|apikey|api_key|key)\\s*[:=]?\\s*"), "")
            return s.filter { it in '\u0021'..'\u007e' }
        }

        fun normalizeBaseUrl(rawUrl: String): String {
            var base = rawUrl.trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .trim()
            if (base.isEmpty()) return ""
            if (!base.startsWith("http://", ignoreCase = true) && !base.startsWith("https://", ignoreCase = true)) {
                val isLocal = base.startsWith("localhost") || base.startsWith("127.") ||
                    base.startsWith("10.") || base.startsWith("192.168.")
                base = (if (isLocal) "http://" else "https://") + base
            }
            base = base.trimEnd('/')
            when {
                base.endsWith("/chat/completions", ignoreCase = true) ->
                    base = base.dropLast("/chat/completions".length).trimEnd('/')
                base.endsWith("/models", ignoreCase = true) ->
                    base = base.dropLast("/models".length).trimEnd('/')
            }
            return base
        }

        fun buildChatEndpoint(rawUrl: String): String {
            val base = normalizeBaseUrl(rawUrl)
            if (base.isEmpty()) return ""
            if (base.contains("generativelanguage.googleapis.com", ignoreCase = true)) {
                return when {
                    base.endsWith("/openai", ignoreCase = true) -> "$base/chat/completions"
                    base.endsWith("/v1beta", ignoreCase = true) -> "$base/openai/chat/completions"
                    else -> "$base/v1beta/openai/chat/completions"
                }
            }
            val hasVersionSuffix = Regex(".*/(v\\d+(?:beta)?|openai)$", RegexOption.IGNORE_CASE).matches(base)
            return if (hasVersionSuffix) "$base/chat/completions" else "$base/v1/chat/completions"
        }

        fun buildModelsEndpoint(rawUrl: String): String {
            val base = normalizeBaseUrl(rawUrl)
            if (base.isEmpty()) return ""
            if (base.contains("generativelanguage.googleapis.com", ignoreCase = true)) {
                return when {
                    base.endsWith("/openai", ignoreCase = true) -> "$base/models"
                    base.endsWith("/v1beta", ignoreCase = true) -> "$base/openai/models"
                    else -> "$base/v1beta/openai/models"
                }
            }
            val hasVersionSuffix = Regex(".*/(v\\d+(?:beta)?|openai)$", RegexOption.IGNORE_CASE).matches(base)
            return if (hasVersionSuffix) "$base/models" else "$base/v1/models"
        }

        /**
         * 当用户粘贴了 API Key 但未切换对应的预设提供商导致 401 时，
         * 自动探测该 Key 真正归属的预设提供商并返回（提供商 + 可用模型列表）。
         */
        suspend fun probeWorkingProvider(rawKey: String): Pair<AiProvider, List<String>>? = withContext(Dispatchers.IO) {
            val cleanKey = sanitizeApiKey(rawKey)
            if (cleanKey.length < 10) return@withContext null

            // 1. 先检查具有唯一特征的前缀
            AiProvider.detectFromKeyPrefix(cleanKey)?.let { detected ->
                val testClient = OpenAIClient(detected.baseUrl, cleanKey)
                val models = testClient.fetchAvailableModels(detected.models, detected.defaultModel).getOrNull()
                if (models != null) {
                    return@withContext detected to models.ifEmpty { detected.models }
                }
            }

            // 2. 对通用 Key 并发探测需要严格 Bearer 鉴权的预设平台（排除公开 /models 的 OpenRouter 与本地 Ollama）
            val candidateIds = listOf("deepseek", "siliconflow", "qwen", "moonshot", "openai", "tokenplan", "zhipu")
            val candidates = candidateIds.map { AiProvider.byId(it) }

            coroutineScope {
                candidates.map { provider ->
                    async {
                        runCatching {
                            val url = buildModelsEndpoint(provider.baseUrl)
                            val req = Request.Builder()
                                .url(url)
                                .header("Authorization", "Bearer $cleanKey")
                                .header("Accept", "application/json")
                                .get()
                                .build()
                            probeClient.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    val body = resp.body?.string().orEmpty()
                                    val client = OpenAIClient(provider.baseUrl, cleanKey)
                                    val models = client.parseModelsJson(body, url).ifEmpty { provider.models }
                                    provider to models
                                } else null
                            }
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull().firstOrNull()
            }
        }
    }
}
