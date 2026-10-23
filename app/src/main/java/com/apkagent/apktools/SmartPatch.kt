package com.apkagent.apktools

import com.apkagent.i18n.L

import com.apkagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 智能 Patch 库 — 一键应用常见逆向 Patch
 * 基于 smali 模式匹配，自动定位并修改关键代码
 */
object SmartPatch {

    data class PatchTemplate(
        val id: String,
        val name: String,
        val description: String,
        val category: String,  // signature / root / emulator / debug / misc
        val patterns: List<SmaliPattern>
    )

    data class SmaliPattern(
        val searchPattern: String,     // smali 代码片段或正则
        val replacement: String,       // 替换后的代码
        val matchType: MatchType = MatchType.CONTAINS,
        val description: String = ""
    )

    enum class MatchType { CONTAINS, REGEX, EXACT_LINE }

    // ──── 内置 Patch 模板库 ────

    val PATCHES = listOf(
        // ═══ 签名校验 ═══
        PatchTemplate(
            id = "signature_check",
            name = L("移除签名校验", "Remove Signature Verify"),
            description = L("绕过 APK 签名验证，允许重打包后正常运行", "Avoiding APK signature authentication, allowing repackaging to run properly"),
            category = "signature",
            patterns = listOf(
                SmaliPattern(
                    searchPattern = """invoke-virtual.*Landroid/content/pm/PackageManager;->getPackageInfo.*""",
                    replacement = """# [PATCHED] 签名校验已跳过
nop""",
                    description = L("PackageManager.getPackageInfo 签名校验", "PackageManager.getPackageInfo Signature Validation")
                ),
                SmaliPattern(
                    searchPattern = """invoke-virtual.*Ljava/security/Signature;->verify.*""",
                    replacement = """# [PATCHED] Signature.verify 已绕过
const/4 v0, 0x1
return v0""",
                    description = L("Signature.verify 校验", "Signature.verify")
                ),
                SmaliPattern(
                    searchPattern = """invoke-virtual.*Ljava/security/MessageDigest;->digest.*""",
                    replacement = """# [PATCHED] MessageDigest 已绕过
nop""",
                    description = L("MessageDigest 哈希校验", "Message Digest Hash.")
                )
            )
        ),

        // ═══ Root 检测 ═══
        PatchTemplate(
            id = "root_detection",
            name = L("移除 Root 检测", "Remove root detection"),
            description = L("绕过各种 Root 检测方法（SuperSU路径、su命令、RootBeer等）", "Around Root detection methods (SuperSU path, su command, RootBeer, etc.)"),
            category = "root",
            patterns = listOf(
                SmaliPattern(
                    searchPattern = """/su/bin/su""",
                    replacement = """/system/bin/true""",
                    description = L("su 二进制路径", "Su binary path")
                ),
                SmaliPattern(
                    searchPattern = """/system/app/Superuser.apk""",
                    replacement = """/dev/null""",
                    description = L("SuperSU APK 路径", "SuperSU AAK Path")
                ),
                SmaliPattern(
                    searchPattern = """invoke-virtual.*Ljava/lang/Runtime;->exec.*"su".*""",
                    replacement = """# [PATCHED] su 命令执行已禁用
const/4 v0, 0x0""",
                    description = "Runtime.exec su"
                ),
                SmaliPattern(
                    searchPattern = """invoke-virtual.*Ljava/io/File;->exists.*"/su".*""",
                    replacement = """# [PATCHED] /su 路径检测已禁用
const/4 v0, 0x0""",
                    description = L("/su 路径存在检测", "/su path presence detection")
                ),
                SmaliPattern(
                    searchPattern = """com.scottyab.rootbeer.RootBeer""",
                    replacement = """# [PATCHED] RootBeer 已禁用""",
                    description = L("RootBeer 库检测", "RootBeer Library Testing")
                )
            )
        ),

        // ═══ 模拟器检测 ═══
        PatchTemplate(
            id = "emulator_detection",
            name = L("移除模拟器检测", "Remove emulator detection"),
            description = L("绕过 Android 模拟器检测（Build属性、传感器、电话状态等）", "Around Android emulator detection (Build properties, sensors, telephone status, etc.)"),
            category = "emulator",
            patterns = listOf(
                SmaliPattern(
                    searchPattern = """sget-object.*Landroid/os/Build;->MODEL:Ljava/lang/String;""",
                    replacement = """# [PATCHED] Build.MODEL 返回真实设备
sget-object v0, Landroid/os/Build;->MODEL:Ljava/lang/String;""",
                    description = L("Build.MODEL 检测", "Build. MODEL Test")
                ),
                SmaliPattern(
                    searchPattern = """const-string.*"goldfish".""",
                    replacement = """const-string v0, "realdevice"""",
                    description = L("goldfish 内核检测", "Goldfish kernel detection")
                ),
                SmaliPattern(
                    searchPattern = """const-string.*"generic".""",
                    replacement = """const-string v0, "specific"""",
                    description = L("generic 设备检测", "Generic device testing")
                ),
                SmaliPattern(
                    searchPattern = """invoke-virtual.*Landroid/telephony/TelephonyManager;->getDeviceId.*""",
                    replacement = """# [PATCHED] getDeviceId 已禁用
const/4 v0, 0x0""",
                    description = "TelephonyManager.getDeviceId"
                )
            )
        ),

        // ═══ 调试限制 ═══
        PatchTemplate(
            id = "debug_restriction",
            name = L("解除调试限制", "Undo Debug Limit"),
            description = L("允许调试器附加、移除反调试检测", "Allow debugger attachment, remove anti-debugger detection"),
            category = "debug",
            patterns = listOf(
                SmaliPattern(
                    searchPattern = """invoke-virtual.*Landroid/os/Debug;->isDebuggerConnected.*""",
                    replacement = """# [PATCHED] isDebuggerConnected 返回 false
const/4 v0, 0x0""",
                    description = "Debug.isDebuggerConnected"
                ),
                SmaliPattern(
                    searchPattern = """invoke-static.*Landroid/os/Debug;->isDebuggerConnected.*""",
                    replacement = """# [PATCHED] isDebuggerConnected 返回 false
const/4 v0, 0x0""",
                    description = "Debug.isDebuggerConnected (static)"
                ),
                SmaliPattern(
                    searchPattern = """invoke-virtual.*android:debuggable.*""",
                    replacement = """# [PATCHED] debuggable check bypassed
const/4 v0, 0x1""",
                    description = L("debuggable 属性检测", "Debuggable attribute detection")
                )
            )
        ),

        // ═══ SSL Pinning ═══
        PatchTemplate(
            id = "ssl_pinning",
            name = L("绕过 SSL Pinning", "Around SSL Pinning"),
            description = L("禁用证书固定，允许中间人抓包", "Dis Whilst Certificate Fixed to Allow Intermediaries to Capture Packages"),
            category = "signature",
            patterns = listOf(
                SmaliPattern(
                    searchPattern = """invoke-virtual.*Lokhttp3/CertificatePinner;->check.*""",
                    replacement = """# [PATCHED] CertificatePinner.check 已绕过
return-void""",
                    description = "OkHttp CertificatePinner"
                ),
                SmaliPattern(
                    searchPattern = """invoke-virtual.*Ljavax/net/ssl/TrustManagerFactory;->getTrustManagers.*""",
                    replacement = """# [PATCHED] TrustManagerFactory 已绕过
nop""",
                    description = "TrustManagerFactory"
                )
            )
        ),

        // ═══ VIP/付费绕过 ═══
        PatchTemplate(
            id = "vip_bypass",
            name = L("VIP/付费绕过", "VIP/pay circumvention"),
            description = L("常见 VIP 验证绕过（返回 true/1）", "Common VIP verification bypass (return true/1)"),
            category = "misc",
            patterns = listOf(
                SmaliPattern(
                    searchPattern = """invoke-virtual.*isVip|isPremium|isPro|isPaid.*Z""",
                    replacement = """# [PATCHED] VIP 检查返回 true
const/4 v0, 0x1
return v0""",
                    description = L("isVip/isPremium/isPro 方法", "IsVip/isPremium/isPro Method")
                )
            )
        )
    )

