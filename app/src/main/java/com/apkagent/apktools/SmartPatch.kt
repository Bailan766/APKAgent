package com.apkagent.apktools

import com.apkagent.i18n.L
import com.apkagent.agent.*
import com.apkagent.apktools.smali.SmaliEngine
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 智能 Patch 模板系统。
 * 预设常见逆向修改场景，一键应用。
 *
 * 修复说明：
 * 1. 修复原实现将正则表达式模式当作字面量字符串调用 String.contains/replace（导致 0 命中）的严重 Bug。
 * 2. 修复将 invoke-* 直接替换为 nop 后遗留悬空 move-result* 导致 ART VerifyError 崩溃的 Bug：
 *    自动识别紧随其后的 move-result vX 并替换为安全的常量赋值（如 const/4 vX, 0x0 或 0x1）。
 * 3. 修复 const-string 替换时硬编码 v0 覆盖无关寄存器的 Bug，改为保留原寄存器 $1。
 * 4. 支持递归查找工作区内所有 .smali 文件；若尚未反编译则自动从当前打开的 APK 反编译。
 */
object SmartPatch {

    data class PatchTemplate(
        val id: String,
        val name: String,
        val category: String,
        val description: String,
        val patterns: List<PatchPattern>
    )

    data class PatchPattern(
        val type: PatternType,
        val target: String,
        val searchRegex: Regex,
        val description: String,
        val replacementBuilder: (MatchResult) -> String
    )

    enum class PatternType {
        SMALI_INSTRUCTION_BLOCK,
        MANIFEST_EDIT
    }

    private fun invokeAndMoveResultRegex(invokePattern: String): Regex =
        Regex(
            """([ \t]*$invokePattern[^\r\n]*)(?:\r?\n[ \t]*move-result(?:-object|-wide)?\s+([vp]\d+))?""",
            RegexOption.IGNORE_CASE
        )

