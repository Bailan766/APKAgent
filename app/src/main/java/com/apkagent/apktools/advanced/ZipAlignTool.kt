package com.apkagent.apktools.advanced

import com.apkagent.i18n.L
import com.android.apksig.ApkVerifier
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
 * 5. 检测输入已含 v2/v3 签名时拒绝对齐（ZipOutputStream 重写会丢失 APK Signing Block），引导「先对齐后签名」。
 * 6. 不再把所有 entry 全量读入内存，改为流式分块读取/写出，并对超大 STORED 条目给出明确错误，避免 OOM。
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

    private const val MAX_STORED_ENTRY_BYTES = 256L * 1024 * 1024

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
        // ZipOutputStream 重写 ZIP 会丢弃 APK Signing Block；仅 v2/v3 签名的包对齐后会变成未签名，明确拒绝
        if (hasV2OrV3Signature(input)) {
            return AlignResult(false, L(
                "输入 APK 已包含 v2/v3 签名（APK Signing Block）。ZipAlign 会重写 ZIP 并丢失签名块，导致包变为未签名；请遵循「先对齐、后签名」：对未签名或仅 v1 的包执行对齐，再重新签名。",
                "Input APK already has a v2/v3 signature. ZipAlign rewrites the ZIP and drops the APK Signing Block, leaving the APK unsigned. Follow 'align first, sign later': align an unsigned or v1-only APK, then sign it."
            ))
        }
        return try {
            val tempOut = if (input.canonicalPath == output.canonicalPath) {
                File(output.parentFile, "${output.name}.align_tmp")
            } else {
                output
            }
            tempOut.parentFile?.mkdirs()
            val res = alignZipFile(input, tempOut, alignment)
            if (res.success) {
                if (tempOut != output) {
                    tempOut.copyTo(output, overwrite = true)
                    tempOut.delete()
                }
            } else {
                // 失败时清理半成品，避免残留损坏的 APK
                if (tempOut.exists() && tempOut != input) tempOut.delete()
            }
            res
        } catch (e: Exception) {
            AlignResult(false, L("对齐失败: ${e.message}", "Alignment failed: ${e.message}"))
        }
    }

    /** 用 apksig 判断输入是否已含 v2/v3 签名；未签名/校验失败按「无 v2/v3」处理，允许对齐。 */
    private fun hasV2OrV3Signature(apk: File): Boolean = try {
        val res = ApkVerifier.Builder(apk).build().verify()
        res.isVerifiedUsingV2Scheme || res.isVerifiedUsingV3Scheme
    } catch (_: Throwable) {
        false
    }

    private fun alignZipFile(input: File, output: File, alignment: Int): AlignResult {
        return try {
            var alignedCount = 0
            var total = 0
            val buffer = ByteArray(64 * 1024)
            ZipFile(input).use { zip ->
                val countingOut = CountingOutputStream(output.outputStream().buffered())
                ZipOutputStream(countingOut).use { zos ->
                    zip.entries().toList().forEach { srcEntry ->
                        if (srcEntry.isDirectory) return@forEach
                        // Android 11+ 要求 resources.arsc 必须 STORED 未压缩
                        val method = if (srcEntry.name == "resources.arsc") ZipEntry.STORED else srcEntry.method
                        val entry = ZipEntry(srcEntry.name)
                        if (method == ZipEntry.STORED) {
                            val size = srcEntry.size
                            // STORED 需提前知道 size/crc；限制单条目大小，防止畸形包 OOM
                            if (size < 0 || size > MAX_STORED_ENTRY_BYTES) {
                                throw EntryTooLargeException("条目 ${srcEntry.name} 过大或大小未知（$size 字节），已拒绝对齐以避免 OOM")
                            }
                            val crc = CRC32()
                            zip.getInputStream(srcEntry).use { inp ->
                                while (true) {
                                    val n = inp.read(buffer)
                                    if (n < 0) break
                                    if (n > 0) crc.update(buffer, 0, n)
                                }
                            }
                            entry.method = ZipEntry.STORED
                            entry.size = size
                            entry.compressedSize = size
                            entry.crc = crc.value

                            zos.flush()
                            val nameBytesLen = srcEntry.name.toByteArray(Charsets.UTF_8).size
                            val headerBase = countingOut.bytesWritten + 30L + nameBytesLen
                            val targetAlign = if (srcEntry.name.endsWith(".so")) maxOf(alignment, 4096) else alignment
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
                        // 流式拷贝，不在内存缓存整包
                        zip.getInputStream(srcEntry).use { inp ->
                            while (true) {
                                val n = inp.read(buffer)
                                if (n < 0) break
                                if (n > 0) zos.write(buffer, 0, n)
                            }
                        }
                        zos.closeEntry()
                        total++
                    }
                }
            }
            AlignResult(true, "对齐完成，共处理 $total 个条目（已对齐 $alignedCount 个 STORED 条目）")
        } catch (e: EntryTooLargeException) {
            AlignResult(false, e.message ?: "单条目过大，已拒绝对齐")
        } catch (e: Exception) {
            AlignResult(false, "对齐写入失败: ${e.message}")
        }
    }

    private class EntryTooLargeException(message: String) : IllegalArgumentException(message)

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

    data class AlignResult(val success: Boolean, val message: String)
}
