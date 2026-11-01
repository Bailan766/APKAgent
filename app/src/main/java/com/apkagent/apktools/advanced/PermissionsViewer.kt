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
 * APK 权限详细分析工具。
 */
object PermissionsViewer : Tool {
    override val name = "apk_permissions"
    override val description get() = L(
        "详细分析 APK 请求的所有权限，包括权限等级（普通/危险/特殊）、中文说明、风险评估。",
        "Analyze all permissions requested by the APK, including risk levels, descriptions, and security assessment."
    )
    override val parameters get() = schemaObject(
        mapOf(
            "apk_path" to strProp(L("APK 文件路径（留空默认当前打开的 APK）", "APK file path (defaults to currently open APK)"))
        )
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPath = args.strNonBlank("apk_path")
        val apkFile = when {
            rawPath != null -> Sandbox.resolve(File(rawPath), ctx.workspace)
            ctx.openApk != null -> ctx.openApk!!
            else -> File(ctx.workspace, "base.apk")
        }
        if (!apkFile.exists() || !apkFile.isFile) {
            return ToolResult(false, "文件不存在: ${apkFile.absolutePath}")
        }

        return try {
            val manifestXml = extractManifest(apkFile)
            val permissions = parsePermissions(manifestXml)

            if (permissions.isEmpty()) {
                return ToolResult(true, L("该 APK 未声明任何权限", "This APK does not declare any permissions"))
            }

            val dangerous = permissions.filter { it.level == PermissionLevel.DANGEROUS }
            val special = permissions.filter { it.level == PermissionLevel.SPECIAL }
            val normal = permissions.filter { it.level == PermissionLevel.NORMAL }
            val unknown = permissions.filter { it.level == PermissionLevel.UNKNOWN }

            val sb = StringBuilder()
            sb.appendLine("🔐 APK 权限分析：${apkFile.name}")
            sb.appendLine("总计 ${permissions.size} 个权限")
            sb.appendLine()

            if (dangerous.isNotEmpty()) {
                sb.appendLine("⚠️ 危险权限（${dangerous.size} 个，需运行时授权）：")
                dangerous.forEach {
                    sb.appendLine("  • ${it.shortName}")
                    sb.appendLine("    ${it.description}")
                    if (it.risk != null) sb.appendLine("    ⚡ 风险: ${it.risk}")
                }
                sb.appendLine()
            }

            if (special.isNotEmpty()) {
                sb.appendLine("🔶 特殊权限（${special.size} 个，需手动设置授权）：")
                special.forEach {
                    sb.appendLine("  • ${it.shortName}")
                    sb.appendLine("    ${it.description}")
                    if (it.risk != null) sb.appendLine("    ⚡ 风险: ${it.risk}")
                }
                sb.appendLine()
            }

            if (normal.isNotEmpty()) {
                sb.appendLine("✅ 普通权限（${normal.size} 个，安装时自动授予）：")
                normal.forEach {
                    sb.appendLine("  • ${it.shortName} — ${it.description}")
                }
                sb.appendLine()
            }

            if (unknown.isNotEmpty()) {
                sb.appendLine("❓ 自定义/未知权限（${unknown.size} 个）：")
                unknown.forEach {
                    sb.appendLine("  • ${it.fullName}")
                }
                sb.appendLine()
            }

            val riskScore = dangerous.size * 10 + special.size * 15
            val riskLevel = when {
                riskScore >= 100 -> L("🔴 高风险（请求大量敏感权限）", "🔴 High Risk")
                riskScore >= 50 -> L("🟡 中等风险（请求部分敏感权限）", "🟡 Medium Risk")
                riskScore > 0 -> L("🟢 低风险（仅少量敏感权限）", "🟢 Low Risk")
                else -> L("⚪ 无风险（仅普通权限）", "⚪ Minimal Risk")
            }
            sb.appendLine("📊 综合评估: $riskLevel (风险分: $riskScore)")

            ToolResult(true, sb.toString())
        } catch (e: Exception) {
            ToolResult(false, "分析失败: ${e.message}")
        }
    }

