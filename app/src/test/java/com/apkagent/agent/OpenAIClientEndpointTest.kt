package com.apkagent.agent

import com.apkagent.store.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAIClientEndpointTest {

    @Test
    fun `sanitizeApiKey removes bearer prefix, quotes, newlines and zero-width chars`() {
        assertEquals("sk-1234567890abcdef", OpenAIClient.sanitizeApiKey("  Bearer sk-1234567890abcdef\r\n "))
        assertEquals("sk-abcdef", OpenAIClient.sanitizeApiKey("\"bearer   sk-abcdef\""))
        assertEquals("AIzaSy12345", OpenAIClient.sanitizeApiKey("“AIzaSy\u200B12345”"))
        assertEquals("sk-test", OpenAIClient.sanitizeApiKey("apiKey: sk-test"))
    }

    @Test
    fun `buildChatEndpoint handles standard v1, v3, v4 and gemini correctly`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            OpenAIClient.buildChatEndpoint("https://api.deepseek.com/v1")
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            OpenAIClient.buildChatEndpoint("https://open.bigmodel.cn/api/paas/v4")
        )
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            OpenAIClient.buildChatEndpoint("https://ark.cn-beijing.volces.com/api/v3")
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            OpenAIClient.buildChatEndpoint("https://generativelanguage.googleapis.com/v1beta")
        )
        assertEquals(
            "https://api.customrelay.com/v1/chat/completions",
            OpenAIClient.buildChatEndpoint("https://api.customrelay.com")
        )
        assertEquals(
            "https://api.customrelay.com/v1/chat/completions",
            OpenAIClient.buildChatEndpoint("https://api.customrelay.com/v1/chat/completions")
        )
    }

    @Test
    fun `detectFromKeyPrefix identifies unambiguous providers`() {
        assertEquals("gemini", AiProvider.detectFromKeyPrefix("AIzaSyD-1234567890abcdef")?.id)
        assertEquals("openrouter", AiProvider.detectFromKeyPrefix("sk-or-v1-1234567890abcdef")?.id)
        assertEquals("claude", AiProvider.detectFromKeyPrefix("sk-ant-api03-1234567890")?.id)
        assertEquals("groq", AiProvider.detectFromKeyPrefix("gsk_1234567890abcdef")?.id)
        assertEquals("openai", AiProvider.detectFromKeyPrefix("sk-proj-1234567890abcdef")?.id)
        assertEquals("zhipu", AiProvider.detectFromKeyPrefix("0123456789abcdef0123456789abcdef.AbCdEf123456")?.id)
        assertNull(AiProvider.detectFromKeyPrefix("sk-generic1234567890abcdef"))
    }
}
