package com.apkagent.apktools.advanced

import com.apkagent.i18n.L
import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import com.apkagent.agent.strProp
import com.apkagent.agent.schemaObject
import com.apkagent.apktools.AxmlParser
import com.apkagent.apktools.Sandbox
import com.apkagent.apktools.strNonBlank
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.zip.ZipFile

/**
 * APK 资源提取与查看工具。
 */
object ResourceExtractor : Tool {
    override val name = "apk_resources"
    override val description get() = L(
        "列出或提取 APK 内的资源文件（图片、布局、assets、raw、so 库等），支持按类型过滤。",
        "List or extract resource files inside the APK (images, layouts, assets, raw, native libraries)."
    )
    override val parameters get() = schemaObject(
        mapOf(
            "apk_path" to strProp(L("APK 文件路径（留空默认当前打开的 APK）", "APK file path (defaults to currently open APK)")),
            "action" to strProp(L("操作：list（列出资源）/ extract（提取文件）/ summary（统计概览）", "Action: list / extract / summary")),
            "filter_type" to strProp(L("过滤类型（list 时可用）：drawable/layout/mipmap/raw/assets/lib/xml/all（默认 all）", "Filter type: drawable/layout/mipmap/raw/assets/lib/xml/all")),
            "entry_name" to strProp(L("要提取的条目名称（extract 时必填，支持通配符 *）", "Entry name to extract (required for extract, supports wildcard *)")),
            "output_dir" to strProp(L("提取输出目录（extract 时可选，默认 extracted_res/）", "Output directory (optional for extract)"))
        )
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawApk = args.strNonBlank("apk_path")
        val apkFile = when {
            rawApk != null -> Sandbox.resolve(File(rawApk), ctx.workspace)
            ctx.openApk != null -> ctx.openApk!!
            else -> File(ctx.workspace, "base.apk")
        }
        val action = args.strNonBlank("action") ?: "summary"
        val filterType = args.strNonBlank("filter_type") ?: "all"
        val entryName = args.strNonBlank("entry_name")
        val rawOut = args.strNonBlank("output_dir")

        if (!apkFile.exists() || !apkFile.isFile) {
            return ToolResult(false, "文件不存在: ${apkFile.absolutePath}")
        }

        return when (action) {
            "summary" -> getResourceSummary(apkFile)
            "list" -> listResources(apkFile, filterType)
            "extract" -> {
                if (entryName == null) return ToolResult(false, L("extract 操作需要 entry_name 参数", "extract requires entry_name parameter"))
                val outDir = if (rawOut != null) Sandbox.resolve(File(rawOut), ctx.workspace) else File(ctx.workspace, "extracted_res")
                extractResources(apkFile, entryName, outDir)
            }
            else -> ToolResult(false, "未知操作: $action，支持 summary/list/extract")
        }
    }