    private fun extractManifest(apkFile: File): String {
        ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: throw IllegalStateException(L("APK 内未找到 AndroidManifest.xml", "AndroidManifest.xml not found in APK"))
            val bytes = zip.getInputStream(entry).readBytes()
            return AxmlParser.parseCached(bytes)
        }
    }

    private fun parsePermissions(manifestXml: String): List<PermissionInfo> {
        val regex = Regex("""<uses-permission(?:-sdk-23)?[^>]*?(?:android:)?name="([^"]+)"""")
        return regex.findAll(manifestXml)
            .map { it.groupValues[1] }
            .distinct()
            .map { getPermissionInfo(it) }
            .toList()
    }

    private fun getPermissionInfo(fullName: String): PermissionInfo {
        val shortName = fullName.removePrefix("android.permission.")
        return PERMISSION_DATABASE[shortName] ?: PermissionInfo(
            fullName = fullName,
            shortName = shortName,
            level = PermissionLevel.UNKNOWN,
            description = L("自定义或未知权限", "Custom or unknown permission"),
            risk = null
        )
    }

    private enum class PermissionLevel {
        NORMAL, DANGEROUS, SPECIAL, UNKNOWN
    }

    private data class PermissionInfo(
        val fullName: String,
        val shortName: String,
        val level: PermissionLevel,
        val description: String,
        val risk: String?
    )

    private val PERMISSION_DATABASE = mapOf(
        "READ_CALENDAR" to PermissionInfo("android.permission.READ_CALENDAR", "READ_CALENDAR", PermissionLevel.DANGEROUS, L("读取日历事件", "Read calendar events"), L("可能泄露个人日程安排", "May leak personal schedule")),
        "WRITE_CALENDAR" to PermissionInfo("android.permission.WRITE_CALENDAR", "WRITE_CALENDAR", PermissionLevel.DANGEROUS, L("添加或修改日历事件", "Modify calendar events"), null),
        "CAMERA" to PermissionInfo("android.permission.CAMERA", "CAMERA", PermissionLevel.DANGEROUS, L("拍摄照片和视频", "Take pictures and videos"), L("可能后台偷拍", "May capture photos in background")),
        "READ_CONTACTS" to PermissionInfo("android.permission.READ_CONTACTS", "READ_CONTACTS", PermissionLevel.DANGEROUS, L("读取联系人通讯录", "Read contacts"), L("可能上传通讯录到服务器", "May upload contacts")),
        "WRITE_CONTACTS" to PermissionInfo("android.permission.WRITE_CONTACTS", "WRITE_CONTACTS", PermissionLevel.DANGEROUS, L("修改联系人", "Modify contacts"), null),
        "GET_ACCOUNTS" to PermissionInfo("android.permission.GET_ACCOUNTS", "GET_ACCOUNTS", PermissionLevel.DANGEROUS, L("访问设备上的账户列表", "Access accounts on device"), null),
        "ACCESS_FINE_LOCATION" to PermissionInfo("android.permission.ACCESS_FINE_LOCATION", "ACCESS_FINE_LOCATION", PermissionLevel.DANGEROUS, L("访问精确位置（GPS）", "Access fine location (GPS)"), L("可实时追踪用户位置", "Can track user location")),
        "ACCESS_COARSE_LOCATION" to PermissionInfo("android.permission.ACCESS_COARSE_LOCATION", "ACCESS_COARSE_LOCATION", PermissionLevel.DANGEROUS, L("访问大致位置（基站/WiFi）", "Access coarse location"), null),
        "ACCESS_BACKGROUND_LOCATION" to PermissionInfo("android.permission.ACCESS_BACKGROUND_LOCATION", "ACCESS_BACKGROUND_LOCATION", PermissionLevel.DANGEROUS, L("后台持续访问位置", "Access location in background"), L("高风险：后台持续追踪", "High risk: continuous background tracking")),
        "RECORD_AUDIO" to PermissionInfo("android.permission.RECORD_AUDIO", "RECORD_AUDIO", PermissionLevel.DANGEROUS, L("录制音频", "Record audio"), L("可能后台窃听", "May record audio in background")),
        "READ_PHONE_STATE" to PermissionInfo("android.permission.READ_PHONE_STATE", "READ_PHONE_STATE", PermissionLevel.DANGEROUS, L("读取手机状态和身份（IMEI/IMSI）", "Read phone state and identity"), L("常用于设备指纹追踪", "Used for device fingerprinting")),
        "CALL_PHONE" to PermissionInfo("android.permission.CALL_PHONE", "CALL_PHONE", PermissionLevel.DANGEROUS, L("直接拨打电话", "Directly call phone numbers"), L("可能恶意拨打收费电话", "May dial premium numbers")),
        "READ_CALL_LOG" to PermissionInfo("android.permission.READ_CALL_LOG", "READ_CALL_LOG", PermissionLevel.DANGEROUS, L("读取通话记录", "Read call log"), L("泄露通话隐私", "Leaks call history")),
        "WRITE_CALL_LOG" to PermissionInfo("android.permission.WRITE_CALL_LOG", "WRITE_CALL_LOG", PermissionLevel.DANGEROUS, L("修改通话记录", "Write call log"), null),
        "SEND_SMS" to PermissionInfo("android.permission.SEND_SMS", "SEND_SMS", PermissionLevel.DANGEROUS, L("发送短信", "Send SMS"), L("高风险：可能恶意发送扣费短信", "High risk: may send paid SMS")),
        "RECEIVE_SMS" to PermissionInfo("android.permission.RECEIVE_SMS", "RECEIVE_SMS", PermissionLevel.DANGEROUS, L("接收短信", "Receive SMS"), L("可能拦截验证码短信", "May intercept OTP SMS")),
        "READ_SMS" to PermissionInfo("android.permission.READ_SMS", "READ_SMS", PermissionLevel.DANGEROUS, L("读取短信内容", "Read SMS"), L("高风险：可窃取验证码和隐私短信", "High risk: can steal OTPs")),
        "READ_EXTERNAL_STORAGE" to PermissionInfo("android.permission.READ_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", PermissionLevel.DANGEROUS, L("读取外部存储（相册/文件）", "Read external storage"), L("可访问所有照片和文件", "Can access photos and files")),
        "WRITE_EXTERNAL_STORAGE" to PermissionInfo("android.permission.WRITE_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", PermissionLevel.DANGEROUS, L("修改或删除外部存储", "Write external storage"), null),
        "READ_MEDIA_IMAGES" to PermissionInfo("android.permission.READ_MEDIA_IMAGES", "READ_MEDIA_IMAGES", PermissionLevel.DANGEROUS, L("读取照片和图片（Android 13+）", "Read media images"), null),
        "READ_MEDIA_VIDEO" to PermissionInfo("android.permission.READ_MEDIA_VIDEO", "READ_MEDIA_VIDEO", PermissionLevel.DANGEROUS, L("读取视频（Android 13+）", "Read media video"), null),
        "READ_MEDIA_AUDIO" to PermissionInfo("android.permission.READ_MEDIA_AUDIO", "READ_MEDIA_AUDIO", PermissionLevel.DANGEROUS, L("读取音频文件（Android 13+）", "Read media audio"), null),
        "SYSTEM_ALERT_WINDOW" to PermissionInfo("android.permission.SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW", PermissionLevel.SPECIAL, L("显示悬浮窗（在其他应用上层显示）", "Draw over other apps"), L("常用于钓鱼欺诈、遮挡界面", "Can be used for overlay phishing")),
        "WRITE_SETTINGS" to PermissionInfo("android.permission.WRITE_SETTINGS", "WRITE_SETTINGS", PermissionLevel.SPECIAL, L("修改系统设置", "Modify system settings"), null),
        "REQUEST_INSTALL_PACKAGES" to PermissionInfo("android.permission.REQUEST_INSTALL_PACKAGES", "REQUEST_INSTALL_PACKAGES", PermissionLevel.SPECIAL, L("请求安装未知来源应用", "Request install packages"), L("可能静默下载并诱导安装恶意软件", "May prompt installation of other APKs")),
        "MANAGE_EXTERNAL_STORAGE" to PermissionInfo("android.permission.MANAGE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", PermissionLevel.SPECIAL, L("管理所有文件（Android 11+ 所有文件访问权）", "Manage all files"), L("高风险：完全访问设备存储", "High risk: full storage access")),
        "PACKAGE_USAGE_STATS" to PermissionInfo("android.permission.PACKAGE_USAGE_STATS", "PACKAGE_USAGE_STATS", PermissionLevel.SPECIAL, L("查看应用使用情况统计", "Usage stats access"), L("可监控用户使用习惯", "Can monitor app usage")),
        "BIND_ACCESSIBILITY_SERVICE" to PermissionInfo("android.permission.BIND_ACCESSIBILITY_SERVICE", "BIND_ACCESSIBILITY_SERVICE", PermissionLevel.SPECIAL, L("绑定无障碍服务", "Bind accessibility service"), L("极高风险：可模拟点击、读取屏幕内容", "Very high risk: can read screen and simulate clicks")),
        "BIND_DEVICE_ADMIN" to PermissionInfo("android.permission.BIND_DEVICE_ADMIN", "BIND_DEVICE_ADMIN", PermissionLevel.SPECIAL, L("设备管理员权限", "Bind device admin"), L("极高风险：可锁定屏幕、擦除数据、阻止卸载", "Very high risk: device admin")),
        "INTERNET" to PermissionInfo("android.permission.INTERNET", "INTERNET", PermissionLevel.NORMAL, L("访问网络", "Internet access"), null),
        "ACCESS_NETWORK_STATE" to PermissionInfo("android.permission.ACCESS_NETWORK_STATE", "ACCESS_NETWORK_STATE", PermissionLevel.NORMAL, L("查看网络连接状态", "Access network state"), null),
        "ACCESS_WIFI_STATE" to PermissionInfo("android.permission.ACCESS_WIFI_STATE", "ACCESS_WIFI_STATE", PermissionLevel.NORMAL, L("查看 WiFi 连接状态", "Access WiFi state"), null),
        "CHANGE_WIFI_STATE" to PermissionInfo("android.permission.CHANGE_WIFI_STATE", "CHANGE_WIFI_STATE", PermissionLevel.NORMAL, L("更改 WiFi 状态", "Change WiFi state"), null),
        "BLUETOOTH" to PermissionInfo("android.permission.BLUETOOTH", "BLUETOOTH", PermissionLevel.NORMAL, L("连接蓝牙设备", "Bluetooth"), null),
        "BLUETOOTH_ADMIN" to PermissionInfo("android.permission.BLUETOOTH_ADMIN", "BLUETOOTH_ADMIN", PermissionLevel.NORMAL, L("管理蓝牙设置", "Bluetooth admin"), null),
        "VIBRATE" to PermissionInfo("android.permission.VIBRATE", "VIBRATE", PermissionLevel.NORMAL, L("控制振动", "Control vibration"), null),
        "WAKE_LOCK" to PermissionInfo("android.permission.WAKE_LOCK", "WAKE_LOCK", PermissionLevel.NORMAL, L("防止手机休眠", "Prevent device from sleeping"), null),
        "RECEIVE_BOOT_COMPLETED" to PermissionInfo("android.permission.RECEIVE_BOOT_COMPLETED", "RECEIVE_BOOT_COMPLETED", PermissionLevel.NORMAL, L("开机自启动", "Run at startup"), null),
        "FOREGROUND_SERVICE" to PermissionInfo("android.permission.FOREGROUND_SERVICE", "FOREGROUND_SERVICE", PermissionLevel.NORMAL, L("运行前台服务", "Run foreground service"), null),
        "POST_NOTIFICATIONS" to PermissionInfo("android.permission.POST_NOTIFICATIONS", "POST_NOTIFICATIONS", PermissionLevel.DANGEROUS, L("发送通知（Android 13+）", "Post notifications"), null),
        "QUERY_ALL_PACKAGES" to PermissionInfo("android.permission.QUERY_ALL_PACKAGES", "QUERY_ALL_PACKAGES", PermissionLevel.SPECIAL, L("查询设备上所有已安装应用", "Query all installed packages"), L("可获取完整应用列表用于画像分析", "Can enumerate all installed apps"))
    )
}
