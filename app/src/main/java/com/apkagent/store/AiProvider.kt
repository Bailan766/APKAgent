package com.apkagent.store

/**
 * AI 提供商预设：内置常见 API，用户只需填 Key 即可。
 */
data class AiProvider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val models: List<String>
) {
    companion object {
        val PRESETS = listOf(
            AiProvider(
                id = "deepseek",
                label = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
                defaultModel = "deepseek-chat",
                models = listOf("deepseek-chat", "deepseek-reasoner")
            ),
            AiProvider(
                id = "siliconflow",
                label = "硅基流动 (SiliconFlow)",
                baseUrl = "https://api.siliconflow.cn/v1",
                defaultModel = "deepseek-ai/DeepSeek-V3",
                models = listOf(
                    "deepseek-ai/DeepSeek-V3",
                    "deepseek-ai/DeepSeek-R1",
                    "Qwen/Qwen3-235B-A22B",
                    "Qwen/Qwen2.5-Coder-32B-Instruct"
                )
            ),
            AiProvider(
                id = "openrouter",
                label = "OpenRouter",
                baseUrl = "https://openrouter.ai/api/v1",
                defaultModel = "deepseek/deepseek-r1",
                models = listOf(
                    "deepseek/deepseek-r1",
                    "deepseek/deepseek-chat",
                    "anthropic/claude-3.7-sonnet",
                    "anthropic/claude-3.5-sonnet",
                    "google/gemini-2.5-flash",
                    "meta-llama/llama-3.3-70b-instruct"
                )
            ),
            AiProvider(
                id = "openai",
                label = "OpenAI GPT",
                baseUrl = "https://api.openai.com/v1",
                defaultModel = "gpt-4o-mini",
                models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1", "gpt-4.1-mini", "o3-mini")
            ),
            AiProvider(
                id = "gemini",
                label = "Google Gemini",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
                defaultModel = "gemini-2.5-flash",
                models = listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash")
            ),
            AiProvider(
                id = "claude",
                label = "Anthropic Claude",
                baseUrl = "https://api.anthropic.com/v1",
                defaultModel = "claude-sonnet-4-20250514",
                models = listOf("claude-sonnet-4-20250514", "claude-3-7-sonnet-latest", "claude-3-5-sonnet-latest", "claude-3-5-haiku-latest")
            ),
            AiProvider(
                id = "qwen",
                label = "通义千问 (阿里云百炼)",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                defaultModel = "qwen-plus",
                models = listOf("qwen-plus", "qwen-max", "qwen-turbo", "qwen3-235b-a22b", "qwq-plus")
            ),
            AiProvider(
                id = "zhipu",
                label = "智谱 GLM (BigModel)",
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                defaultModel = "glm-4-flash",
                models = listOf("glm-4-flash", "glm-4-plus", "glm-4-air", "glm-4-long", "glm-z1-flash")
            ),
            AiProvider(
                id = "moonshot",
                label = "Moonshot (Kimi)",
                baseUrl = "https://api.moonshot.cn/v1",
                defaultModel = "moonshot-v1-auto",
                models = listOf("moonshot-v1-auto", "kimi-latest", "moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k")
            ),
            AiProvider(
                id = "volcengine",
                label = "火山引擎 (豆包 Ark)",
                baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
                defaultModel = "doubao-1-5-pro-32k-250115",
                models = listOf("doubao-1-5-pro-32k-250115", "deepseek-r1-250120", "deepseek-v3-250324")
            ),
            AiProvider(
                id = "groq",
                label = "Groq",
                baseUrl = "https://api.groq.com/openai/v1",
                defaultModel = "llama-3.3-70b-versatile",
                models = listOf("llama-3.3-70b-versatile", "deepseek-r1-distill-llama-70b", "qwen-qwq-32b")
            ),
            AiProvider(
                id = "tokenplan",
                label = "TokenPlan (Mimo)",
                baseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
                defaultModel = "mimo-v2.5-pro",
                models = listOf("mimo-v2.5-pro", "mimo-v2.5-flash", "mimo-v2.0")
            ),
            AiProvider(
                id = "ollama",
                label = "Ollama (本地/局域网)",
                baseUrl = "http://10.0.2.2:11434/v1",
                defaultModel = "deepseek-r1:latest",
                models = listOf("deepseek-r1:latest", "qwen2.5-coder:latest", "llama3.2:latest")
            ),
            AiProvider(
                id = "custom",
                label = "自定义 (OpenAI 兼容 / 中转站)",
                baseUrl = "",
                defaultModel = "",
                models = emptyList()
            )
        )

        fun byId(id: String): AiProvider = PRESETS.firstOrNull { it.id == id } ?: PRESETS.last()

        /**
         * 根据具有唯一特征的 API Key 前缀/格式自动识别提供商。
         * 对通用 `sk-...` 不做盲目猜测（交由在线探测处理）。
         */
        fun detectFromKeyPrefix(cleanKey: String): AiProvider? {
            if (cleanKey.length < 12) return null
            return when {
                cleanKey.startsWith("AIzaSy") -> byId("gemini")
                cleanKey.startsWith("sk-or-") -> byId("openrouter")
                cleanKey.startsWith("sk-ant-") -> byId("claude")
                cleanKey.startsWith("gsk_") -> byId("groq")
                cleanKey.startsWith("sk-proj-") || cleanKey.startsWith("sk-svcacct-") -> byId("openai")
                Regex("^[0-9a-fA-F]{32}\\.[a-zA-Z0-9_-]{8,}$").matches(cleanKey) -> byId("zhipu")
                else -> null
            }
        }
    }
}