    private fun getResourceSummary(apkFile: File): ToolResult {
        return try {
            val stats = mutableMapOf<String, MutableList<Pair<String, Long>>>()

            ZipFile(apkFile).use { zip ->
                zip.entries().toList().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val category = categorizeEntry(entry.name)
                    stats.getOrPut(category) { mutableListOf() }.add(entry.name to entry.size)
                }
            }

            val sb = StringBuilder()
            sb.appendLine("📦 APK 资源概览：${apkFile.name}")
            sb.appendLine("文件大小: ${formatSize(apkFile.length())}")
            sb.appendLine()

            stats.entries.sortedByDescending { it.value.sumOf { p -> p.second } }.forEach { (cat, items) ->
                val totalSize = items.sumOf { it.second }
                sb.appendLine("• $cat: ${items.size} 个文件 (${formatSize(totalSize)})")
            }

            val libEntries = stats["Native 库 (lib/)"] ?: emptyList()
            if (libEntries.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine(L("🔧 Native 架构:", "🔧 Native ABIs:"))
                val archs = libEntries.map { it.first.split("/").getOrNull(1) ?: "unknown" }.distinct()
                archs.forEach { arch ->
                    val count = libEntries.count { it.first.startsWith("lib/$arch/") }
                    sb.appendLine("  • $arch ($count 个 .so)")
                }
            }

            ToolResult(true, sb.toString())
        } catch (e: Exception) {
            ToolResult(false, "统计失败: ${e.message}")
        }
    }

    private fun listResources(apkFile: File, filterType: String): ToolResult {
        return try {
            val entries = mutableListOf<Pair<String, Long>>()

            ZipFile(apkFile).use { zip ->
                zip.entries().toList().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    if (matchesFilter(entry.name, filterType)) {
                        entries.add(entry.name to entry.size)
                    }
                }
            }

            if (entries.isEmpty()) {
                return ToolResult(true, "未找到匹配的条目（过滤类型: $filterType）")
            }

            val sb = StringBuilder()
            sb.appendLine("📋 资源列表（过滤: $filterType，共 ${entries.size} 项）：")
            sb.appendLine()

            val displayLimit = 200
            entries.sortedBy { it.first }.take(displayLimit).forEach { (name, size) ->
                sb.appendLine("  $name (${formatSize(size)})")
            }

            if (entries.size > displayLimit) {
                sb.appendLine()
                sb.appendLine("... 省略其余 ${entries.size - displayLimit} 项")
            }

            ToolResult(true, sb.toString())
        } catch (e: Exception) {
            ToolResult(false, "列出资源失败: ${e.message}")
        }
    }

    private fun extractResources(apkFile: File, pattern: String, outputDir: File): ToolResult {
        return try {
            outputDir.mkdirs()
            val regex = patternToRegex(pattern)
            var count = 0
            val extractedFiles = mutableListOf<String>()

            ZipFile(apkFile).use { zip ->
                zip.entries().toList().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    if (regex.matches(entry.name)) {
                        val outFile = File(outputDir, entry.name)
                        // Zip Slip 防护：越界条目直接跳过
                        if (!isSafeExtractPath(outputDir, outFile)) return@forEach
                        outFile.parentFile?.mkdirs()
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        outFile.writeBytes(bytes)
                        // 若是二进制 AXML，同步旁路生成一份可读 XML 方便查看
                        if (entry.name.endsWith(".xml", ignoreCase = true) && AxmlParser.isBinaryAxml(bytes)) {
                            val readable = File(outputDir, "${entry.name}.readable.xml")
                            if (isSafeExtractPath(outputDir, readable)) {
                                runCatching { readable.writeText(AxmlParser.parseCached(bytes)) }
                            }
                        }
                        count++
                        if (extractedFiles.size < 20) {
                            extractedFiles.add(entry.name)
                        }
                    }
                }
            }

            if (count == 0) {
                ToolResult(false, "未找到匹配的条目: $pattern")
            } else {
                val sb = StringBuilder()
                sb.appendLine("✅ 提取完成：$count 个文件")
                sb.appendLine("输出目录: ${outputDir.absolutePath}")
                sb.appendLine()
                sb.appendLine(L("已提取文件:", "Extracted files:"))
                extractedFiles.forEach { sb.appendLine("  • $it") }
                if (count > extractedFiles.size) {
                    sb.appendLine("  ... 及其他 ${count - extractedFiles.size} 个文件")
                }
                ToolResult(true, sb.toString())
            }
        } catch (e: Exception) {
            ToolResult(false, "提取失败: ${e.message}")
        }
    }

    /** 校验解压目标仍在 outDir 内，防止 Zip Slip 越界写入。 */
    private fun isSafeExtractPath(outDir: File, outFile: File): Boolean {
        return try {
            val root = outDir.canonicalPath
            outFile.canonicalPath.startsWith(root + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    private fun categorizeEntry(name: String): String {
        return when {
            name.endsWith(".dex") -> "DEX 字节码"
            name.startsWith("lib/") && name.endsWith(".so") -> "Native 库 (lib/)"
            name.startsWith("res/drawable") -> "图片资源 (drawable)"
            name.startsWith("res/mipmap") -> "应用图标 (mipmap)"
            name.startsWith("res/layout") -> "布局文件 (layout)"
            name.startsWith("res/xml") -> "XML 配置 (res/xml)"
            name.startsWith("res/raw") -> "原始资源 (res/raw)"
            name.startsWith("res/") -> "其他资源 (res/)"
            name.startsWith("assets/") -> "Assets 资源"
            name.startsWith("kotlin/") -> "Kotlin 元数据"
            name.startsWith("META-INF/") -> "签名与清单 (META-INF)"
            name == "AndroidManifest.xml" -> "AndroidManifest.xml"
            name == "resources.arsc" -> "资源索引表 (resources.arsc)"
            else -> L("其他文件", "Other files")
        }
    }

    private fun matchesFilter(name: String, filter: String): Boolean {
        return when (filter.lowercase()) {
            "all" -> true
            "drawable" -> name.startsWith("res/drawable")
            "mipmap" -> name.startsWith("res/mipmap")
            "layout" -> name.startsWith("res/layout")
            "raw" -> name.startsWith("res/raw")
            "xml" -> name.startsWith("res/xml") || name == "AndroidManifest.xml"
            "assets" -> name.startsWith("assets/")
            "lib", "so" -> name.startsWith("lib/") && name.endsWith(".so")
            "dex" -> name.endsWith(".dex")
            else -> name.contains(filter, ignoreCase = true)
        }
    }

    private fun patternToRegex(pattern: String): Regex {
        val escaped = Regex.escape(pattern)
            .replace("\\*", ".*")
            .replace("\\?", ".")
        return Regex("^$escaped$", RegexOption.IGNORE_CASE)
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        }
    }
}
