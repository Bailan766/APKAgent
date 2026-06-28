package com.apkagent.apktools

import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 解析结果缓存层。
 * 对 DEX/AXML/resources.arsc 的解析结果按文件内容 hash 缓存，
 * 避免重复解析大文件（几秒→毫秒级命中）。
 * LRU 淘汰，内存占用上限 50MB。
 */
object ParseCache {
    private const val TAG = "ParseCache"
    private const val MAX_ENTRIES = 20
    private const val MAX_MEMORY_BYTES = 50L * 1024 * 1024 // 50MB

    private val cache = LinkedHashMap<String, CacheEntry>(MAX_ENTRIES, 0.75f, true)
    private var totalMemoryBytes = 0L

    data class CacheEntry(
        val key: String,
        val data: Any,
        val memoryBytes: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 计算文件内容的 SHA-256 hash（前 4KB + 文件大小，快速近似）
     */
    fun contentHash(file: File): String {
        if (!file.exists()) return "missing"
        val size = file.length()
        val head = ByteArray(minOf(4096, size.toInt()))
        try {
            file.inputStream().use { it.read(head) }
        } catch (_: Exception) {}
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(head)
        digest.update(size.toString().toByteArray())
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    fun contentHash(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data.take(4096).toByteArray())
        digest.update(data.size.toString().toByteArray())
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(file: File, parser: String): T? {
        val key = "${parser}:${contentHash(file)}"
        val entry = cache[key] ?: return null
        Log.d(TAG, "命中缓存: $key")
        return entry.data as? T
    }

    fun put(file: File, parser: String, data: Any, memoryBytes: Long) {
        val key = "${parser}:${contentHash(file)}"
        evictIfNeeded(memoryBytes)
        cache[key] = CacheEntry(key, data, memoryBytes)
        totalMemoryBytes += memoryBytes
        Log.d(TAG, "缓存写入: $key (${memoryBytes / 1024}KB, 总计 ${totalMemoryBytes / 1024}KB)")
    }

    fun <T> getOrParse(
        file: File,
        parser: String,
        estimatedMemory: Long = 0,
        parseFn: () -> T
    ): T {
        get<T>(file, parser)?.let { return it }
        val result = parseFn()
        val mem = if (estimatedMemory > 0) estimatedMemory else estimateSize(result)
        put(file, parser, result as Any, mem)
        return result
    }

    private fun evictIfNeeded(needed: Long) {
        while (cache.size >= MAX_ENTRIES || totalMemoryBytes + needed > MAX_MEMORY_BYTES) {
            val oldest = cache.entries.firstOrNull() ?: break
            totalMemoryBytes -= oldest.value.memoryBytes
            cache.remove(oldest.key)
            Log.d(TAG, "淘汰缓存: ${oldest.key}")
        }
    }

    private fun estimateSize(data: Any): Long {
        return when (data) {
            is DexParser.DexInfo -> 1024L + data.classes.size * 100
            is ByteArray -> data.size.toLong()
            is String -> data.length.toLong() * 2
            is List<*> -> data.size.toLong() * 256
            else -> 4096L
        }
    }

    fun clear() {
        cache.clear()
        totalMemoryBytes = 0L
        Log.i(TAG, "缓存已清空")
    }

    fun stats(): String = "缓存: ${cache.size}/$MAX_ENTRIES 条, ${totalMemoryBytes / 1024}KB / ${MAX_MEMORY_BYTES / 1024}KB"
}
