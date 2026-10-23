package com.apkagent.apktools

import com.apkagent.i18n.L

import com.apkagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * 安全风险扫描仪表盘
 * 扫描 APK 中的安全风险并生成报告
 */
object RiskScanner {

    data class RiskItem(
        val severity: Severity,   // HIGH / MEDIUM / LOW / INFO
        val category: String,
        val title: String,
        val detail: String,
        val recommendation: String = ""
    )

    enum class Severity(val label: String, val icon: String) {
        HIGH(L("高危", "High"), "🔴"),
        MEDIUM(L("中危", "Medium"), "🟡"),
        LOW(L("低危", "Low"), "🔵"),
        INFO(L("信息", "Info"), "⚪")
    }

    /**
     * 全面扫描 APK 安全风险
     */
    suspend fun scan(apkFile: File): ScanResult = withContext(Dispatchers.IO) {
        Logger.i("RiskScan", L("开始扫描: ${apkFile.name}", "Scanning: ${apkFile.name}"))

        val risks = mutableListOf<RiskItem>()
        val manifest = extractManifest(apkFile)
        val dexContent = extractDexStrings(apkFile)

        // 1. 危险权限检测
        scanDangerousPermissions(manifest)?.let { risks.addAll(it) }

        // 2. 导出组件检测
        scanExportedComponents(manifest)?.let { risks.addAll(it) }

        // 3. WebView 风险
        scanWebViewRisks(dexContent)?.let { risks.addAll(it) }

        // 4. 硬编码密钥
        scanHardcodedSecrets(dexContent)?.let { risks.addAll(it) }

        // 5. 证书 Pinning
        scanCertificatePinning(dexContent)?.let { risks.addAll(it) }

        // 6. debuggable
        scanDebuggable(manifest)?.let { risks.add(it) }

        // 7. allowBackup
        scanAllowBackup(manifest)?.let { risks.add(it) }

        // 8. usesCleartextTraffic
        scanCleartextTraffic(manifest)?.let { risks.add(it) }

        // 9. 代码混淆检测
        scanObfuscation(apkFile)?.let { risks.addAll(it) }

        // 10. 原生库检测
        scanNativeLibs(apkFile)?.let { risks.addAll(it) }

        val report = generateReport(apkFile.name, risks)
        Logger.i("RiskScan", L("完成: ${risks.size} 项风险", "Done: ${risks.size} risk(s)"))

        ScanResult(risks = risks, report = report)
    }