    val TEMPLATES = listOf(
        // ── 签名校验绕过 ──
        PatchTemplate(
            id = "bypass_signature",
            name = L("绕过签名校验", "Bypass Signature Verification"),
            category = L("安全绕过", "Security Bypass"),
            description = L("检测并禁用应用内的签名校验逻辑，允许重打包后正常运行", "Detect and disable signature check logic inside the app"),
            patterns = listOf(
                PatchPattern(
                    type = PatternType.SMALI_INSTRUCTION_BLOCK,
                    target = "Signature.hashCode / equals",
                    searchRegex = invokeAndMoveResultRegex("""invoke-virtual\s+\{[^}]+\},\s*Landroid/content/pm/Signature;->(?:equals|hashCode)"""),
                    description = L("强制签名 equals/hashCode 校验通过", "Force Signature equals/hashCode check to pass")
                ) { mr ->
                    val reg = mr.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
                    if (reg != null) "    const/4 $reg, 0x1" else "    nop"
                },
                PatchPattern(
                    type = PatternType.SMALI_INSTRUCTION_BLOCK,
                    target = "SigningInfo.hasSignature",
                    searchRegex = invokeAndMoveResultRegex("""invoke-virtual\s+\{[^}]+\},\s*Landroid/content/pm/SigningInfo;->has(?:Signature|MultipleSigners)"""),
                    description = L("强制 SigningInfo 签名校验返回 true", "Force SigningInfo signature check to return true")
                ) { mr ->
                    val reg = mr.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
                    if (reg != null) "    const/4 $reg, 0x1" else "    nop"
                }
            )
        ),

        // ── Root 检测绕过 ──
        PatchTemplate(
            id = "bypass_root",
            name = L("绕过 Root 检测", "Bypass Root Detection"),
            category = L("安全绕过", "Security Bypass"),
            description = L("禁用常见的 Root 检测方法（su 二进制、Magisk、RootBeer 等）", "Disable common Root detection methods (su binary, Magisk, RootBeer, etc.)"),
            patterns = listOf(
                PatchPattern(
                    type = PatternType.SMALI_INSTRUCTION_BLOCK,
                    target = "su binary path",
                    searchRegex = Regex("""([ \t]*const-string(?:/jumbo)?\s+([vp]\d+),\s*)"(?:/system/bin/su|/system/xbin/su|/sbin/su|/data/local/xbin/su|/data/local/bin/su|/su/bin/su)"""", RegexOption.IGNORE_CASE),
                    description = L("替换 su 路径检测为不存在的路径", "Replace su path check with non-existent path")
                ) { mr ->
                    "${mr.groupValues[1]}\"/nonexistent/su_disabled\""
                },
                PatchPattern(
                    type = PatternType.SMALI_INSTRUCTION_BLOCK,
                    target = "Root check method",
                    searchRegex = invokeAndMoveResultRegex("""invoke-(?:virtual|direct|static)\s+\{[^}]*\},\s*L[^;]+;->(?:isRooted|isDeviceRooted|checkRoot|detectRoot)\([^)]*\)Z"""),
                    description = L("Root 检测方法直接返回 false", "Make Root check method return false")
                ) { mr ->
                    val reg = mr.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
                    if (reg != null) "    const/4 $reg, 0x0" else mr.value
                }
            )
        ),

        // ── 调试检测绕过 ──
        PatchTemplate(
            id = "bypass_debug",
            name = L("绕过调试检测", "Bypass Debug Detection"),
            category = L("安全绕过", "Security Bypass"),
            description = L("禁用 isDebuggerConnected、TracerPid 等反调试检测", "Disable anti-debugging checks like isDebuggerConnected and TracerPid"),
            patterns = listOf(
                PatchPattern(
                    type = PatternType.SMALI_INSTRUCTION_BLOCK,
                    target = "Debug.isDebuggerConnected",
                    searchRegex = invokeAndMoveResultRegex("""invoke-static\s+\{\},\s*Landroid/os/Debug;->isDebuggerConnected\(\)Z"""),
                    description = L("Debug.isDebuggerConnected() 恒返回 false", "Force Debug.isDebuggerConnected() to return false")
                ) { mr ->
                    val reg = mr.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
                    if (reg != null) "    const/4 $reg, 0x0" else "    nop"
                },
                PatchPattern(
                    type = PatternType.SMALI_INSTRUCTION_BLOCK,
                    target = "TracerPid",
                    searchRegex = Regex("""([ \t]*const-string(?:/jumbo)?\s+[vp]\d+,\s*)"TracerPid""""),
                    description = L("替换 TracerPid 检测关键字", "Replace TracerPid detection keyword")
                ) { mr ->
                    "${mr.groupValues[1]}\"SafePid\""
                }
            )
        ),

        // ── 模拟器检测绕过 ──
        PatchTemplate(
            id = "bypass_emulator",
            name = L("绕过模拟器检测", "Bypass Emulator Detection"),
            category = L("安全绕过", "Security Bypass"),
            description = L("修改模拟器特征检测（Build.FINGERPRINT、qemu、goldfish 等）", "Modify emulator detection strings (goldfish, ranchu, generic)"),
            patterns = listOf(
                PatchPattern(
                    type = PatternType.SMALI_INSTRUCTION_BLOCK,
                    target = "goldfish / ranchu",
                    searchRegex = Regex("""([ \t]*const-string(?:/jumbo)?\s+[vp]\d+,\s*)"(?:goldfish|ranchu|sdk_gphone|vbox86p|nox|bluestacks)"""", RegexOption.IGNORE_CASE),
                    description = L("替换模拟器硬件特征字符串", "Replace emulator hardware signature strings")
                ) { mr ->
                    "${mr.groupValues[1]}\"qcom_realdevice\""
                }
            )
        ),

        // ── SSL Pinning 绕过 ──
        PatchTemplate(
            id = "bypass_ssl_pinning",
            name = L("绕过 SSL 证书绑定", "Bypass SSL Certificate Pinning"),
            category = L("网络抓包", "Network Capture"),
            description = L("禁用 OkHttp CertificatePinner 与 X509TrustManager 证书校验，配合抓包工具使用", "Disable OkHttp CertificatePinner and X509TrustManager verification"),
            patterns = listOf(
                PatchPattern(
                    type = PatternType.SMALI_INSTRUCTION_BLOCK,
                    target = "CertificatePinner.check",
                    searchRegex = Regex("""[ \t]*invoke-virtual\s+\{[^}]+\},\s*Lokhttp3/CertificatePinner;->check\([^\r\n]*"""),
                    description = L("移除 OkHttp 证书固定检查调用", "Remove OkHttp CertificatePinner.check call")
                ) { _ ->
                    "    nop"
                },
                PatchPattern(
                    type = PatternType.SMALI_INSTRUCTION_BLOCK,
                    target = "checkServerTrusted",
                    searchRegex = Regex("""[ \t]*invoke-interface\s+\{[^}]+\},\s*Ljavax/net/ssl/X509TrustManager;->checkServerTrusted\([^\r\n]*"""),
                    description = L("跳过 X509TrustManager.checkServerTrusted 调用", "Skip X509TrustManager.checkServerTrusted call")
                ) { _ ->
                    "    nop"
                }
            )
        ),

        // ── 启用调试模式 ──
        PatchTemplate(
            id = "enable_debuggable",
            name = L("启用调试模式", "Enable Debuggable Mode"),
            category = L("开发调试", "Development & Debugging"),
            description = L("在 AndroidManifest.xml 中设置 android:debuggable=\"true\"（支持二进制 AXML 与文本 XML）", "Set android:debuggable=\"true\" in AndroidManifest.xml"),
            patterns = listOf(
                PatchPattern(
                    type = PatternType.MANIFEST_EDIT,
                    target = "AndroidManifest.xml",
                    searchRegex = Regex("""android:debuggable\s*=\s*"false""""),
                    description = L("启用应用调试", "Enable app debugging")
                ) { _ ->
                    """android:debuggable="true""""
                }
            )
        ),

        // ── 广告移除 ──
        PatchTemplate(
            id = "remove_ads",
            name = L("移除常见广告 SDK", "Remove Common Ad SDKs"),
            category = L("功能修改", "Feature Modification"),
            description = L("禁用 Google AdMob、穿山甲、优量汇等常见广告 SDK 的加载", "Disable loading of AdMob, Pangle, GDT and other ad SDKs"),
            patterns = listOf(
                PatchPattern(
                    type = PatternType.SMALI_INSTRUCTION_BLOCK,
                    target = "AdView.loadAd",
                    searchRegex = Regex("""[ \t]*invoke-virtual\s+\{[^}]+\},\s*Lcom/google/android/gms/ads/[^;]+;->loadAd\([^\r\n]*"""),
                    description = L("禁用 AdMob 广告加载", "Disable AdMob ad loading")
                ) { _ ->
                    "    nop"
                },
                PatchPattern(
                    type = PatternType.SMALI_INSTRUCTION_BLOCK,
                    target = "TTAdNative.load",
                    searchRegex = Regex("""[ \t]*invoke-interface\s+\{[^}]+\},\s*Lcom/bytedance/sdk/openadsdk/TTAdNative;->load[^\r\n]*"""),
                    description = L("禁用穿山甲广告加载", "Disable Pangle ad loading")
                ) { _ ->
                    "    nop"
                }
            )
        ),

        // ── 截图限制解除 ──
        PatchTemplate(
            id = "allow_screenshot",
            name = L("解除截图/录屏限制", "Remove Screenshot/Recording Restriction"),
            category = L("功能修改", "Feature Modification"),
            description = L("移除 FLAG_SECURE (0x2000) 标志，允许在应用内截图和录屏", "Remove WindowManager.LayoutParams.FLAG_SECURE (0x2000) to allow screenshots"),
            patterns = listOf(
                PatchPattern(
                    type = PatternType.SMALI_INSTRUCTION_BLOCK,
                    target = "Window.setFlags(FLAG_SECURE)",
                    searchRegex = Regex("""([ \t]*const/16\s+([vp]\d+),\s*)0x2000\b"""),
                    description = L("将 FLAG_SECURE (0x2000) 置零", "Replace FLAG_SECURE (0x2000) with 0x0")
                ) { mr ->
                    "${mr.groupValues[1]}0x0"
                }
            )
        ),

        // ── VIP/会员检查绕过 ──
        PatchTemplate(
            id = "bypass_vip_check",
            name = L("VIP 检查点定位与绕过", "VIP Check Bypass"),
            category = L("功能修改", "Feature Modification"),
            description = L("搜索并修改常见的 VIP/会员布尔状态检查方法（isVip、isPremium、isPro 等）", "Locate and patch common boolean VIP/Premium check methods"),
            patterns = listOf(
                PatchPattern(
                    type = PatternType.SMALI_INSTRUCTION_BLOCK,
                    target = "isVip / isPremium / isPro",
                    searchRegex = invokeAndMoveResultRegex("""invoke-(?:virtual|direct|static|interface)\s+\{[^}]*\},\s*L[^;]+;->(?:isVip|isPremium|isPro|isPaid|isSubscribed|hasVip)\([^)]*\)Z"""),
                    description = L("强制 VIP 状态返回 true", "Force VIP boolean check result to true")
                ) { mr ->
                    val reg = mr.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
                    if (reg != null) "    const/4 $reg, 0x1" else mr.value
                }
            )
        )
    )

    val PATCHES get() = TEMPLATES

    private val ID_ALIASES = mapOf(
        "signature_check" to "bypass_signature",
        "root_detection" to "bypass_root",
        "emulator_detection" to "bypass_emulator",
        "debug_restriction" to "bypass_debug",
        "ssl_pinning" to "bypass_ssl_pinning",
        "vip_bypass" to "bypass_vip_check"
    )

    fun findTemplate(idOrAlias: String): PatchTemplate? {
        val normalized = ID_ALIASES[idOrAlias] ?: idOrAlias
        return TEMPLATES.find { it.id == normalized || it.id == idOrAlias }
    }

    fun scanAvailable(workspace: File): String {
        val recs = recommendPatches(workspace)
        return if (recs.isEmpty()) {
            L(
                "当前工作区未扫描到已反编译的特征匹配（若尚未反编译，可直接调用 smart_patch_apply 自动反编译并应用 Patch）。\n可用模板: ${TEMPLATES.joinToString { it.id }}",
                "No disassembled matches found in workspace yet. Available templates: ${TEMPLATES.joinToString { it.id }}"
            )
        } else {
            buildString {
                appendLine(L("🎯 扫描到以下可用智能 Patch：", "🎯 Applicable Smart Patches:"))
                recs.forEach { (t, count) ->
                    appendLine("• ${t.id} (${t.name}) — 匹配 $count 处: ${t.description}")
                }
            }
        }
    }

    suspend fun applyPatch(
        workspace: File,
        patchId: String,
        targetDir: String? = null,
        openApk: File? = null
    ): PatchResult {
        val template = findTemplate(patchId)
            ?: return PatchResult(false, "未知 Patch ID: $patchId\n可用: ${TEMPLATES.joinToString { it.id }}", 0)
        val dir = if (!targetDir.isNullOrBlank()) Sandbox.resolve(File(targetDir), workspace) else workspace
        return applyPatch(template, dir, openApk)
    }

    private fun collectSmaliFiles(workspace: File): List<File> {
        if (!workspace.exists()) return emptyList()
        return workspace.walkTopDown()
            .onEnter { dir -> dir.name != ".snapshots" && dir.name != "audit" && dir.name != "projects" }
            .filter { it.isFile && it.extension == "smali" }
            .toList()
    }

    /**
     * 扫描工作区，推荐适用的 Patch 模板。
     */
    fun recommendPatches(workspace: File): List<Pair<PatchTemplate, Int>> {
        val recommendations = mutableListOf<Pair<PatchTemplate, Int>>()
        val smaliFiles = collectSmaliFiles(workspace)
        if (smaliFiles.isEmpty()) return emptyList()

        for (template in TEMPLATES) {
            var matchCount = 0
            for (pattern in template.patterns) {
                when (pattern.type) {
                    PatternType.SMALI_INSTRUCTION_BLOCK -> {
                        smaliFiles.forEach { file ->
                            val content = runCatching { file.readText() }.getOrDefault("")
                            matchCount += pattern.searchRegex.findAll(content).count()
                        }
                    }
                    PatternType.MANIFEST_EDIT -> {
                        val manifest = workspace.walkTopDown()
                            .onEnter { it.name != ".snapshots" }
                            .find { it.isFile && it.name == "AndroidManifest.xml" }
                        if (manifest != null) {
                            val bytes = manifest.readBytes()
                            val xml = AxmlParser.parseCached(bytes)
                            if (pattern.searchRegex.containsMatchIn(xml) || !xml.contains("""android:debuggable="true"""")) {
                                matchCount++
                            }
                        }
                    }
                }
            }
            if (matchCount > 0) {
                recommendations.add(template to matchCount)
            }
        }

        return recommendations.sortedByDescending { it.second }
    }

    /**
     * 应用指定的 Patch 模板。
     */
    suspend fun applyPatch(template: PatchTemplate, workspace: File, openApk: File? = null): PatchResult {
        var modifiedFiles = 0
        val logs = mutableListOf<String>()

        try {
            var smaliFiles = collectSmaliFiles(workspace)
            if (smaliFiles.isEmpty() && openApk != null && openApk.exists()) {
                val autoSmaliDir = File(workspace, "smali_out")
                val disasm = SmaliEngine.disassembleApk(openApk, autoSmaliDir)
                if (disasm.success) {
                    logs.add("📦 已自动反编译 ${openApk.name} -> ${autoSmaliDir.name} (${disasm.classCount} 个类)")
                    smaliFiles = collectSmaliFiles(autoSmaliDir)
                }
            }

            for (pattern in template.patterns) {
                when (pattern.type) {
                    PatternType.SMALI_INSTRUCTION_BLOCK -> {
                        if (smaliFiles.isEmpty()) {
                            logs.add("⚠️ 未找到 smali 文件，请先执行 dex_disasm 或 apk_unpack")
                            continue
                        }
                        var patternHits = 0
                        smaliFiles.forEach { file ->
                            val content = file.readText()
                            if (pattern.searchRegex.containsMatchIn(content)) {
                                val newContent = pattern.searchRegex.replace(content) { mr ->
                                    patternHits++
                                    pattern.replacementBuilder(mr)
                                }
                                if (newContent != content) {
                                    file.writeText(newContent)
                                    modifiedFiles++
                                    logs.add("✓ ${file.name}: ${pattern.description}")
                                }
                            }
                        }
                        if (patternHits == 0) {
                            logs.add("ℹ️ 未匹配到特征: ${pattern.target}")
                        }
                    }
                    PatternType.MANIFEST_EDIT -> {
                        val manifest = workspace.walkTopDown()
                            .onEnter { it.name != ".snapshots" }
                            .find { it.isFile && it.name == "AndroidManifest.xml" }
                        if (manifest == null) {
                            logs.add("⚠️ 未找到 AndroidManifest.xml，跳过 ${pattern.description}")
                            continue
                        }
                        val raw = manifest.readBytes()
                        if (AxmlParser.isBinaryAxml(raw)) {
                            val (patchedBytes, changes) = AxmlParser.patchBinaryManifest(raw, debuggable = true)
                            if (changes.isNotEmpty()) {
                                manifest.writeBytes(patchedBytes)
                                modifiedFiles++
                                logs.add("✓ AndroidManifest.xml (二进制 AXML): ${changes.joinToString()}")
                            } else {
                                logs.add("ℹ️ AndroidManifest.xml 中未找到可原地覆写的 debuggable 属性槽位")
                            }
                        } else {
                            val content = String(raw, Charsets.UTF_8)
                            val newContent = if (pattern.searchRegex.containsMatchIn(content)) {
                                pattern.searchRegex.replace(content) { mr -> pattern.replacementBuilder(mr) }
                            } else {
                                content.replace(Regex("""<application\b"""), """<application android:debuggable="true"""")
                            }
                            if (newContent != content) {
                                manifest.writeText(newContent)
                                modifiedFiles++
                                logs.add("✓ AndroidManifest.xml: ${pattern.description}")
                            }
                        }
                    }
                }
            }

            return if (modifiedFiles > 0) {
                PatchResult(true, "成功修改 $modifiedFiles 个文件\n" + logs.joinToString("\n"), modifiedFiles)
            } else {
                PatchResult(false, L("未找到匹配的代码模式\n", "No matching code pattern found\n") + logs.joinToString("\n"), 0)
            }
        } catch (e: Exception) {
            return PatchResult(false, "应用失败: ${e.message}\n" + logs.joinToString("\n"), modifiedFiles)
        }
    }

    data class PatchResult(val success: Boolean, val message: String, val modifiedCount: Int)
}

/**
 * Tool 包装：列出/推荐/应用智能 Patch。
 */
object SmartPatchTool : Tool {
    override val name = "smart_patch"
    override val description = L(
        "智能 Patch 系统：list(列出模板) / recommend(根据代码推荐) / apply(应用模板)。支持绕过签名、Root、调试、模拟器、SSL Pinning、VIP 等。",
        "Smart Patch system: list (templates) / recommend (scan workspace) / apply (apply template)."
    )
    override val parameters = schemaObject(
        mapOf(
            "action" to strProp(L("操作：list / recommend / apply", "Action: list / recommend / apply")),
            "template_id" to strProp(L("模板 ID（apply 时必填）：bypass_signature, bypass_root, bypass_debug, bypass_emulator, bypass_ssl_pinning, enable_debuggable, remove_ads, allow_screenshot, bypass_vip_check", "Template ID (required for apply)")),
            "workspace_dir" to strProp(L("工作目录（留空默认当前工作区）", "Working directory (defaults to current workspace)"))
        ),
        required = listOf("action")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val action = args.str("action") ?: "list"
        val rawWs = args.str("workspace_dir")?.takeIf { it.isNotBlank() }
        val wsDir = if (rawWs != null) Sandbox.resolve(File(rawWs), ctx.workspace) else ctx.workspace

        return when (action) {
            "list" -> {
                val sb = StringBuilder(L("📋 可用智能 Patch 模板：\n\n", "📋 Available Smart Patch Templates:\n\n"))
                SmartPatch.TEMPLATES.groupBy { it.category }.forEach { (cat, templates) ->
                    sb.appendLine("【$cat】")
                    templates.forEach { t ->
                        sb.appendLine("  • ${t.id} — ${t.name}")
                        sb.appendLine("    ${t.description}")
                    }
                    sb.appendLine()
                }
                ToolResult(true, sb.toString())
            }
            "recommend" -> {
                val recs = SmartPatch.recommendPatches(wsDir)
                if (recs.isEmpty()) {
                    ToolResult(true, L("未检测到明显的保护/特征模式（若尚未反编译，可先执行 dex_disasm 或直接调用 smart_patch apply）", "No obvious patterns detected in current workspace"))
                } else {
                    val sb = StringBuilder(L("🎯 推荐应用的 Patch（按匹配度排序）：\n\n", "🎯 Recommended Patches (sorted by matches):\n\n"))
                    recs.forEach { (t, count) ->
                        sb.appendLine("• ${t.name} (${t.id}) — 检测到 $count 处匹配")
                        sb.appendLine("  ${t.description}")
                        sb.appendLine()
                    }
                    ToolResult(true, sb.toString())
                }
            }
            "apply" -> {
                val tid = args.str("template_id") ?: return ToolResult(false, L("缺少 template_id", "Missing template_id"))
                val template = SmartPatch.findTemplate(tid)
                    ?: return ToolResult(false, "未知模板 ID: $tid")
                val result = SmartPatch.applyPatch(template, wsDir, ctx.openApk)
                ToolResult(result.success, result.message)
            }
            else -> ToolResult(false, "未知 action: $action（支持 list/recommend/apply）")
        }
    }
}
