package com.apkagent.apktools

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
        HIGH("高危", "🔴"),
        MEDIUM("中危", "🟡"),
        LOW("低危", "🔵"),
        INFO("信息", "⚪")
    }

    /**
     * 全面扫描 APK 安全风险
     */
    suspend fun scan(apkFile: File): ScanResult = withContext(Dispatchers.IO) {
        Logger.i("RiskScan", "开始扫描: ${apkFile.name}")

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
        Logger.i("RiskScan", "完成: ${risks.size} 项风险")

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
            Logger.w("RiskScan", "解析 Manifest 失败: ${e.message}")
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
            Logger.w("RiskScan", "提取 DEX 字符串失败: ${e.message}")
            ""
        }
    }

    private fun scanDangerousPermissions(manifest: String): List<RiskItem>? {
        if (manifest.isBlank()) return null
        val dangerousPerms = mapOf(
            "READ_CONTACTS" to "读取联系人",
            "WRITE_CONTACTS" to "修改联系人",
            "READ_CALL_LOG" to "读取通话记录",
            "WRITE_CALL_LOG" to "修改通话记录",
            "READ_SMS" to "读取短信",
            "SEND_SMS" to "发送短信",
            "RECORD_AUDIO" to "录音",
            "CAMERA" to "使用摄像头",
            "ACCESS_FINE_LOCATION" to "精确位置",
            "READ_PHONE_STATE" to "读取手机状态",
            "READ_EXTERNAL_STORAGE" to "读取外部存储",
            "WRITE_EXTERNAL_STORAGE" to "写入外部存储",
            "INSTALL_PACKAGES" to "安装应用",
            "DELETE_PACKAGES" to "删除应用",
            "SYSTEM_ALERT_WINDOW" to "悬浮窗",
            "WRITE_SETTINGS" to "修改系统设置"
        )
        val found = mutableListOf<RiskItem>()
        for ((perm, desc) in dangerousPerms) {
            if (manifest.contains(perm)) {
                found.add(RiskItem(
                    severity = if (perm in listOf("CAMERA", "RECORD_AUDIO", "READ_SMS", "SEND_SMS", "INSTALL_PACKAGES"))
                        Severity.HIGH else Severity.MEDIUM,
                    category = "权限",
                    title = "危险权限: $perm",
                    detail = desc,
                    recommendation = "确认是否必要，移除不需要的权限"
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
                category = "组件导出",
                title = "$count 个组件设为 exported=true",
                detail = "导出的组件可被其他应用调用，可能存在安全风险",
                recommendation = "仅导出必要的组件，添加权限保护"
            ))
        }
        // 检测 intent-filter（隐式导出）
        val intentFilterCount = Regex("""<intent-filter""").findAll(manifest).count()
        if (intentFilterCount > 3) {
            risks.add(RiskItem(
                severity = Severity.LOW,
                category = "组件导出",
                title = "$intentFilterCount 个 intent-filter",
                detail = "带有 intent-filter 的组件在旧版本 Android 上默认导出",
                recommendation = "显式设置 exported=false"
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
                title = "WebView 启用 JavaScript",
                detail = "启用 JS 可能导致 XSS 攻击",
                recommendation = "确保加载的内容可信，添加输入验证"
            ))
        }
        if (dexContent.contains("addJavascriptInterface")) {
            risks.add(RiskItem(
                severity = Severity.HIGH,
                category = "WebView",
                title = "WebView 暴露 Java 接口",
                detail = "addJavascriptInterface 可被网页调用原生方法",
                recommendation = "仅暴露必要方法，添加 @JavascriptInterface 注解"
            ))
        }
        if (dexContent.contains("setAllowFileAccess(true)") || dexContent.contains("setAllowFileAccess")) {
            risks.add(RiskItem(
                severity = Severity.HIGH,
                category = "WebView",
                title = "WebView 允许文件访问",
                detail = "网页可通过 file:// 协议读取本地文件",
                recommendation = "禁用文件访问: setAllowFileAccess(false)"
            ))
        }
        if (dexContent.contains("loadUrl(\"http://") || dexContent.contains("loadUrl(\"http:")) {
            risks.add(RiskItem(
                severity = Severity.MEDIUM,
                category = "WebView",
                title = "WebView 加载 HTTP 链接",
                detail = "明文 HTTP 传输，存在中间人攻击风险",
                recommendation = "使用 HTTPS 替代"
            ))
        }
        return risks.ifEmpty { null }
    }

    private fun scanHardcodedSecrets(dexContent: String): List<RiskItem>? {
        val risks = mutableListOf<RiskItem>()
        val patterns = mapOf(
            Regex("""(?:api[_-]?key|apikey)\s*[=:]\s*["'][A-Za-z0-9_\-]{16,}["']""", RegexOption.IGNORE_CASE) to "硬编码 API Key",
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
            RiskItem(Severity.HIGH, "安全配置", "应用可调试", "debuggable=true 允许调试器附加", "发布版本设为 false")
        } else null
    }

    private fun scanAllowBackup(manifest: String): RiskItem? {
        return if (manifest.contains("android:allowBackup=\"true\"") || !manifest.contains("allowBackup")) {
            RiskItem(Severity.LOW, "安全配置", "允许备份", "应用数据可通过 adb backup 导出", "设为 allowBackup=false")
        } else null
    }

    private fun scanCleartextTraffic(manifest: String): RiskItem? {
        return if (manifest.contains("usesCleartextTraffic=\"true\"")) {
            RiskItem(Severity.MEDIUM, "网络安全", "允许明文传输", "HTTP 流量未加密", "使用 HTTPS 替代")
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
                    risks.add(RiskItem(Severity.INFO, "代码保护", "检测到代码混淆", "约 ${(shortClassCount.toFloat() / totalClassCount * 100).toInt()}% 类名为短名称", "这是正常的安全措施"))
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
                        category = "原生库",
                        title = "包含 ${libs.size} 个原生库",
                        detail = "架构: ${arches.joinToString(", ")}",
                        recommendation = "检查原生库是否有已知漏洞"
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
            appendLine("║        APK 安全风险扫描报告              ║")
            appendLine("╚══════════════════════════════════════════╝")
            appendLine()
            appendLine("📦 文件: $apkName")
            appendLine("📊 风险统计:")
            appendLine("   🔴 高危: $high  🟡 中危: $medium  🔵 低危: $low  ⚪ 信息: $info")
            appendLine()

            val score = maxOf(0, 100 - high * 15 - medium * 8 - low * 3)
            val grade = when {
                score >= 90 -> "A (优秀)"
                score >= 75 -> "B (良好)"
                score >= 60 -> "C (一般)"
                score >= 40 -> "D (较差)"
                else -> "F (危险)"
            }
            appendLine("🛡️ 安全评分: $score/100 — $grade")
            appendLine()

            if (risks.isEmpty()) {
                appendLine("✅ 未发现明显安全风险")
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
