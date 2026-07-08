package com.apkagent.apktools.advanced

import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import com.apkagent.agent.strProp
import com.apkagent.agent.schemaObject
import com.apkagent.apktools.str
import com.apkagent.apktools.int
import com.apkagent.apktools.bool
import com.apkagent.apktools.AxmlParser
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 权限列表查看器：解析 APK 的 AndroidManifest.xml，提取所有声明的权限。
 *
 * 功能：
 * 1. 读取 APK 的 AndroidManifest.xml（二进制格式）
 * 2. 使用 AxmlParser 解析为可读 XML
 * 3. 提取所有 uses-permission 声明
 * 4. 分类显示：普通权限、危险权限、特殊权限
 * 5. 显示权限用途说明
 */
object PermissionsViewer : Tool {
    override val name = "apk_permissions"
    override val description = "查看 APK 声明的所有权限，分类显示普通/危险/特殊权限，并提供用途说明。"
    override val parameters = schemaObject(
        mapOf(
            "apk_path" to strProp("APK 文件路径")
        )
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val apkPath = args.str("apk_path") ?: return ToolResult(false, "缺少 apk_path")
        val apkFile = File(apkPath)
        if (!apkFile.exists()) return ToolResult(false, "文件不存在: $apkPath")

        return try {
            val permissions = extractPermissions(apkFile)
            if (permissions.isEmpty()) {
                return ToolResult(false, "APK 未声明任何权限")
            }

            formatPermissions(permissions)
        } catch (e: Exception) {
            ToolResult(false, "解析权限失败: ${e.message}")
        }
    }

