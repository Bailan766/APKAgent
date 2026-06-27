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
                id = "openai",
                label = "OpenAI GPT",
                baseUrl = "https://api.openai.com/v1",
                defaultModel = "gpt-4o-mini",
                models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4-turbo", "o3-mini")
            ),
            AiProvider(
                id = "claude",
                label = "Claude",
                baseUrl = "https://api.anthropic.com/v1",
                defaultModel = "claude-sonnet-4-20250514",
                models = listOf("claude-sonnet-4-20250514", "claude-3-5-sonnet-20241022", "claude-3-haiku-20240307")
            ),
            AiProvider(
                id = "gemini",
                label = "Gemini",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                defaultModel = "gemini-2.5-flash",
                models = listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash")
            ),
            AiProvider(
                id = "qwen",
                label = "通义千问",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                defaultModel = "qwen-plus",
                models = listOf("qwen-plus", "qwen-max", "qwen-turbo", "qwen3-235b-a22b")
            ),
            AiProvider(
                id = "zhipu",
                label = "智谱 GLM",
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                defaultModel = "glm-4-flash",
                models = listOf("glm-4-flash", "glm-4-plus", "glm-4-air")
            ),
            AiProvider(
                id = "moonshot",
                label = "Moonshot(Kimi)",
                baseUrl = "https://api.moonshot.cn/v1",
                defaultModel = "moonshot-v1-8k",
                models = listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k")
            ),
            AiProvider(
                id = "siliconflow",
                label = "硅基流动",
                baseUrl = "https://api.siliconflow.cn/v1",
                defaultModel = "deepseek-ai/DeepSeek-V3",
                models = listOf("deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1", "Qwen/Qwen3-235B-A22B")
            ),
            AiProvider(
                id = "custom",
                label = "自定义",
                baseUrl = "",
                defaultModel = "",
                models = emptyList()
            )
        )

        fun byId(id: String): AiProvider = PRESETS.firstOrNull { it.id == id } ?: PRESETS.last()
    }
}
