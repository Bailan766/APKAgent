package com.apkagent.apktools

import org.junit.Assert.*
import org.junit.Test

/**
 * ParseCache 单元测试。
 * 注意：ParseCache 内部使用 android.util.Log，需要 Robolectric 或设备才能完整测试。
 * 此处仅测试不依赖 Android 框架的纯逻辑部分。
 */
class ParseCacheTest {

    @Test
    fun `contentHash returns consistent hash for same data`() {
        val data = ByteArray(100) { it.toByte() }
        val h1 = ParseCache.contentHash(data)
        val h2 = ParseCache.contentHash(data)
        assertEquals(h1, h2)
        assertTrue(h1.length == 16) // 8 bytes * 2 hex chars
    }

    @Test
    fun `contentHash returns different hash for different data`() {
        val h1 = ParseCache.contentHash(ByteArray(100) { 0 })
        val h2 = ParseCache.contentHash(ByteArray(100) { 1 })
        assertNotEquals(h1, h2)
    }
}