    /**
     * 对工作区中的 smali 文件应用指定 Patch
     */
    suspend fun applyPatch(
        workspace: File,
        patchId: String,
        targetDir: String? = null
    ): PatchResult = withContext(Dispatchers.IO) {
        val template = PATCHES.find { it.id == patchId }
            ?: return@withContext PatchResult(false, "未知 Patch: $patchId\n可用: ${PATCHES.joinToString { it.id }}")

        Logger.i("Patch", "应用: ${template.name}")

        val smaliDir = if (targetDir != null) {
            File(targetDir)
        } else {
            // 自动查找 smali 目录
            workspace.listFiles()?.find { it.isDirectory && it.name.startsWith("smali") }
                ?: return@withContext PatchResult(false, L("未找到 smali 目录，请先 apk_unpack 解包", "Smali directory not found, please unpack first"))
        }

        if (!smaliDir.exists()) {
            return@withContext PatchResult(false, "smali 目录不存在: ${smaliDir.absolutePath}")
        }

        val results = mutableListOf<String>()
        var totalPatched = 0

        for (pattern in template.patterns) {
            var patternCount = 0
            smaliDir.walkTopDown()
                .filter { it.isFile && it.extension == "smali" }
                .forEach { file ->
                    try {
                        val content = file.readText()
                        if (content.contains(pattern.searchPattern)) {
                            val newContent = content.replace(
                                pattern.searchPattern,
                                pattern.replacement
                            )
                            file.writeText(newContent)
                            patternCount++
                            Logger.d("Patch", "匹配: ${file.name} ← ${pattern.description}")
                        }
                    } catch (e: Exception) {
                        Logger.w("Patch", "处理 ${file.name} 失败: ${e.message}")
                    }
                }
            if (patternCount > 0) {
                results.add("  ✅ ${pattern.description}: $patternCount 处")
                totalPatched += patternCount
            } else {
                results.add("  ⚠️ ${pattern.description}: 未找到匹配")
            }
        }

        val msg = buildString {
            appendLine("${template.name} — 扫描完成")
            appendLine("匹配 $totalPatched 处代码:")
            results.forEach { appendLine(it) }
            if (totalPatched > 0) {
                appendLine(L("\n下一步: apk_repack + apk_sign 重新打包签名", "Next: apk repack + apk sign"))
            }
        }

        PatchResult(totalPatched > 0, msg)
    }

