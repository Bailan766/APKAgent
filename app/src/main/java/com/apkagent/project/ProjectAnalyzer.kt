package com.apkagent.project

import com.apkagent.i18n.L
import com.apkagent.apktools.AxmlParser
import com.apkagent.apktools.DexParser
import com.apkagent.apktools.SignatureReader
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

object ProjectAnalyzer {

    fun analyze(project: ReverseProject): ProjectAnalysisSummary = analyzeQuick(project)

    fun analyzeQuick(project: ReverseProject): ProjectAnalysisSummary {
        val apk = File(project.importedApkPath)
        val manifestXml = runCatching { readManifest(apk) }.getOrDefault("")
        val packageName = extractAttr(manifestXml, "package") ?: project.packageName
        val versionName = extractAttr(manifestXml, "versionName") ?: project.versionName
        val versionCode = extractAttr(manifestXml, "versionCode")?.toLongOrNull() ?: project.versionCode
        val minSdk = extractUsesSdk(manifestXml, "minSdkVersion")
        val targetSdk = extractUsesSdk(manifestXml, "targetSdkVersion")
        val permissions = extractTagValues(manifestXml, "uses-permission", "name")
        val activities = extractTagValues(manifestXml, "activity", "name")
        val services = extractTagValues(manifestXml, "service", "name")
        val receivers = extractTagValues(manifestXml, "receiver", "name")
        val providers = extractTagValues(manifestXml, "provider", "name")
        val applicationLabel = extractAttr(manifestXml, "label")

        val dexEntries = mutableListOf<String>()
        val nativeLibs = mutableListOf<String>()
        val assetEntries = mutableListOf<String>()

        ZipFile(apk).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                when {
                    entry.name.endsWith(".dex", ignoreCase = true) -> dexEntries += entry.name
                    entry.name.startsWith("lib/") && entry.name.endsWith(".so", ignoreCase = true) -> nativeLibs += entry.name
                    entry.name.startsWith("assets/") && !entry.isDirectory -> assetEntries += entry.name
                }
            }
        }

        val packerResult = runCatching { ProjectIntelligence.detectPacker(apk) }.getOrNull()
        val dexClasses = runCatching {
            ZipFile(apk).use { zip ->
                val firstDex = zip.getEntry("classes.dex") ?: return@use emptyList<String>()
                val bytes = zip.getInputStream(firstDex).use { it.readBytes() }
                DexParser.parseCached(bytes).classes
            }
        }.getOrDefault(emptyList())

        val techStack = runCatching { ProjectIntelligence.detectTechStack(apk, dexClasses) }
            .getOrDefault(ProjectIntelligence.TechStackResult(emptyList(), emptyList()))

        val certSummary = runCatching { SignatureReader.read(apk) }.getOrDefault("")
        val riskSummary = buildRiskSummary(
            permissions = permissions,
            dexEntries = dexEntries,
            nativeLibs = nativeLibs,
            assetEntries = assetEntries,
            manifestXml = manifestXml,
            packerResult = packerResult,
            techStack = techStack
        )

        return ProjectAnalysisSummary(
            projectId = project.id,
            projectName = project.name,
            apkName = apk.name,
            apkSize = apk.length(),
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            applicationLabel = applicationLabel,
            permissions = permissions,
            activities = activities,
            services = services,
            receivers = receivers,
            providers = providers,
            dexEntries = dexEntries,
            nativeLibs = nativeLibs,
            assetEntries = assetEntries.take(40),
            certificateSummary = certSummary.take(4000),
            riskSummary = riskSummary,
            packers = packerResult?.detectedPackers ?: emptyList(),
            frameworks = techStack.frameworks,
            detectedSdks = techStack.sdks
        )
    }

    private fun readManifest(apk: File): String {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return ""
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            return AxmlParser(bytes).parse()
        }
    }

    private fun extractAttr(xml: String, attr: String): String? {
        val regex = Regex("""$attr\s*=\s*"([^"]+)""")
        return regex.find(xml)?.groupValues?.getOrNull(1)
    }

    private fun extractUsesSdk(xml: String, attr: String): String? {
        val usesSdk = Regex("""<uses-sdk[^>]*>""").find(xml)?.value ?: return null
        val regex = Regex("""$attr\s*=\s*"([^"]+)""")
        return regex.find(usesSdk)?.groupValues?.getOrNull(1)
    }

    private fun extractTagValues(xml: String, tag: String, attr: String): List<String> {
        val tagRegex = Regex("""<$tag\b[^>]*>""")
        val attrRegex = Regex("""(?:android:)?$attr\s*=\s*"([^"]+)""")
        return tagRegex.findAll(xml)
            .mapNotNull { match -> attrRegex.find(match.value)?.groupValues?.getOrNull(1) }
            .distinct()
            .sorted()
            .toList()
    }

    private fun buildRiskSummary(
        permissions: List<String>,
        dexEntries: List<String>,
        nativeLibs: List<String>,
        assetEntries: List<String>,
        manifestXml: String,
        packerResult: ProjectIntelligence.PackerResult? = null,
        techStack: ProjectIntelligence.TechStackResult? = null
    ): String {
        val risks = mutableListOf<String>()

        if (packerResult != null && packerResult.isPacked) {
            val names = if (packerResult.detectedPackers.isNotEmpty()) packerResult.detectedPackers.joinToString() else "未知厂商加壳"
            risks += "⚠️ 加壳防护: 检测到 [$names] (保护分: ${packerResult.protectionScore})"
        }

        if (techStack != null && techStack.frameworks.isNotEmpty()) {
            risks += "技术栈框架: ${techStack.frameworks.joinToString()}"
        }

        if (techStack != null && techStack.sdks.isNotEmpty()) {
            risks += "常用 SDK: ${techStack.sdks.joinToString()}"
        }

        val highRiskPermissions = permissions.filter {
            it.contains("SEND_SMS") ||
                it.contains("RECEIVE_SMS") ||
                it.contains("READ_SMS") ||
                it.contains("QUERY_ALL_PACKAGES") ||
                it.contains("REQUEST_INSTALL_PACKAGES") ||
                it.contains("SYSTEM_ALERT_WINDOW") ||
                it.contains("READ_PHONE_STATE") ||
                it.contains("BIND_ACCESSIBILITY_SERVICE")
        }
        if (highRiskPermissions.isNotEmpty()) {
            risks += "高危权限: ${highRiskPermissions.joinToString()}"
        }
        if (dexEntries.size > 2) {
            risks += "存在多 DEX (${dexEntries.size})，可能需要分阶段分析"
        }
        if (nativeLibs.isNotEmpty()) {
            risks += "存在 Native so (${nativeLibs.size})，部分逻辑可能在 JNI 层"
        }
        val lowerXml = manifestXml.lowercase(Locale.getDefault())
        if (lowerXml.contains("accessibilityservice")) {
            risks += L("Manifest 含无障碍服务声明", "Manifast Statement of Services Including Accessibility")
        }
        if (assetEntries.any { it.contains("frida", ignoreCase = true) || it.contains("xposed", ignoreCase = true) }) {
            risks += L("assets 中出现 frida/xposed 关键字", "Assets appear Frida/xposed keywords")
        }
        if (risks.isEmpty()) risks += L("未发现明显高危特征，建议继续查看权限、DEX 和证书信息", "No visible high-risk characteristics were found, and it is recommended that access, DEX and certificate information be continued")
        return risks.joinToString("\n")
    }

    fun intelligenceHooks(): List<String> = listOf(
        "string_index",
        "url_token_extraction",
        "sdk_fingerprint",
        "native_entry_scan",
        "anti_tamper_detection"
    )
}
