package com.apkagent.apktools.advanced

import com.apkagent.i18n.L
import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import com.apkagent.agent.boolProp
import com.apkagent.agent.strProp
import com.apkagent.agent.schemaObject
import com.apkagent.apktools.Sandbox
import com.apkagent.apktools.strNonBlank
import com.apkagent.apktools.bool
import com.apkagent.shizuku.ShizukuManager
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * APK 安装工具：通过 Shizuku 执行 pm install 安装单个 APK 或 Split APK Bundle。
 */
object ApkInstaller : Tool {
    override val name = "apk_install"
    override val description get() = L(
        "通过 Shizuku 安装 APK（支持单个 APK 或 Split APK 目录）。需要 Shizuku 已授权。",
        "Install APK via Shizuku (supports single APK or Split APK directory)."
    )
    override val parameters get() = schemaObject(
        mapOf(
            "apk_path" to strProp(L("APK 文件路径，或包含多个分片 APK 的目录路径（留空默认当前 APK）", "APK file path or Split APK directory path")),
            "allow_downgrade" to boolProp(L("是否允许降级安装（默认 true）", "Allow downgrade installation (default true)")),
            "grant_permissions" to boolProp(L("是否自动授予所有运行时权限（默认 false）", "Automatically grant all runtime permissions (default false)"))
        )
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPath = args.strNonBlank("apk_path")
        val target = when {
            rawPath != null -> Sandbox.resolve(File(rawPath), ctx.workspace)
            ctx.openApk != null -> ctx.openApk!!
            else -> File(ctx.workspace, "base.apk")
        }
        val allowDowngrade = args.bool("allow_downgrade") ?: true
        val grantPermissions = args.bool("grant_permissions") ?: false

        if (!ShizukuManager.isAuthorized()) {
            return ToolResult(false, L("❌ Shizuku 未授权，无法执行安装。请先在设置页授权 Shizuku。", "❌ Shizuku is not authorized."))
        }

        if (!target.exists()) return ToolResult(false, "文件或目录不存在: ${target.absolutePath}")

        return if (target.isDirectory) {
            installSplitApks(target, allowDowngrade, grantPermissions)
        } else {
            installSingleApk(target, allowDowngrade, grantPermissions)
        }
    }

    private fun installSingleApk(apkFile: File, allowDowngrade: Boolean, grantPermissions: Boolean): ToolResult {
        return try {
            val flags = buildFlags(allowDowngrade, grantPermissions)
            val tmpApk = "/data/local/tmp/apkagent_install_${System.currentTimeMillis()}.apk"
            ShizukuManager.copyFile(apkFile.absolutePath, tmpApk)
            ShizukuManager.chmod(tmpApk, "644")

            val cmd = "pm install $flags '$tmpApk' 2>&1"
            val (output, code) = ShizukuManager.execAndGetCode(cmd)
            ShizukuManager.deleteFile(tmpApk)

            if (code == 0 && output?.contains("Success") == true) {
                ToolResult(true, "✅ 安装成功: ${apkFile.name}\n$output")
            } else {
                ToolResult(false, "❌ 安装失败 (code=$code):\n$output")
            }
        } catch (e: Exception) {
            ToolResult(false, "安装异常: ${e.message}")
        }
    }

    private fun installSplitApks(splitDir: File, allowDowngrade: Boolean, grantPermissions: Boolean): ToolResult {
        return try {
            val apkFiles = splitDir.listFiles()?.filter { it.name.endsWith(".apk") } ?: emptyList()
            if (apkFiles.isEmpty()) {
                return ToolResult(false, "目录内未找到 APK 文件: ${splitDir.absolutePath}")
            }

            val flags = buildFlags(allowDowngrade, grantPermissions)
            val totalSize = apkFiles.sumOf { it.length() }

            val createCmd = "pm install-create $flags -S $totalSize 2>&1"
            val (createOutput, createCode) = ShizukuManager.execAndGetCode(createCmd)
            if (createCode != 0 || createOutput == null) {
                return ToolResult(false, "创建安装会话失败:\n$createOutput")
            }

            val sessionId = Regex("""\[(\d+)\]""").find(createOutput)?.groupValues?.get(1)
                ?: return ToolResult(false, "无法解析 Session ID:\n$createOutput")

            apkFiles.forEachIndexed { index, apk ->
                val writeCmd = "pm install-write -S ${apk.length()} $sessionId ${index}_${apk.name} '${apk.absolutePath}' 2>&1"
                val (writeOutput, writeCode) = ShizukuManager.execAndGetCode(writeCmd)
                if (writeCode != 0) {
                    ShizukuManager.execAndGet("pm install-abandon $sessionId")
                    return ToolResult(false, "写入分片失败 (${apk.name}):\n$writeOutput")
                }
            }

            val commitCmd = "pm install-commit $sessionId 2>&1"
            val (commitOutput, commitCode) = ShizukuManager.execAndGetCode(commitCmd)

            if (commitCode == 0 && commitOutput?.contains("Success") == true) {
                ToolResult(true, "✅ Split APK 安装成功（${apkFiles.size} 个分片）\n$commitOutput")
            } else {
                ToolResult(false, "❌ 提交安装失败:\n$commitOutput")
            }
        } catch (e: Exception) {
            ToolResult(false, "Split APK 安装异常: ${e.message}")
        }
    }

    private fun buildFlags(allowDowngrade: Boolean, grantPermissions: Boolean): String {
        val flags = mutableListOf("-r")
        if (allowDowngrade) flags.add("-d")
        if (grantPermissions) flags.add("-g")
        return flags.joinToString(" ")
    }
}
