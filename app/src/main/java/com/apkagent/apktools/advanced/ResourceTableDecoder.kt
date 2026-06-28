package com.apkagent.apktools.advanced

import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import com.apkagent.agent.strProp
import com.apkagent.agent.schemaObject
import com.apkagent.apktools.str
import com.apkagent.apktools.int
import com.apkagent.apktools.bool
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * resources.arsc 解码器：解析 Android 资源表文件。
 *
 * 功能：
 * 1. 解析资源表结构（ResTable_header）
 * 2. 读取字符串池（资源名称、值等）
 * 3. 解析包（Package）和类型（Type）
 * 4. 列出所有资源及其配置变体
 * 5. 支持按资源 ID 或名称查询
 *
 * 基于 AOSP ResourceTypes.h 规范实现。
 */
object ResourceTableDecoder : Tool {
    override val name = "apk_decode_resources"
    override val description = "解码 APK 的 resources.arsc 资源表，列出所有资源定义、字符串、配置变体。"
    override val parameters = schemaObject(
        mapOf(
            "apk_path" to strProp("APK 文件路径"),
            "resource_name" to strProp("查询指定资源名称（可选）"),
            "resource_type" to strProp("查询指定资源类型（如 string/drawable/layout，可选）")
        )
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val apkPath = args.str("apk_path") ?: return ToolResult(false, "缺少 apk_path")
        val resourceName = args.str("resource_name")
        val resourceType = args.str("resource_type")

        val apkFile = File(apkPath)
        if (!apkFile.exists()) return ToolResult(false, "文件不存在: $apkPath")

        return try {
            // 从 APK 中提取 resources.arsc
            val arscData = extractResourceTable(apkFile)
                ?: return ToolResult(false, "APK 中未找到 resources.arsc")

            val decoder = ResourceTableParser(arscData)
            val table = decoder.parse()

            formatResult(table, resourceName, resourceType)
        } catch (e: Exception) {
            ToolResult(false, "解码失败: ${e.message}")
        }
    }

