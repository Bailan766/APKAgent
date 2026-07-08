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
import java.util.zip.ZipFile

/**
 * 资源提取/替换工具：从 APK 中提取或替换资源文件。
 *
 * 功能：
 * 1. 列出 APK 内所有资源文件
 * 2. 提取指定资源到本地目录
 * 3. 替换 APK 内的资源文件
 * 4. 支持按类型过滤（drawable/layout/xml/anim/color 等）
 * 5. 支持批量提取
 */
object ResourceExtractor : Tool {
    override val name = "apk_resources"
    override val description = "提取或替换 APK 内的资源文件（图标、布局、XML 等）。"
    override val parameters = schemaObject(
        mapOf(
            "apk_path" to strProp("APK 文件路径"),
            "action" to strProp("操作：list（列出资源）/ extract（提取）/ replace（替换）"),
            "output_dir" to strProp("输出目录（extract 时需要）"),
            "resource_path" to strProp("资源路径（如 res/drawable/icon.png）"),
            "resource_type" to strProp("资源类型过滤（如 drawable/layout/xml/anim/color 等）"),
            "new_file_path" to strProp("替换文件路径（replace 时需要）")
        )
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val apkPath = args.str("apk_path") ?: return ToolResult(false, "缺少 apk_path")
        val action = args.str("action") ?: "list"
        val outputDir = args.str("output_dir")
        val resourcePath = args.str("resource_path")
        val resourceType = args.str("resource_type")
        val newFilePath = args.str("new_file_path")

        val apkFile = File(apkPath)
        if (!apkFile.exists()) return ToolResult(false, "文件不存在: $apkPath")

        return when (action) {
            "list" -> listResources(apkFile, resourceType)
            "extract" -> {
                if (outputDir == null) return ToolResult(false, "extract 操作需要 output_dir 参数")
                extractResources(apkFile, File(outputDir), resourcePath, resourceType)
            }
            "replace" -> {
                if (resourcePath == null || newFilePath == null) {
                    return ToolResult(false, "replace 操作需要 resource_path 和 new_file_path 参数")
                }
                replaceResource(apkFile, resourcePath, File(newFilePath))
            }
            else -> ToolResult(false, "未知操作: $action，支持 list/extract/replace")
        }
    }

    private fun listResources(apkFile: File, resourceType: String?): ToolResult {
        return try {
            val resources = mutableListOf<ResourceInfo>()
            ZipFile(apkFile).use { zip ->
                zip.entries().toList().forEach { entry ->
                    if (entry.name.startsWith("res/") || entry.name.startsWith("assets/")) {
                        val type = entry.name.substringBefore("/").substringAfter("res/")
                        val size = entry.size
                        resources.add(ResourceInfo(entry.name, type, size, entry.compressedSize))
                    }
                }
            }

            val filtered = if (resourceType != null) {
                resources.filter { it.type.contains(resourceType, ignoreCase = true) }
            } else {
                resources
            }

            val sb = StringBuilder()
            sb.appendLine("📁 APK 资源列表（${filtered.size} 个）：")
            sb.appendLine()

            // 按类型分组
            val grouped = filtered.groupBy { it.type }
            grouped.forEach { (type, resList) ->
                sb.appendLine("📂 $type (${resList.size} 个):")
                resList.sortedBy { it.name }.forEach { res ->
                    val sizeStr = formatSize(res.size)
                    val compressed = if (res.compressedSize < res.size) {
                        " (压缩: ${formatSize(res.compressedSize)})"
                    } else ""
                    sb.appendLine("  • ${res.name}$compressed")
                }
                sb.appendLine()
            }

            ToolResult(true, sb.toString())
        } catch (e: Exception) {
            ToolResult(false, "列出资源失败: ${e.message}")
        }
    }

    private fun extractResources(apkFile: File, outputDir: File, resourcePath: String?, resourceType: String?): ToolResult {
        return try {
            outputDir.mkdirs()
            var count = 0
            var totalSize = 0L

            ZipFile(apkFile).use { zip ->
                zip.entries().toList().forEach { entry ->
                    val shouldExtract = when {
                        resourcePath != null -> entry.name.contains(resourcePath)
                        resourceType != null -> entry.name.startsWith("res/") &&
                            entry.name.substringAfter("res/").substringBefore("/").contains(resourceType, ignoreCase = true)
                        else -> entry.name.startsWith("res/") || entry.name.startsWith("assets/")
                    }

                    if (shouldExtract) {
                        val outFile = File(outputDir, entry.name)
                        outFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        count++
                        totalSize += entry.size
                    }
                }
            }

            if (count == 0) {
                ToolResult(false, "未找到匹配的资源")
            } else {
                ToolResult(true, "✅ 提取完成\n资源数量: $count\n总大小: ${formatSize(totalSize)}\n输出目录: ${outputDir.absolutePath}")
            }
        } catch (e: Exception) {
            ToolResult(false, "提取失败: ${e.message}")
        }
    }

    private fun replaceResource(apkFile: File, resourcePath: String, newFile: File): ToolResult {
        if (!newFile.exists()) return ToolResult(false, "替换文件不存在: ${newFile.absolutePath}")

        return try {
            // 创建临时文件
            val tempApk = File(apkFile.parent, "${apkFile.nameWithoutExtension}_temp.apk")
            var replaced = false

            ZipFile(apkFile).use { zip ->
                java.util.zip.ZipOutputStream(tempApk.outputStream()).use { zos ->
                    zip.entries().toList().forEach { entry ->
                        if (entry.name == resourcePath) {
                            // 替换资源
                            zos.putNextEntry(java.util.zip.ZipEntry(entry.name))
                            newFile.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                            replaced = true
                        } else {
                            // 保留原文件
                            zos.putNextEntry(java.util.zip.ZipEntry(entry.name))
                            zip.getInputStream(entry).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }

            if (!replaced) {
                tempApk.delete()
                return ToolResult(false, "APK 中未找到资源: $resourcePath")
            }

            // 替换原文件
            apkFile.delete()
            tempApk.renameTo(apkFile)

            ToolResult(true, "✅ 资源替换成功\n资源: $resourcePath\n新文件: ${newFile.name}")
        } catch (e: Exception) {
            ToolResult(false, "替换失败: ${e.message}")
        }
    }

    private data class ResourceInfo(
        val name: String,
        val type: String,
        val size: Long,
        val compressedSize: Long
    )

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        }
    }
}
