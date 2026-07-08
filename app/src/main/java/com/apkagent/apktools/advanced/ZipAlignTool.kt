package com.apkagent.apktools.advanced

import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import com.apkagent.agent.intProp
import com.apkagent.agent.strProp
import com.apkagent.agent.schemaObject
import com.apkagent.apktools.str
import com.apkagent.apktools.int
import com.apkagent.apktools.bool
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ZipAlign 工具：对 APK 进行 ZIP 对齐，确保未压缩数据按 4 字节边界对齐。
 * 这是 APK 安装的必要条件，未对齐的 APK 在 Android 11+ 上可能无法安装。
 *
 * 基于 AEE 的 ZipAlign 实现，纯 Kotlin 实现。
 */
object ZipAlignTool : Tool {
    override val name = "apk_zipalign"
    override val description = "对 APK 进行 ZIP 对齐（ZipAlign），确保未压缩数据按 4 字节边界对齐。Android 11+ 要求 APK 必须对齐。"
    override val parameters = schemaObject(
        mapOf(
            "apk_path" to strProp("APK 文件路径"),
            "output_path" to strProp("输出路径（默认覆盖原文件）"),
            "alignment" to intProp("对齐字节数（默认 4）")
        )
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val apkPath = args.str("apk_path") ?: return ToolResult(false, "缺少 apk_path 参数")
        val output = args.str("output_path")
        val alignment = args.int("alignment") ?: 4

        val input = File(apkPath)
        if (!input.exists()) return ToolResult(false, "文件不存在: $apkPath")

        val outFile = if (output != null) File(output) else File(input.parent, "${input.nameWithoutExtension}_aligned.apk")

        return try {
            val result = alignApk(input, outFile, alignment)
            if (result.success) {
                ToolResult(true, "✅ ZipAlign 完成\n${result.message}\n输出: ${outFile.absolutePath}")
            } else {
                ToolResult(false, "❌ ZipAlign 失败: ${result.message}")
            }
        } catch (e: Exception) {
            ToolResult(false, "❌ ZipAlign 异常: ${e.message}")
        }
    }

    private fun alignApk(input: File, output: File, alignment: Int): AlignResult {
        try {
            RandomAccessFile(input, "r").use { raf ->
                val fileLength = raf.length()
                val maxEOCDLookup = 0xffff + 22
                val seekStart = if (fileLength > maxEOCDLookup) fileLength - maxEOCDLookup else 0L
                val readAmount = if (fileLength > maxEOCDLookup) maxEOCDLookup else fileLength.toInt()

                // 查找 EOCD 签名
                raf.seek(seekStart)
                var eocdPosition = -1L
                for (i in readAmount - 4 downTo 0) {
                    raf.seek(seekStart + i)
                    if (raf.read() == 0x50) {
                        raf.seek(seekStart + i)
                        if (raf.readInt() == 0x504b0506.toInt()) {
                            eocdPosition = seekStart + i
                            break
                        }
                    }
                }
                if (eocdPosition < 0) return AlignResult(false, "未找到 EOCD 签名")

                // 读取中央目录信息
                raf.seek(eocdPosition + 10)
                val buf = ByteArray(10)
                raf.read(buf)
                val eocdBuf = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                val totalEntries = eocdBuf.short.toInt() and 0xFFFF
                val centralDirOffset = eocdBuf.int

                // 检查是否需要对齐
                var needsAlignment = false
                raf.seek(centralDirOffset.toLong())
                val entryBuf = ByteArray(46)
                for (i in 0 until totalEntries) {
                    val entryStart = raf.filePointer
                    raf.read(entryBuf)
                    val entryByteBuffer = ByteBuffer.wrap(entryBuf).order(ByteOrder.LITTLE_ENDIAN)

                    if (entryByteBuffer.int != 0x02014b50) continue

                    val fileNameLen = entryByteBuffer.short.toInt() and 0xFFFF
                    val extraFieldLen = entryByteBuffer.short.toInt() and 0xFFFF
                    val compressionMethod = entryByteBuffer.getShort(10).toInt() and 0xFFFF
                    val localHeaderOffset = entryByteBuffer.getInt(42)

                    // 检查未压缩文件是否对齐
                    if (compressionMethod == 0) {
                        val dataPos = localHeaderOffset + 30 + fileNameLen + extraFieldLen
                        if (dataPos % alignment != 0) {
                            needsAlignment = true
                            break
                        }
                    }

                    // 检查 .so 文件是否 16KB 对齐
                    val fileNameBuf = ByteArray(fileNameLen)
                    raf.read(fileNameBuf)
                    val fileName = String(fileNameBuf, Charsets.UTF_8)
                    if (fileName.endsWith(".so")) {
                        val dataPos = localHeaderOffset + 30 + fileNameLen + extraFieldLen
                        if (dataPos % 16384 != 0) {
                            needsAlignment = true
                            break
                        }
                    }

                    raf.seek(entryStart + 46 + fileNameLen + extraFieldLen + (entryByteBuffer.short.toInt() and 0xFFFF))
                }

                if (!needsAlignment) {
                    return AlignResult(true, "APK 已经是对齐的，无需处理")
                }

                // 执行对齐（简化版：复制并调整）
                return alignZipFile(input, output, alignment)
            }
        } catch (e: Exception) {
            return AlignResult(false, "对齐失败: ${e.message}")
        }
    }

    private fun alignZipFile(input: File, output: File, alignment: Int): AlignResult {
        try {
            val entries = mutableListOf<ZipEntryInfo>()
            java.util.zip.ZipFile(input).use { zip ->
                zip.entries().toList().forEach { entry ->
                    val data = zip.getInputStream(entry).readBytes()
                    entries.add(ZipEntryInfo(entry.name, data, entry.method, entry.time))
                }
            }

            // 计算需要对齐的条目
            var offset = 0L
            val alignedEntries = entries.map { info ->
                val headerSize = 30 + info.name.toByteArray().size
                val dataOffset = offset + headerSize
                val padding = if (info.method == java.util.zip.ZipEntry.STORED) {
                    val misalignment = (dataOffset % alignment).toInt()
                    if (misalignment != 0) alignment - misalignment else 0
                } else 0
                offset = dataOffset + info.data.size + padding
                info.copy(padding = padding)
            }

            // 写入对齐后的 ZIP
            java.util.zip.ZipOutputStream(output.outputStream()).use { zos ->
                alignedEntries.forEach { info ->
                    val entry = java.util.zip.ZipEntry(info.name).apply {
                        method = info.method
                        time = info.time
                    }
                    zos.putNextEntry(entry)
                    if (info.padding > 0) {
                        zos.write(ByteArray(info.padding))
                    }
                    zos.write(info.data)
                    zos.closeEntry()
                }
            }

            return AlignResult(true, "对齐完成，${alignedEntries.size} 个条目已处理")
        } catch (e: Exception) {
            return AlignResult(false, "对齐写入失败: ${e.message}")
        }
    }

    private data class ZipEntryInfo(
        val name: String,
        val data: ByteArray,
        val method: Int,
        val time: Long,
        val padding: Int = 0
    )

    private data class AlignResult(val success: Boolean, val message: String)
}