    private fun extractManifest(apkFile: File): String {
        return try {
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml") ?: return ""
                val bytes = zip.getInputStream(entry).readBytes()
                // 尝试解析二进制 XML（简化处理，返回可读部分）
                String(bytes, Charsets.UTF_8).replace("\u0000", "")
            }
        } catch (e: Exception) {
            Logger.w("RiskScan", L("解析 Manifest 失败: ${e.message}", "Manifest parse failed: ${e.message}"))
            ""
        }
    }

    private fun extractDexStrings(apkFile: File): String {
        return try {
            val sb = StringBuilder()
            ZipFile(apkFile).use { zip ->
                zip.entries().toList()
                    .filter { it.name.endsWith(".dex") }
                    .forEach { entry ->
                        val bytes = zip.getInputStream(entry).readBytes()
                        // 提取可打印字符串
                        val text = String(bytes, Charsets.US_ASCII)
                        sb.append(text.take(500_000))
                    }
            }
            sb.toString()
        } catch (e: Exception) {
            Logger.w("RiskScan", L("提取 DEX 字符串失败: ${e.message}", "Failed to extract DEX strings: ${e.message}"))
            ""
        }
    }

    private fun scanDangerousPermissions(manifest: String): List<RiskItem>? {
        if (manifest.isBlank()) return null
        val dangerousPerms = mapOf(
            "READ_CONTACTS" to L("读取联系人", "Read contacts"),
            "WRITE_CONTACTS" to L("修改联系人", "Modify contacts"),
            "READ_CALL_LOG" to L("读取通话记录", "Read call records"),
            "WRITE_CALL_LOG" to L("修改通话记录", "Modify Phone Record"),
            "READ_SMS" to L("读取短信", "Read SMS"),
            "SEND_SMS" to L("发送短信", "Send Text"),
            "RECORD_AUDIO" to L("录音", "Record audio"),
            "CAMERA" to L("使用摄像头", "Use camera"),
            "ACCESS_FINE_LOCATION" to L("精确位置", "Accurate Location"),
            "READ_PHONE_STATE" to L("读取手机状态", "Read Cellular Status"),
            "READ_EXTERNAL_STORAGE" to L("读取外部存储", "Read external storage"),
            "WRITE_EXTERNAL_STORAGE" to L("写入外部存储", "Write External Storage"),
            "INSTALL_PACKAGES" to L("安装应用", "Install apps"),
            "DELETE_PACKAGES" to L("删除应用", "Remove Application"),
            "SYSTEM_ALERT_WINDOW" to L("悬浮窗", "Overlay window"),
            "WRITE_SETTINGS" to L("修改系统设置", "Modify System Settings")
        )
        val found = mutableListOf<RiskItem>()
        for ((perm, desc) in dangerousPerms) {
            if (manifest.contains(perm)) {
                found.add(RiskItem(
                    severity = if (perm in listOf("CAMERA", "RECORD_AUDIO", "READ_SMS", "SEND_SMS", "INSTALL_PACKAGES"))
                        Severity.HIGH else Severity.MEDIUM,
                    category = L("权限", "Permission"),
                    title = "危险权限: $perm",
                    detail = desc,
                    recommendation = L("确认是否必要，移除不需要的权限", "Confirm if necessary, remove unneeded permissions")
                ))
            }
        }
        return found.ifEmpty { null }
    }

    private fun scanExportedComponents(manifest: String): List<RiskItem>? {
        if (manifest.isBlank()) return null
        val risks = mutableListOf<RiskItem>()
        val exportedPattern = Regex("""android:exported\s*=\s*"true"""")
        val count = exportedPattern.findAll(manifest).count()
        if (count > 0) {
            risks.add(RiskItem(
                severity = if (count > 5) Severity.HIGH else Severity.MEDIUM,
                category = L("组件导出", "Component Export"),
                title = "$count 个组件设为 exported=true",
                detail = L("导出的组件可被其他应用调用，可能存在安全风险", "The exported components can be called by other applications and may pose a security risk"),
                recommendation = L("仅导出必要的组件，添加权限保护", "Export only necessary components to add rights protection")
            ))
        }
        // 检测 intent-filter（隐式导出）
        val intentFilterCount = Regex("""<intent-filter""").findAll(manifest).count()
        if (intentFilterCount > 3) {
            risks.add(RiskItem(
                severity = Severity.LOW,
                category = L("组件导出", "Component Export"),
                title = "$intentFilterCount 个 intent-filter",
                detail = L("带有 intent-filter 的组件在旧版本 Android 上默认导出", "The component with inent-filter is exported by default on the old Android version"),
                recommendation = L("显式设置 exported=false", "Organisation")
            ))
        }
        return risks.ifEmpty { null }
    }

    private fun scanWebViewRisks(dexContent: String): List<RiskItem>? {
        val risks = mutableListOf<RiskItem>()
        if (dexContent.contains("setJavaScriptEnabled(true)") || dexContent.contains("setJavaScriptEnabled")) {
            risks.add(RiskItem(
                severity = Severity.MEDIUM,
                category = "WebView",
                title = L("WebView 启用 JavaScript", "WebView Enable JavaScript"),
                detail = L("启用 JS 可能导致 XSS 攻击", "Enable JS could cause an XSS attack"),
                recommendation = L("确保加载的内容可信，添加输入验证", "Ensure that the contents loaded are credible, add input authentication")
            ))
        }
        if (dexContent.contains("addJavascriptInterface")) {
            risks.add(RiskItem(
                severity = Severity.HIGH,
                category = "WebView",
                title = L("WebView 暴露 Java 接口", "WebView Exposure Java Interface"),
                detail = L("addJavascriptInterface 可被网页调用原生方法", "AddJavascriptInterface can be blew up on the page."),
                recommendation = L("仅暴露必要方法，添加 @JavascriptInterface 注解", "Only expose necessary methods, add @JavascriptInterface")
            ))
        }
        if (dexContent.contains("setAllowFileAccess(true)") || dexContent.contains("setAllowFileAccess")) {
            risks.add(RiskItem(
                severity = Severity.HIGH,
                category = "WebView",
                title = L("WebView 允许文件访问", "WebView allows file access"),
                detail = L("网页可通过 file:// 协议读取本地文件", "The web page can be used to access local files by file:// protocol"),
                recommendation = L("禁用文件访问: setAllowFileAccess(false)", "Disable file access: setAllowFileAccess")
            ))
        }
        if (dexContent.contains("loadUrl(\"http://") || dexContent.contains("loadUrl(\"http:")) {
            risks.add(RiskItem(
                severity = Severity.MEDIUM,
                category = "WebView",
                title = L("WebView 加载 HTTP 链接", "WebView Loading HTTP Link"),
                detail = L("明文 HTTP 传输，存在中间人攻击风险", "Express HTTP transmission with risk of attack by an intermediary"),
                recommendation = L("使用 HTTPS 替代", "Use HTTPS instead")
            ))
        }
        return risks.ifEmpty { null }
    }

    private fun scanHardcodedSecrets(dexContent: String): List<RiskItem>? {
        val risks = mutableListOf<RiskItem>()
        val patterns = mapOf(
            Regex("""(?:api[_-]?key|apikey)\s*[=:]\s*["'][A-Za-z0-9_\-]{16,}["']""", RegexOption.IGNORE_CASE) to L("硬编码 API Key", "Hardcoded API key"),
            Regex("""(?:secret|password|passwd|pwd)\s*[=:]\s*["'][^"']{6,}["']""", RegexOption.IGNORE_CASE) to "硬编码密码/Secret",
            Regex("""(?:token)\s*[=:]\s*["'][A-Za-z0-9_\-\.]{20,}["']""", RegexOption.IGNORE_CASE) to "硬编码 Token",
            Regex("""[A-Za-z0-9+/]{40,}={0,2}""") to "可能的 Base64 编码密钥"
        )
        for ((pattern, title) in patterns) {
            val matches = pattern.findAll(dexContent).take(5).toList()
            if (matches.isNotEmpty()) {
                risks.add(RiskItem(
                    severity = Severity.HIGH,
                    category = "硬编码密钥",
                    title = title,
                    detail = "发现 ${matches.size} 处匹配: ${matches.first().value.take(30)}...",
                    recommendation = "将密钥移至服务端或使用加密存储"
                ))
            }
        }
        return risks.ifEmpty { null }
    }

    private fun scanCertificatePinning(dexContent: String): List<RiskItem>? {
        val hasPinning = dexContent.contains("CertificatePinner") ||
                dexContent.contains("TrustManager") ||
                dexContent.contains("X509TrustManager") ||
                dexContent.contains("ssl.SSLContext")
        return if (!hasPinning) {
            listOf(RiskItem(
                severity = Severity.MEDIUM,
                category = "网络安全",
                title = "未检测到证书 Pinning",
                detail = "应用可能容易受到中间人攻击",
                recommendation = "实现 CertificatePinner 或 Network Security Config"
            ))
        } else null
    }

    private fun scanDebuggable(manifest: String): RiskItem? {
        return if (manifest.contains("android:debuggable=\"true\"")) {
            RiskItem(Severity.HIGH, L("安全配置", "Security Configuration"), L("应用可调试", "Apply debugging"), L("debuggable=true 允许调试器附加", "Debuggable=true allows debugger attachments"), L("发布版本设为 false", "Release as false"))
        } else null
    }

    private fun scanAllowBackup(manifest: String): RiskItem? {
        return if (manifest.contains("android:allowBackup=\"true\"") || !manifest.contains("allowBackup")) {
            RiskItem(Severity.LOW, L("安全配置", "Security Configuration"), L("允许备份", "Allow Backup"), L("应用数据可通过 adb backup 导出", "Apply data to export via adb backup"), L("设为 allowBackup=false", "Set as allowBackup = false"))
        } else null
    }

    private fun scanCleartextTraffic(manifest: String): RiskItem? {
        return if (manifest.contains("usesCleartextTraffic=\"true\"")) {
            RiskItem(Severity.MEDIUM, L("网络安全", "Cybersecurity"), L("允许明文传输", "Allow specified transfer"), L("HTTP 流量未加密", "HTTP traffic not encrypted"), L("使用 HTTPS 替代", "Use HTTPS instead"))
        } else null
    }

    private fun scanObfuscation(apkFile: File): List<RiskItem>? {
        return try {
            ZipFile(apkFile).use { zip ->
                val dexEntries = zip.entries().toList().filter { it.name.endsWith(".dex") }
                val sample = dexEntries.firstOrNull()?.let { entry ->
                    String(zip.getInputStream(entry).readBytes(), Charsets.US_ASCII).take(100_000)
                } ?: ""
                val risks = mutableListOf<RiskItem>()
                // 检查是否有大量短类名（混淆特征）
                val shortClassCount = Regex("""L[a-z]/[a-z];""").findAll(sample).count()
                val totalClassCount = Regex("""L[a-zA-Z]+(/[a-zA-Z]+)*;""").findAll(sample).count()
                if (totalClassCount > 0 && shortClassCount.toFloat() / totalClassCount > 0.3f) {
                    risks.add(RiskItem(Severity.INFO, L("代码保护", "Code protection"), L("检测到代码混淆", "We 50; code confusion detected."), "约 ${(shortClassCount.toFloat() / totalClassCount * 100).toInt()}% 类名为短名称", L("这是正常的安全措施", "It' Modalities are normal.")))
                }
                risks.ifEmpty { null }
            }
        } catch (_: Exception) { null }
    }

    private fun scanNativeLibs(apkFile: File): List<RiskItem>? {
        return try {
            ZipFile(apkFile).use { zip ->
                val libs = zip.entries().toList().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                if (libs.isNotEmpty()) {
                    val arches = libs.map { it.name.split("/")[1] }.distinct()
                    listOf(RiskItem(
                        severity = Severity.INFO,
                        category = L("原生库", "Original Treasury"),
                        title = "包含 ${libs.size} 个原生库",
                        detail = "架构: ${arches.joinToString(", ")}",
                        recommendation = L("检查原生库是否有已知漏洞", "Check if there are any known gaps in the original bank.")
                    ))
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun generateReport(apkName: String, risks: List<RiskItem>): String {
        val high = risks.count { it.severity == Severity.HIGH }
        val medium = risks.count { it.severity == Severity.MEDIUM }
        val low = risks.count { it.severity == Severity.LOW }
        val info = risks.count { it.severity == Severity.INFO }

        return buildString {
            appendLine("╔══════════════════════════════════════════╗")
            appendLine(L("║        APK 安全风险扫描报告              ║", "Worm AAPK Security Risk Scanning Report"))
            appendLine("╚══════════════════════════════════════════╝")
            appendLine()
            appendLine("📦 文件: $apkName")
            appendLine(L("📊 风险统计:", "Risk statistics:"))
            appendLine("   🔴 高危: $high  🟡 中危: $medium  🔵 低危: $low  ⚪ 信息: $info")
            appendLine()

            val score = maxOf(0, 100 - high * 15 - medium * 8 - low * 3)
            val grade = when {
                score >= 90 -> L("A (优秀)", "A (excellent)")
                score >= 75 -> L("B (良好)", "B (good)")
                score >= 60 -> L("C (一般)", "C (General)")
                score >= 40 -> L("D (较差)", "D (poor)")
                else -> L("F (危险)", "F (dangerous)")
            }
            appendLine("🛡️ 安全评分: $score/100 — $grade")
            appendLine()

            if (risks.isEmpty()) {
                appendLine(L("✅ 未发现明显安全风险", "✅ No obvious security risks found"))
                return@buildString
            }

            // 按严重程度分组输出
            for (severity in Severity.entries) {
                val group = risks.filter { it.severity == severity }
                if (group.isEmpty()) continue
                appendLine("━━━ ${severity.icon} ${severity.label} (${group.size}) ━━━")
                for (risk in group) {
                    appendLine("  [${risk.category}] ${risk.title}")
                    appendLine("    ${risk.detail}")
                    if (risk.recommendation.isNotEmpty()) {
                        appendLine("    💡 建议: ${risk.recommendation}")
                    }
                    appendLine()
                }
            }
        }
    }

    data class ScanResult(
        val risks: List<RiskItem>,
        val report: String
    )
}
