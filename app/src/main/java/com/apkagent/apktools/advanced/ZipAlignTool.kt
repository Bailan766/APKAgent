package com.apkagent.apktools.advanced

import com.apkagent.i18n.L
import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import com.apkagent.agent.intProp
import com.apkagent.agent.strProp
import com.apkagent.agent.schemaObject
import com.apkagent.apktools.Sandbox
import com.apkagent.apktools.str
import com.apkagent.apktools.int
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * ZipAlign 工具：对 APK 进行 ZIP 对齐，确保未压缩数据（特别是 resources.arsc 与 .so）按边界对齐。
 *
 * 修复说明：
 * 1. 修复原实现将 padding 字节直接写入文件内容流（导致所有 STORED 文件头被插入 0x00 损坏）的严重 Bug，
 *    改为写入 ZipEntry.extra 字段。
 * 2. 修复 ZipEntry.STORED 未设置 size / compressedSize / crc 导致抛出 ZipException 的 Bug。
 * 3. 使用 CountingOutputStream 实时追踪 DEFLATED 压缩后的真实字节偏移，确保 STORED 条目 100% 精确对齐。
 * 4. 强制将 resources.arsc 设为 STORED + 4 字节对齐，满足 Android 11+ (API 30+) 安装要求。
 */
object ZipAlignTool : Tool {
    override val name = "apk_zipalign"
    override val description get() = L(
        "对 APK 进行 ZIP 对齐（ZipAlign），确保 resources.arsc 与未压缩数据按 4 字节边界对齐。Android 11+ 要求 APK 必须对齐。",
        "ZipAlign for APK to ensure uncompressed entries and resources.arsc are aligned to 4-byte boundaries (required on Android 11+)."
    )
    override val parameters get() = schemaObject(
        mapOf(
            "apk_path" to strProp(L("APK 文件路径（留空默认当前打开的 APK）", "APK file path (defaults to currently open APK)")),
            "output_path" to strProp(L("输出路径（默认生成 *_aligned.apk）", "Output path (default *_aligned.apk)")),
            "alignment" to intProp(L("对齐字节数（默认 4）", "Alignment bytes (default 4)"))
        )
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPath = args.str("apk_path")?.takeIf { it.isNotBlank() }
        val input = when {
            rawPath != null -> Sandbox.resolve(File(rawPath), ctx.workspace)
            ctx.openApk != null -> ctx.openApk!!
            else -> File(ctx.workspace, "base.apk")
        }
        val output = args.str("output_path")?.takeIf { it.isNotBlank() }
        val alignment = (args.int("alignment") ?: 4).coerceAtLeast(4)

        if (!input.exists() || !input.isFile) {
            return ToolResult(false, "文件不存在: ${input.absolutePath}")
        }

        val outFile = if (output != null) {
            Sandbox.resolve(File(output), ctx.workspace)
        } else {
            File(ctx.workspace, "${input.nameWithoutExtension}_aligned.apk")
        }

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

    fun alignApk(input: File, output: File, alignment: Int = 4): AlignResult {
        return try {
            val tempOut = if (input.canonicalPath == output.canonicalPath) {
                File(output.parentFile, "${output.name}.align_tmp")
            } else {
                output
            }
            tempOut.parentFile?.mkdirs()
            val res = alignZipFile(input, tempOut, alignment)
            if (res.success && tempOut != output) {
                tempOut.copyTo(output, overwrite = true)
                tempOut.delete()
            }
            res
        } catch (e: Exception) {
            AlignResult(false, L("对齐失败: ${e.message}", "Alignment failed: ${e.message}"))
        }
    }

    private fun alignZipFile(input: File, output: File, alignment: Int): AlignResult {
        return try {
            val entries = mutableListOf<ZipEntryInfo>()
            ZipFile(input).use { zip ->
                zip.entries().toList().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val data = zip.getInputStream(entry).use { it.readBytes() }
                    // Android 11+ 要求 resources.arsc 必须 STORED 未压缩
                    val method = if (entry.name == "resources.arsc") {
                        ZipEntry.STORED
                    } else {
                        entry.method
                    }
                    entries.add(ZipEntryInfo(entry.name, data, method))
                }
            }

            val countingOut = CountingOutputStream(output.outputStream().buffered())
            var alignedCount = 0
            ZipOutputStream(countingOut).use { zos ->
                entries.forEach { info ->
                    val entry = ZipEntry(info.name)
                    if (info.method == ZipEntry.STORED) {
                        entry.method = ZipEntry.STORED
                        entry.size = info.data.size.toLong()
                        entry.compressedSize = info.data.size.toLong()
                        val crc = CRC32()
                        crc.update(info.data)
                        entry.crc = crc.value

                        zos.flush()
                        val nameBytesLen = info.name.toByteArray(Charsets.UTF_8).size
                        val headerBase = countingOut.bytesWritten + 30L + nameBytesLen
                        val targetAlign = if (info.name.endsWith(".so")) maxOf(alignment, 4096) else alignment
                        val misalignment = (headerBase % targetAlign).toInt()
                        val padding = if (misalignment == 0) 0 else (targetAlign - misalignment)
                        if (padding > 0) {
                            entry.extra = ByteArray(padding)
                        }
                        alignedCount++
                    } else {
                        entry.method = ZipEntry.DEFLATED
                    }

                    zos.putNextEntry(entry)
                    zos.write(info.data)
                    zos.closeEntry()
                }
            }

            AlignResult(true, "对齐完成，共处理 ${entries.size} 个条目（已对齐 $alignedCount 个 STORED 条目）")
        } catch (e: Exception) {
            AlignResult(false, "对齐写入失败: ${e.message}")
        }
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            bytesWritten += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytesWritten += len
        }
    }

    private data class ZipEntryInfo(
        val name: String,
        val data: ByteArray,
        val method: Int
    )

    data class AlignResult(val success: Boolean, val message: String)
}
