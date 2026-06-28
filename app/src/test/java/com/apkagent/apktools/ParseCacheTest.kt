package com.apkagent.apktools

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ParseCacheTest {

    @After
    fun cleanup() {
        ParseCache.clear()
    }

    @Test
    fun `getOrParse caches result`() {
        var callCount = 0
        val result1 = ParseCache.getOrParse<String>(
            File("/nonexistent"), "test", 100
        ) { callCount++; "hello" }
        assertEquals("hello", result1)
        assertEquals(1, callCount)

        // 第二次应该命中缓存
        val result2 = ParseCache.getOrParse<String>(
            File("/nonexistent"), "test", 100
        ) { callCount++; "world" }
        assertEquals("hello", result2) // 应该返回缓存值
        assertEquals(1, callCount) // 不应该再次调用
    }

    @Test
    fun `stats shows cache info`() {
        ParseCache.getOrParse<String>(File("/test"), "parser", 100) { "data" }
        val stats = ParseCache.stats()
        assertTrue(stats.contains("1/20"))
    }

    @Test
    fun `clear empties cache`() {
        ParseCache.getOrParse<String>(File("/test"), "parser", 100) { "data" }
        ParseCache.clear()
        val stats = ParseCache.stats()
        assertTrue(stats.contains("0/20"))
    }
}
