package com.apkagent.apktools.advanced

import com.apkagent.i18n.L
import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import com.apkagent.agent.strProp
import com.apkagent.agent.schemaObject
import com.apkagent.apktools.Sandbox
import com.apkagent.apktools.strNonBlank
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Split APK / App Bundle 处理工具。
 * 支持 .apks（Split APK Bundle）、.apkm（APKMirror Bundle）、.xapk（APKPure Bundle）格式。
 */
object SplitApkHandler : Tool {
    override val name = "apk_split_handler"
    override val description get() = L(
        "处理 Split APK / App Bundle（.apks/.apkm/.xapk）。可列出分片、提取分片、合并为单个 APK。",
        "Process Split APK / App Bundle (.apks/.apkm/.xapk): list splits, extract splits, or merge."
    )
    override val parameters get() = schemaObject(
        mapOf(
            "bundle_path" to strProp(L("Bundle 文件路径（.apks/.apkm/.xapk）", "Bundle file path (.apks/.apkm/.xapk)")),
            "action" to strProp(L("操作：list（列出分片）/ extract（提取）/ merge（合并为单个 APK）", "Operation: list / extract / merge")),
            "output_dir" to strProp(L("输出目录（extract/merge 时可选，默认 splits/）", "Output directory (optional for extract/merge)")),
            "target_apk" to strProp(L("提取指定分片名称（可选，默认全部）", "Target split name to extract (optional)"))
        )
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawBundle = args.strNonBlank("bundle_path")
        val bundleFile = when {
            rawBundle != null -> Sandbox.resolve(File(rawBundle), ctx.workspace)
            ctx.openApk != null -> ctx.openApk!!
            else -> return ToolResult(false, L("缺少 bundle_path", "Missing bundle_path"))
        }
        val action = args.strNonBlank("action") ?: "list"
        val rawOut = args.strNonBlank("output_dir")
        val outDir = if (rawOut != null) Sandbox.resolve(File(rawOut), ctx.workspace) else File(ctx.workspace, "splits")
        val targetApk = args.strNonBlank("target_apk")

        if (!bundleFile.exists()) return ToolResult(false, "文件不存在: ${bundleFile.absolutePath}")

        return when (action) {
            "list" -> listSplits(bundleFile)
            "extract" -> extractSplits(bundleFile, outDir, targetApk)
            "merge" -> mergeSplits(bundleFile, outDir)
            else -> ToolResult(false, "未知操作: $action，支持 list/extract/merge")
        }
    }

    private fun listSplits(bundleFile: File): ToolResult {
        return try {
            val splits = mutableListOf<SplitInfo>()
            ZipFile(bundleFile).use { zip ->
                zip.entries().toList().forEach { entry ->
                    if (entry.name.endsWith(".apk")) {
                        val size = entry.size
                        val config = extractConfigFromName(entry.name)
                        splits.add(SplitInfo(entry.name, size, config))
                    }
                }
            }

            if (splits.isEmpty()) {
                return ToolResult(false, L("Bundle 内未找到 APK 分片", "No APK splits found in bundle"))
            }

            val sb = StringBuilder()
            sb.appendLine("📦 Bundle 分片列表（${splits.size} 个）：")
            sb.appendLine()
            splits.sortedBy { it.name }.forEach { split ->
                val sizeStr = formatSize(split.size)
                val configStr = if (split.config != null) " [${split.config}]" else ""
                sb.appendLine("  • ${split.name}$configStr ($sizeStr)")
            }

            ToolResult(true, sb.toString())
        } catch (e: Exception) {
            ToolResult(false, "读取 Bundle 失败: ${e.message}")
        }
    }

    private fun extractSplits(bundleFile: File, outputDir: File, targetApk: String?): ToolResult {
        return try {
            outputDir.mkdirs()
            var count = 0
            ZipFile(bundleFile).use { zip ->
                zip.entries().toList().forEach { entry ->
                    if (entry.name.endsWith(".apk")) {
                        if (targetApk != null && !entry.name.contains(targetApk)) return@forEach
                        val outFile = File(outputDir, entry.name)
                        outFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        count++
                    }
                }
            }

            if (count == 0) {
                ToolResult(false, L("未找到匹配的 APK 分片", "No matching APK splits found"))
            } else {
                ToolResult(true, "✅ 提取完成：$count 个分片\n输出目录: ${outputDir.absolutePath}")
            }
        } catch (e: Exception) {
            ToolResult(false, "提取失败: ${e.message}")
        }
    }

    private fun mergeSplits(bundleFile: File, outputDir: File): ToolResult {
        return try {
            outputDir.mkdirs()
            val tempDir = File(outputDir, "temp_splits")
            tempDir.mkdirs()

            val extractResult = extractSplits(bundleFile, tempDir, null)
            if (!extractResult.success) return extractResult

            val baseApk = tempDir.listFiles()?.find { it.name == "base.apk" || it.name.contains("base") }
                ?: return ToolResult(false, L("未找到 base.apk，无法合并", "base.apk not found, cannot merge"))

            val mergedApk = File(outputDir, "${bundleFile.nameWithoutExtension}_merged.apk")
            val splits = tempDir.listFiles()?.filter {
                it.name.endsWith(".apk") && it != baseApk
            } ?: emptyList()

            baseApk.copyTo(mergedApk, overwrite = true)

            val sb = StringBuilder()
            sb.appendLine(L("⚠️ Split APK 提取与合并结果：", "⚠️ Split APK Extraction & Merge Result:"))
            sb.appendLine()
            sb.appendLine("已提取 ${splits.size + 1} 个分片到: ${tempDir.absolutePath}")
            sb.appendLine("基础 APK: ${baseApk.name}")
            sb.appendLine(L("配置分片:", "Config splits:"))
            splits.forEach { sb.appendLine("  • ${it.name}") }
            sb.appendLine()
            sb.appendLine(L("💡 建议：", "Recommendations:"))
            sb.appendLine(L("  1. 直接安装所有分片：使用 apk_install 传入 ${tempDir.absolutePath}", "1. Install all splits directly using apk_install"))
            sb.appendLine(L("  2. 仅修改 base.apk：绝大多数逆向修改只需针对 base.apk", "2. Modify base.apk only"))

            ToolResult(true, sb.toString())
        } catch (e: Exception) {
            ToolResult(false, "合并失败: ${e.message}")
        }
    }

    private fun extractConfigFromName(name: String): String? {
        val parts = name.removeSuffix(".apk").split("_")
        return if (parts.size > 1) parts.drop(1).joinToString("_") else null
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        }
    }

    private data class SplitInfo(val name: String, val size: Long, val config: String?)
}
