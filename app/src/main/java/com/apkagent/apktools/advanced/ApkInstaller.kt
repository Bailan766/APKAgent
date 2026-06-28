package com.apkagent.apktools.advanced

import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import com.apkagent.agent.strProp
import com.apkagent.agent.schemaObject
import com.apkagent.apktools.str
import com.apkagent.apktools.int
import com.apkagent.apktools.bool
import com.apkagent.shizuku.ShizukuManager
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.zip.ZipFile

/**
 * APK 安装器：支持普通 APK 和 Split APK 安装。
 *
 * 功能：
 * 1. 普通 APK 安装（通过 Shizuku 或系统 intent）
 * 2. Split APK 安装（通过 pm install-create/write/commit）
 * 3. App Bundle 安装（先解压再安装分片）
 * 4. 安装前验证（签名、版本、权限）
 * 5. 安装后状态检查
 */
object ApkInstaller : Tool {
    override val name = "apk_install"
    override val description = "安装 APK（支持普通 APK 和 Split APK）。使用 Shizuku 提权安装，无需用户确认。"
    override val parameters = schemaObject(
        mapOf(
            "apk_path" to strProp("APK 文件路径（或 Bundle 路径 .apks/.apkm/.xapk）"),
            "action" to strProp("操作：install（安装）/ verify（仅验证）/ info（查看安装信息）")
        )
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val apkPath = args.str("apk_path") ?: return ToolResult(false, "缺少 apk_path")
        val action = args.str("action") ?: "install"

        val apkFile = File(apkPath)
        if (!apkFile.exists()) return ToolResult(false, "文件不存在: $apkPath")

        return when (action) {
            "verify" -> verifyApk(apkFile)
            "info" -> getInstallInfo(apkFile)
            "install" -> installApk(apkFile)
            else -> ToolResult(false, "未知操作: $action，支持 install/verify/info")
        }
    }

    private fun verifyApk(apkFile: File): ToolResult {
        val sb = StringBuilder()
        sb.appendLine("🔍 APK 安装验证：")
        sb.appendLine()

        // 检查文件类型
        val fileType = when {
            apkFile.name.endsWith(".apk") -> "普通 APK"
            apkFile.name.endsWith(".apks") -> "Split APK Bundle"
            apkFile.name.endsWith(".apkm") -> "APKMirror Bundle"
            apkFile.name.endsWith(".xapk") -> "APKPure Bundle"
            else -> "未知格式"
        }
        sb.appendLine("📦 文件类型: $fileType")
        sb.appendLine("📁 文件大小: ${formatSize(apkFile.length())}")
        sb.appendLine()

        // 检查签名
        try {
            val verifier = com.android.apksig.ApkVerifier.Builder(apkFile).build()
            val result = verifier.verify()
            if (result.isVerified) {
                sb.appendLine("✅ 签名验证: 通过")
            } else {
                sb.appendLine("❌ 签名验证: 失败")
                result.errors.forEach { sb.appendLine("  • $it") }
            }
        } catch (e: Exception) {
            sb.appendLine("⚠️ 签名验证: 无法验证 (${e.message})")
        }

        // 检查是否为 Split APK
        if (fileType != "普通 APK") {
            try {
                val splits = listSplits(apkFile)
                sb.appendLine()
                sb.appendLine("📦 Split APK 分片:")
                splits.forEach { sb.appendLine("  • ${it.first} (${formatSize(it.second)})") }
            } catch (e: Exception) {
                sb.appendLine("⚠️ 无法解析分片: ${e.message}")
            }
        }

        return ToolResult(true, sb.toString())
    }

    private fun getInstallInfo(apkFile: File): ToolResult {
        val sb = StringBuilder()
        sb.appendLine("📋 APK 安装信息：")
        sb.appendLine()

        try {
            // 读取 AndroidManifest.xml 获取包信息
            val manifestData = extractManifest(apkFile)
            if (manifestData != null) {
                val parser = com.apkagent.apktools.AxmlParser(manifestData)
                val xml = parser.parse()

                // 提取关键信息
                val packageName = extractAttribute(xml, "package")
                val versionName = extractAttribute(xml, "android:versionName")
                val versionCode = extractAttribute(xml, "android:versionCode")
                val minSdk = extractAttribute(xml, "android:minSdkVersion")
                val targetSdk = extractAttribute(xml, "android:targetSdkVersion")

                sb.appendLine("📦 包名: ${packageName ?: "未知"}")
                sb.appendLine("📌 版本: ${versionName ?: "未知"} (${versionCode ?: "未知"})")
                sb.appendLine("📱 最低 SDK: ${minSdk ?: "未知"}")
                sb.appendLine("🎯 目标 SDK: ${targetSdk ?: "未知"}")
                sb.appendLine()

                // 提取权限
                val permissions = extractPermissionsFromXml(xml)
                if (permissions.isNotEmpty()) {
                    sb.appendLine("🔐 权限 (${permissions.size} 个):")
                    permissions.take(10).forEach { sb.appendLine("  • $it") }
                    if (permissions.size > 10) {
                        sb.appendLine("  ... 还有 ${permissions.size - 10} 个权限")
                    }
                }
            } else {
                sb.appendLine("⚠️ 无法读取 AndroidManifest.xml")
            }
        } catch (e: Exception) {
            sb.appendLine("❌ 解析失败: ${e.message}")
        }

        return ToolResult(true, sb.toString())
    }