    /**
     * 扫描工作区，列出可应用的 Patch
     */
    suspend fun scanAvailable(workspace: File): String = withContext(Dispatchers.IO) {
        val smaliDir = workspace.listFiles()?.find { it.isDirectory && it.name.startsWith("smali") }
            ?: return@withContext L("未找到 smali 目录，请先 apk_unpack 解包", "Smali directory not found, please unpack first")

        val sb = StringBuilder()
        sb.appendLine(L("=== 智能 Patch 库 ===\n", "It's the..."))

        for (template in PATCHES) {
            var matchCount = 0
            for (pattern in template.patterns) {
                smaliDir.walkTopDown()
                    .filter { it.isFile && it.extension == "smali" }
                    .forEach { file ->
                        try {
                            if (file.readText().contains(pattern.searchPattern)) matchCount++
                        } catch (_: Exception) {}
                    }
            }
            val icon = if (matchCount > 0) "🟢" else "⚪"
            sb.appendLine("$icon [${template.id}] ${template.name}")
            sb.appendLine("   ${template.description}")
            if (matchCount > 0) sb.appendLine("   → 发现 $matchCount 处可 Patch")
            sb.appendLine()
        }

        sb.appendLine(L("使用方法: smart_patch apply <patch_id>", "Usage method: smart pactapply<pact id>"))
        sb.toString()
    }

    data class PatchResult(val success: Boolean, val message: String)
}
