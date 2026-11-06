package com.apkagent.apktools

import com.apkagent.i18n.L
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 解析结果缓存层。
 * 对 DEX/AXML/resources.arsc 的解析结果按文件内容 hash 缓存，
 * 避免重复解析大文件（几秒→毫秒级命中）。
 * LRU 淘汰，内存占用上限 50MB，全部访问均加锁保证多线程安全。
 *
 * 修复说明：
 * 1. contentHash 改为全量流式 SHA-256 + Long 文件大小（大文件分块读，避免 2GB+ 溢出与只取头 4KB 的碰撞）。
 * 2. 文件缓存 key 加入 lastModified，文件被覆盖后不会命中旧结果。
 * 3. 新增 ByteArray 版 getOrParse，避免用虚拟文件路径做 key 导致不同 ByteArray 共用 "missing"。
 */
object ParseCache {
    private const val TAG = "ParseCache"
    private const val MAX_ENTRIES = 20
    private const val MAX_MEMORY_BYTES = 50L * 1024 * 1024 // 50MB
    private const val HASH_BUFFER_SIZE = 64 * 1024

    private val cache = LinkedHashMap<String, CacheEntry>(MAX_ENTRIES, 0.75f, true)
    private var totalMemoryBytes = 0L

    data class CacheEntry(
        val key: String,
        val data: Any,
        val memoryBytes: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    /** 计算文件内容的全量 SHA-256 hash（流式分块，size 用 Long），并纳入大小。 */
    fun contentHash(file: File): String {
        if (!file.exists() || !file.isFile) return "missing"
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(HASH_BUFFER_SIZE)
            var total = 0L
            file.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        digest.update(buf, 0, n)
                        total += n
                    }
                }
            }
            digest.update(total.toString().toByteArray())
            hex(digest.digest())
        } catch (_: Exception) {
            "missing"
        }
    }

    /** 计算字节数组的全量 SHA-256 hash（不再只取前 4KB）。 */
    fun contentHash(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data)
        digest.update(data.size.toString().toByteArray())
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String =
        bytes.take(8).joinToString("") { "%02x".format(it) }

    /** 文件缓存 key：parser + 内容 hash + 最后修改时间，覆盖写入后自动失效。 */
    private fun cacheKey(file: File, parser: String): String =
        "${parser}:${contentHash(file)}:${file.lastModified()}"

    @Synchronized
    @Suppress("UNCHECKED_CAST")
    private fun <T> getByKey(key: String): T? {
        val entry = cache[key] ?: return null
        try { Log.d(TAG, "命中缓存: $key") } catch (_: Throwable) {}
        return entry.data as? T
    }

    @Synchronized
    private fun putByKey(key: String, data: Any, memoryBytes: Long) {
        cache.remove(key)?.let { totalMemoryBytes -= it.memoryBytes }
        evictIfNeeded(memoryBytes)
        cache[key] = CacheEntry(key, data, memoryBytes)
        totalMemoryBytes += memoryBytes
        try { Log.d(TAG, "缓存写入: $key (${memoryBytes / 1024}KB, 总计 ${totalMemoryBytes / 1024}KB)") } catch (_: Throwable) {}
    }

    fun <T> get(file: File, parser: String): T? = getByKey(cacheKey(file, parser))

    fun put(file: File, parser: String, data: Any, memoryBytes: Long) =
        putByKey(cacheKey(file, parser), data, memoryBytes)

    fun <T> getOrParse(
        file: File,
        parser: String,
        estimatedMemory: Long = 0,
        parseFn: () -> T
    ): T {
        get<T>(file, parser)?.let { return it }
        val result = parseFn()
        val mem = if (estimatedMemory > 0) estimatedMemory else estimateSize(result as Any)
        put(file, parser, result as Any, mem)
        return result
    }

    /** ByteArray 版：直接用内容 hash 做 key，避免虚拟文件不存在导致 contentHash 恒为 "missing"。 */
    fun <T> getOrParse(
        data: ByteArray,
        parser: String,
        estimatedMemory: Long = 0,
        parseFn: () -> T
    ): T {
        val key = "${parser}:bytes:${contentHash(data)}"
        getByKey<T>(key)?.let { return it }
        val result = parseFn()
        val mem = if (estimatedMemory > 0) estimatedMemory else estimateSize(result as Any)
        putByKey(key, result as Any, mem)
        return result
    }

    private fun evictIfNeeded(needed: Long) {
        while (cache.size >= MAX_ENTRIES || totalMemoryBytes + needed > MAX_MEMORY_BYTES) {
            val oldest = cache.entries.firstOrNull() ?: break
            totalMemoryBytes = (totalMemoryBytes - oldest.value.memoryBytes).coerceAtLeast(0L)
            cache.remove(oldest.key)
            try { Log.d(TAG, L("淘汰缓存: ${oldest.key}", "Evicted cache: ${oldest.key}")) } catch (_: Throwable) {}
        }
    }

    private fun estimateSize(data: Any): Long {
        return when (data) {
            is DexParser.DexInfo -> 1024L + (data.classes.size + data.strings.size) * 80L
            is ByteArray -> data.size.toLong()
            is String -> data.length.toLong() * 2
            is List<*> -> data.size.toLong() * 256
            else -> 4096L
        }
    }

    @Synchronized
    fun clear() {
        cache.clear()
        totalMemoryBytes = 0L
        try { Log.i(TAG, L("缓存已清空", "Cache cleared")) } catch (_: Throwable) {}
    }

    @Synchronized
    fun stats(): String = "缓存: ${cache.size}/$MAX_ENTRIES 条, ${totalMemoryBytes / 1024}KB / ${MAX_MEMORY_BYTES / 1024}KB"
}