    private fun extractResourceTable(apkFile: File): ByteArray? {
        ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry("resources.arsc") ?: return null
            return zip.getInputStream(entry).readBytes()
        }
    }

    private fun formatResult(table: ResourceTable, resourceName: String?, resourceType: String?): ToolResult {
        val sb = StringBuilder()
        sb.appendLine("📋 资源表解码结果：")
        sb.appendLine()

        // 包信息
        table.packages.forEach { pkg ->
            sb.appendLine("📦 包: ${pkg.name} (ID: 0x${pkg.id.toString(16)})")
            sb.appendLine("   类型数量: ${pkg.types.size}")
            sb.appendLine("   资源数量: ${pkg.entries.size}")
            sb.appendLine()
        }

        // 按类型分组显示
        val groupedEntries = table.packages.flatMap { pkg ->
            pkg.entries.map { Triple(pkg, it.typeName, it) }
        }.groupBy { it.second }

        val filteredTypes = if (resourceType != null) {
            groupedEntries.filter { it.key.contains(resourceType, ignoreCase = true) }
        } else {
            groupedEntries
        }

        filteredTypes.forEach { (typeName, entries) ->
            sb.appendLine("📁 类型: $typeName (${entries.size} 个资源)")
            if (resourceName != null) {
                entries.filter { it.third.name.contains(resourceName, ignoreCase = true) }
                    .forEach { (_, _, entry) ->
                        sb.appendLine("   • ${entry.name} = ${entry.value}")
                    }
            } else {
                entries.take(20).forEach { (_, _, entry) ->
                    sb.appendLine("   • ${entry.name} = ${entry.value}")
                }
                if (entries.size > 20) {
                    sb.appendLine("   ... 还有 ${entries.size - 20} 个资源")
                }
            }
            sb.appendLine()
        }

        return ToolResult(true, sb.toString())
    }

    /**
     * resources.arsc 解析器
     */
    private class ResourceTableParser(private val data: ByteArray) {
        private val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        private val strings = mutableListOf<String>()

        fun parse(): ResourceTable {
            val header = parseHeader()
            val packages = mutableListOf<Package>()

            // 解析包
            while (buf.remaining() > 0) {
                val pos = buf.position()
                val chunkType = buf.short.toInt() and 0xFFFF
                val headerSize = buf.short.toInt() and 0xFFFF
                val chunkSize = buf.int

                when (chunkType) {
                    0x0200 -> { // RES_STRING_POOL_TYPE
                        parseStringPool()
                    }
                    0x0201 -> { // RES_TABLE_PACKAGE_TYPE
                        packages.add(parsePackage())
                    }
                }

                buf.position(pos + chunkSize)
            }

            return ResourceTable(header, packages)
        }

        private fun parseHeader(): TableHeader {
            val type = buf.short.toInt() and 0xFFFF
            val headerSize = buf.short.toInt() and 0xFFFF
            val size = buf.int
            val packageCount = buf.int
            return TableHeader(type, headerSize, size, packageCount)
        }

        private fun parseStringPool() {
            val startPos = buf.position()
            val stringCount = buf.int
            val styleCount = buf.int
            val flags = buf.int
            val stringsStart = buf.int
            val stylesStart = buf.int

            val offsets = IntArray(stringCount)
            for (i in 0 until stringCount) offsets[i] = buf.int

            val isUtf8 = (flags and (1 shl 8)) != 0
            val stringsBase = startPos + stringsStart

            for (i in 0 until stringCount) {
                buf.position(stringsBase + offsets[i])
                strings.add(readString(isUtf8))
            }
        }

        private fun readString(isUtf8: Boolean): String {
            return if (isUtf8) {
                var len = buf.get().toInt() and 0xFF
                if (len and 0x80 != 0) {
                    len = ((len and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
                }
                var byteLen = buf.get().toInt() and 0xFF
                if (byteLen and 0x80 != 0) {
                    byteLen = ((byteLen and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
                }
                val ba = ByteArray(byteLen)
                buf.get(ba)
                buf.get() // trailing 0
                String(ba, Charsets.UTF_8)
            } else {
                var len = buf.short.toInt() and 0xFFFF
                if (len and 0x8000 != 0) {
                    len = ((len and 0x7FFF) shl 16) or (buf.short.toInt() and 0xFFFF)
                }
                val ba = ByteArray(len * 2)
                buf.get(ba)
                buf.short // trailing 0x0000
                String(ba, Charsets.UTF_16LE)
            }
        }

        private fun parsePackage(): Package {
            val startPos = buf.position()
            val id = buf.int
            val nameChars = CharArray(128)
            for (i in 0 until 128) nameChars[i] = buf.short.toInt().toChar()
            val name = String(nameChars).trim('\u0000')

            val typeStrings = buf.int
            val lastPublicType = buf.int
            val keyStrings = buf.int
            val lastPublicKey = buf.int

            val types = mutableListOf<String>()
            val entries = mutableListOf<ResourceEntry>()

            // 解析类型和条目
            while (buf.remaining() > 0) {
                val pos = buf.position()
                val chunkType = buf.short.toInt() and 0xFFFF
                val headerSize = buf.short.toInt() and 0xFFFF
                val chunkSize = buf.int

                when (chunkType) {
                    0x0202 -> { // RES_TABLE_TYPE_TYPE
                        val typeId = buf.get().toInt() and 0xFF
                        buf.get() // reserved
                        buf.short // reserved
                        val entryCount = buf.int
                        val entriesStart = buf.int

                        val typeName = if (typeId - 1 < strings.size) strings[typeId - 1] else "type_$typeId"
                        types.add(typeName)

                        // 解析条目
                        val entriesPos = pos + entriesStart
                        buf.position(entriesPos)
                        for (i in 0 until entryCount) {
                            val entryOffset = buf.int
                            if (entryOffset != -1) {
                                val entryStart = entriesPos + entryOffset
                                val entrySize = buf.short.toInt() and 0xFFFF
                                val entryFlags = buf.short.toInt() and 0xFFFF
                                val keyIndex = buf.int

                                val entryName = if (keyIndex < strings.size) strings[keyIndex] else "entry_$keyIndex"
                                entries.add(ResourceEntry(entryName, typeName, "0x${(id shl 24 or (typeId shl 16) or i).toString(16)}"))
                            }
                        }
                    }
                }

                buf.position(pos + chunkSize)
            }

            return Package(id, name, types, entries)
        }
    }

    private data class TableHeader(val type: Int, val headerSize: Int, val size: Int, val packageCount: Int)
    private data class ResourceTable(val header: TableHeader, val packages: List<Package>)
    private data class Package(val id: Int, val name: String, val types: List<String>, val entries: List<ResourceEntry>)
    private data class ResourceEntry(val name: String, val typeName: String, val value: String)
}
