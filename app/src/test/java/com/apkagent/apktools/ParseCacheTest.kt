package com.apkagent.apktools

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ParseCacheTest {

    @After
    fun cleanup() {
        ParseCache.clear()
    }

    @Test
    fun `stats returns expected format`() {
        val stats = ParseCache.stats()
        assertTrue(stats.contains("缓存"))
        assertTrue(stats.contains("0/20"))
    }

    @Test
    fun `clear resets stats`() {
        ParseCache.clear()
        val stats = ParseCache.stats()
        assertTrue(stats.contains("0/20"))
    }

    @Test
    fun `contentHash returns consistent hash`() {
        val data = ByteArray(100) { it.toByte() }
        val h1 = ParseCache.contentHash(data)
        val h2 = ParseCache.contentHash(data)
        assertEquals(h1, h2)
    }
}