    private fun extractPermissions(apkFile: File): List<PermissionInfo> {
        val permissions = mutableListOf<PermissionInfo>()
        ZipFile(apkFile).use { zip ->
            val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return emptyList()
            val manifestData = zip.getInputStream(manifestEntry).readBytes()

            val parser = AxmlParser(manifestData)
            val xml = parser.parse()

            // 解析 XML 提取权限
            val lines = xml.split("\n")
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.contains("uses-permission") && trimmed.contains("android:name=")) {
                    val nameMatch = Regex("""android:name="([^"]+)"""").find(trimmed)
                    if (nameMatch != null) {
                        val permName = nameMatch.groupValues[1]
                        val category = categorizePermission(permName)
                        val description = getPermissionDescription(permName)
                        permissions.add(PermissionInfo(permName, category, description))
                    }
                }
            }
        }
        return permissions
    }

    private fun categorizePermission(permission: String): PermissionCategory {
        return when {
            // 危险权限（需要运行时申请）
            permission.contains("CAMERA") ||
            permission.contains("RECORD_AUDIO") ||
            permission.contains("READ_CONTACTS") ||
            permission.contains("WRITE_CONTACTS") ||
            permission.contains("READ_CALENDAR") ||
            permission.contains("WRITE_CALENDAR") ||
            permission.contains("READ_CALL_LOG") ||
            permission.contains("WRITE_CALL_LOG") ||
            permission.contains("READ_PHONE_STATE") ||
            permission.contains("READ_PHONE_NUMBERS") ||
            permission.contains("CALL_PHONE") ||
            permission.contains("ANSWER_PHONE_CALLS") ||
            permission.contains("ADD_VOICEMAIL") ||
            permission.contains("USE_SIP") ||
            permission.contains("BODY_SENSORS") ||
            permission.contains("SEND_SMS") ||
            permission.contains("RECEIVE_SMS") ||
            permission.contains("READ_SMS") ||
            permission.contains("RECEIVE_WAP_PUSH") ||
            permission.contains("RECEIVE_MMS") ||
            permission.contains("ACCESS_FINE_LOCATION") ||
            permission.contains("ACCESS_COARSE_LOCATION") ||
            permission.contains("ACCESS_BACKGROUND_LOCATION") ||
            permission.contains("READ_EXTERNAL_STORAGE") ||
            permission.contains("WRITE_EXTERNAL_STORAGE") ||
            permission.contains("READ_MEDIA_IMAGES") ||
            permission.contains("READ_MEDIA_VIDEO") ||
            permission.contains("READ_MEDIA_AUDIO") -> PermissionCategory.DANGEROUS

            // 特殊权限（需要特殊申请）
            permission.contains("SYSTEM_ALERT_WINDOW") ||
            permission.contains("WRITE_SETTINGS") ||
            permission.contains("REQUEST_INSTALL_PACKAGES") ||
            permission.contains("MANAGE_EXTERNAL_STORAGE") ||
            permission.contains("QUERY_ALL_PACKAGES") ||
            permission.contains("PACKAGE_USAGE_STATS") ||
            permission.contains("REQUEST_DELETE_PACKAGES") ||
            permission.contains("ACCESS_NOTIFICATION_POLICY") ||
            permission.contains("BIND_NOTIFICATION_LISTENER_SERVICE") ||
            permission.contains("BIND_VPN_SERVICE") ||
            permission.contains("BIND_ACCESSIBILITY_SERVICE") -> PermissionCategory.SPECIAL

            // 普通权限
            else -> PermissionCategory.NORMAL
        }
    }

    private fun getPermissionDescription(permission: String): String {
        return when {
            permission.contains("CAMERA") -> "拍照和录像"
            permission.contains("RECORD_AUDIO") -> "录音"
            permission.contains("READ_CONTACTS") -> "读取联系人"
            permission.contains("WRITE_CONTACTS") -> "修改联系人"
            permission.contains("READ_CALENDAR") -> "读取日历"
            permission.contains("WRITE_CALENDAR") -> "修改日历"
            permission.contains("READ_CALL_LOG") -> "读取通话记录"
            permission.contains("WRITE_CALL_LOG") -> "修改通话记录"
            permission.contains("READ_PHONE_STATE") -> "读取手机状态"
            permission.contains("CALL_PHONE") -> "拨打电话"
            permission.contains("SEND_SMS") -> "发送短信"
            permission.contains("RECEIVE_SMS") -> "接收短信"
            permission.contains("READ_SMS") -> "读取短信"
            permission.contains("ACCESS_FINE_LOCATION") -> "精确位置（GPS）"
            permission.contains("ACCESS_COARSE_LOCATION") -> "粗略位置（网络）"
            permission.contains("READ_EXTERNAL_STORAGE") -> "读取存储"
            permission.contains("WRITE_EXTERNAL_STORAGE") -> "写入存储"
            permission.contains("INTERNET") -> "网络访问"
            permission.contains("ACCESS_NETWORK_STATE") -> "查看网络状态"
            permission.contains("ACCESS_WIFI_STATE") -> "查看 WiFi 状态"
            permission.contains("BLUETOOTH") -> "蓝牙"
            permission.contains("NFC") -> "NFC"
            permission.contains("VIBRATE") -> "振动"
            permission.contains("WAKE_LOCK") -> "防止休眠"
            permission.contains("SYSTEM_ALERT_WINDOW") -> "悬浮窗"
            permission.contains("WRITE_SETTINGS") -> "修改系统设置"
            permission.contains("REQUEST_INSTALL_PACKAGES") -> "安装应用"
            permission.contains("MANAGE_EXTERNAL_STORAGE") -> "管理所有文件"
            permission.contains("QUERY_ALL_PACKAGES") -> "查询所有应用"
            permission.contains("FOREGROUND_SERVICE") -> "前台服务"
            permission.contains("RECEIVE_BOOT_COMPLETED") -> "开机启动"
            permission.contains("FOREGROUND_SERVICE_") -> "前台服务类型"
            else -> permission.substringAfterLast(".").replace("_", " ").lowercase()
        }
    }

    private fun formatPermissions(permissions: List<PermissionInfo>): ToolResult {
        val sb = StringBuilder()
        sb.appendLine("🔐 APK 权限列表（${permissions.size} 个）：")
        sb.appendLine()

        // 按类别分组
        val grouped = permissions.groupBy { it.category }

        // 危险权限
        grouped[PermissionCategory.DANGEROUS]?.let { perms ->
            sb.appendLine("⚠️ 危险权限（需要运行时申请）- ${perms.size} 个：")
            perms.forEach { perm ->
                sb.appendLine("  • ${perm.name}")
                sb.appendLine("    ${perm.description}")
            }
            sb.appendLine()
        }

        // 特殊权限
        grouped[PermissionCategory.SPECIAL]?.let { perms ->
            sb.appendLine("🔧 特殊权限（需要特殊申请）- ${perms.size} 个：")
            perms.forEach { perm ->
                sb.appendLine("  • ${perm.name}")
                sb.appendLine("    ${perm.description}")
            }
            sb.appendLine()
        }

        // 普通权限
        grouped[PermissionCategory.NORMAL]?.let { perms ->
            sb.appendLine("✅ 普通权限 - ${perms.size} 个：")
            perms.forEach { perm ->
                sb.appendLine("  • ${perm.name}")
                sb.appendLine("    ${perm.description}")
            }
        }

        return ToolResult(true, sb.toString())
    }

    private enum class PermissionCategory {
        NORMAL, DANGEROUS, SPECIAL
    }

    private data class PermissionInfo(
        val name: String,
        val category: PermissionCategory,
        val description: String
    )
}