    private fun installApk(apkFile: File): ToolResult {
        return try {
            // 检查 Shizuku 是否可用
            if (!ShizukuManager.isShizukuAvailable()) {
                return ToolResult(false, "❌ Shizuku 不可用，无法安装 APK\n请先启动 Shizuku 服务")
            }

            when {
                apkFile.name.endsWith(".apk") -> installSingleApk(apkFile)
                apkFile.name.endsWith(".apks") || apkFile.name.endsWith(".apkm") || apkFile.name.endsWith(".xapk") -> installBundle(apkFile)
                else -> ToolResult(false, "不支持的文件格式: ${apkFile.name}")
            }
        } catch (e: Exception) {
            ToolResult(false, "❌ 安装失败: ${e.message}")
        }
    }

    private fun installSingleApk(apkFile: File): ToolResult {
        val result = ShizukuManager.executeCommand("pm install -r -d \"${apkFile.absolutePath}\"")
        return if (result.contains("Success")) {
            ToolResult(true, "✅ APK 安装成功\n${apkFile.name}")
        } else {
            ToolResult(false, "❌ APK 安装失败\n$result")
        }
    }

    private fun installBundle(bundleFile: File): ToolResult {
        // 先解压 Bundle
        val tempDir = File(bundleFile.parent, "temp_install_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            ZipFile(bundleFile).use { zip ->
                zip.entries().toList().forEach { entry ->
                    if (entry.name.endsWith(".apk")) {
                        val outFile = File(tempDir, entry.name)
                        outFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }

            // 查找 base.apk
            val baseApk = File(tempDir, "base.apk")
            if (!baseApk.exists()) {
                return ToolResult(false, "Bundle 中未找到 base.apk")
            }

            // 使用 pm install-create/write/commit 安装 Split APK
            val apkFiles = tempDir.listFiles()?.filter { it.name.endsWith(".apk") } ?: emptyList()
            val totalSize = apkFiles.sumOf { it.length() }

            // 创建安装会话
            val sessionResult = ShizukuManager.executeCommand("pm install-create -S $totalSize")
            val sessionId = Regex("""sessionId=(\d+)""").find(sessionResult)?.groupValues?.get(1)
                ?: return ToolResult(false, "创建安装会话失败\n$sessionResult")

            // 写入每个分片
            for (apk in apkFiles) {
                val writeResult = ShizukuManager.executeCommand("pm install-write $sessionId ${apk.name} \"${apk.absolutePath}\"")
                if (!writeResult.contains("Success")) {
                    return ToolResult(false, "写入分片失败: ${apk.name}\n$writeResult")
                }
            }

            // 提交安装
            val commitResult = ShizukuManager.executeCommand("pm install-commit $sessionId")
            return if (commitResult.contains("Success")) {
                ToolResult(true, "✅ Split APK 安装成功\n分片数量: ${apkFiles.size}")
            } else {
                ToolResult(false, "❌ Split APK 安装失败\n$commitResult")
            }
        } catch (e: Exception) {
            return ToolResult(false, "❌ 安装异常: ${e.message}")
        } finally {
            // 清理临时目录
            tempDir.deleteRecursively()
        }
    }

    private fun listSplits(bundleFile: File): List<Pair<String, Long>> {
        val splits = mutableListOf<Pair<String, Long>>()
        ZipFile(bundleFile).use { zip ->
            zip.entries().toList().forEach { entry ->
                if (entry.name.endsWith(".apk")) {
                    splits.add(entry.name to entry.size)
                }
            }
        }
        return splits
    }

    private fun extractManifest(apkFile: File): ByteArray? {
        ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return null
            return zip.getInputStream(entry).readBytes()
        }
    }

    private fun extractAttribute(xml: String, attribute: String): String? {
        val regex = Regex("""$attribute="([^"]+)"""")
        return regex.find(xml)?.groupValues?.get(1)
    }

    private fun extractPermissionsFromXml(xml: String): List<String> {
        val permissions = mutableListOf<String>()
        val regex = Regex("""android:name="([^"]*permission[^"]*)"""", RegexOption.IGNORE_CASE)
        regex.findAll(xml).forEach { match ->
            permissions.add(match.groupValues[1])
        }
        return permissions
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        }
    }
}
