package com.apkagent.tools

import com.apkagent.i18n.L
import com.apkagent.agent.*
import com.apkagent.apktools.AxmlParser
import com.apkagent.apktools.Sandbox
import com.apkagent.apktools.smali.SmaliEngine
import kotlinx.serialization.json.*
import java.io.File

/**
 * 智能自动修改工具 — 自动定位并修改常见的检测逻辑（VIP、签名、Root、模拟器、证书固定）
 *
 * 修复说明：
 * 1. 修复同一跳转行在匹配多个特征时被翻转两次（if-eqz -> if-nez -> if-eqz）导致修改失效的 Bug。
 * 2. 扩大条件跳转指令扫描窗口（支持在 move-result 之后的 1~8 行内定位跳转指令）。
 * 3. 支持 workspace 相对路径解析与自动反编译。
 */
class AutoPatchTool : Tool {
    companion object : Tool by AutoPatchTool()
    override val name = "auto_patch"
    override val description get() = L(
        "自动识别并修改常见检测逻辑（VIP检测、Root检测、模拟器检测、签名验证、SSL Pinning）",
        "Automatically identify and patch common check logic (VIP, Root, Emulator, Signature, SSL Pinning)"
    )

    override val parameters get() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("workspace") {
                put("type", "string")
                put("description", L("APK 解包后的工作目录（留空默认当前工作区）", "Unpacked APK workspace directory (defaults to current workspace)"))
            }
            putJsonObject("patch_type") {
                put("type", "string")
                put("description", L("要应用的修改类型", "Patch type to apply"))
                putJsonArray("enum") {
                    add("vip")
                    add("root")
                    add("emulator")
                    add("signature")
                    add("ssl_pinning")
                    add("debuggable")
                }
            }
            putJsonObject("dry_run") {
                put("type", "boolean")
                put("description", L("是否仅预览不实际修改（默认 false）", "Preview only without modifying files (default false)"))
            }
        }
        putJsonArray("required") {
            add("patch_type")
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawWs = args["workspace"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val wsDir = if (rawWs != null) Sandbox.resolve(File(rawWs), ctx.workspace) else ctx.workspace
        val patchType = args["patch_type"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(false, L("缺少 patch_type 参数", "Missing patch_type parameter"))
        val dryRun = args["dry_run"]?.jsonPrimitive?.booleanOrNull ?: false

        if (!wsDir.exists()) {
            return ToolResult(false, "工作目录不存在: ${wsDir.absolutePath}")
        }

        // 如果工作区尚未反编译出 .smali，且有打开的 APK，则自动反编译
        val existingSmali = wsDir.walkTopDown()
            .onEnter { it.name != ".snapshots" && it.name != "audit" && it.name != "projects" }
            .any { it.isFile && it.extension == "smali" }
        if (!existingSmali && patchType != "debuggable" && ctx.openApk != null && ctx.openApk!!.exists()) {
            SmaliEngine.disassembleApk(ctx.openApk!!, File(wsDir, "smali_out"))
        }

        val patches = mutableListOf<String>()

        when (patchType) {
            "vip" -> patchVipChecks(wsDir, dryRun, patches)
            "root" -> patchRootChecks(wsDir, dryRun, patches)
            "emulator" -> patchEmulatorChecks(wsDir, dryRun, patches)
            "signature" -> patchSignatureChecks(wsDir, dryRun, patches)
            "ssl_pinning" -> patchSslPinning(wsDir, dryRun, patches)
            "debuggable" -> patchDebuggable(wsDir, dryRun, patches)
            else -> return ToolResult(false, "不支持的 patch_type: $patchType")
        }

        val output = if (patches.isEmpty()) {
            "未找到可自动修改的 $patchType 检测点（建议使用 smart_search 手动搜索定位）"
        } else {
            buildString {
                appendLine(if (dryRun) "# 预览模式 — 以下位置将被修改:" else "# 已完成以下修改:")
                appendLine()
                patches.forEach { appendLine("- $it") }
                appendLine()
                if (!dryRun) {
                    appendLine(L("提示: 修改已完成，可使用 `dex_asm` / `apk_repack` + `apk_sign` 重新打包签名", "Tip: Patch complete; use dex_asm / apk_repack + apk_sign to repack and sign"))
                }
            }
        }

        return ToolResult(true, output)
    }

    private fun listSmaliFiles(wsDir: File): Sequence<File> =
        wsDir.walkTopDown()
            .onEnter { it.name != ".snapshots" && it.name != "audit" && it.name != "projects" }
            .filter { it.isFile && it.extension == "smali" }

    private fun flipJumpInstruction(line: String): String? = when {
        line.contains("if-eqz") -> line.replace("if-eqz", "if-nez")
        line.contains("if-nez") -> line.replace("if-nez", "if-eqz")
        else -> null
    }

    private fun patchByPatterns(
        wsDir: File,
        patterns: List<String>,
        dryRun: Boolean,
        label: String,
        patches: MutableList<String>
    ) {
        val regexes = patterns.map { Regex(it, RegexOption.IGNORE_CASE) }
        listSmaliFiles(wsDir).forEach { file ->
            val lines = runCatching { file.readLines().toMutableList() }.getOrNull() ?: return@forEach
            val flippedLineIndices = mutableSetOf<Int>()
            var modified = false

            for (i in lines.indices) {
                val line = lines[i]
                if (regexes.any { it.containsMatchIn(line) }) {
                    // 在当前行及后续 8 行内寻找最近的条件跳转指令，且每个跳转行最多翻转一次
                    for (j in i..minOf(lines.size - 1, i + 8)) {
                        if (j in flippedLineIndices) break
                        val flipped = flipJumpInstruction(lines[j])
                        if (flipped != null) {
                            flippedLineIndices.add(j)
                            if (!dryRun) {
                                lines[j] = flipped
                                modified = true
                            }
                            patches.add("${file.relativeToOrSelf(wsDir).path}:${j + 1} — 反转 $label 条件跳转")
                            break
                        }
                    }
                }
            }

            if (modified) {
                file.writeText(lines.joinToString("\n"))
            }
        }
    }

    private fun patchVipChecks(wsDir: File, dryRun: Boolean, patches: MutableList<String>) {
        val patterns = listOf(
            "isVip", "isPro", "isPremium", "isPaid", "checkVip", "hasVip", "vipStatus", "memberLevel"
        )
        patchByPatterns(wsDir, patterns, dryRun, "VIP 检测", patches)
    }

    private fun patchRootChecks(wsDir: File, dryRun: Boolean, patches: MutableList<String>) {
        val rootIndicators = listOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "com.noshufou.android.su",
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu"
        )

        listSmaliFiles(wsDir).forEach { file ->
            var content = runCatching { file.readText() }.getOrDefault("")
            var modified = false

            rootIndicators.forEach { indicator ->
                if (content.contains(indicator)) {
                    if (!dryRun) {
                        content = content.replace(indicator, "/dev/null/not_exist")
                        modified = true
                    }
                    patches.add("${file.relativeToOrSelf(wsDir).path} — 替换 Root 特征字符串: $indicator")
                }
            }

            if (modified) {
                file.writeText(content)
            }
        }
    }

    private fun patchEmulatorChecks(wsDir: File, dryRun: Boolean, patches: MutableList<String>) {
        val emulatorStrings = listOf("generic", "vbox", "goldfish", "ranchu", "Andy", "Nox", "BlueStacks")

        listSmaliFiles(wsDir).forEach { file ->
            var content = runCatching { file.readText() }.getOrDefault("")
            var modified = false

            emulatorStrings.forEach { str ->
                if (content.contains("\"$str\"")) {
                    if (!dryRun) {
                        content = content.replace("\"$str\"", "\"real_device_placeholder\"")
                        modified = true
                    }
                    patches.add("${file.relativeToOrSelf(wsDir).path} — 替换模拟器特征: $str")
                }
            }

            if (modified) {
                file.writeText(content)
            }
        }
    }

    private fun patchSignatureChecks(wsDir: File, dryRun: Boolean, patches: MutableList<String>) {
        val patterns = listOf(
            "PackageManager;->getPackageInfo",
            "PackageInfo;->signatures",
            "SigningInfo;->hasSignature",
            "Signature;->toCharsString",
            "Signature;->toByteArray",
            "Signature;->hashCode"
        )
        patchByPatterns(wsDir, patterns, dryRun, "签名校验", patches)
    }

    private fun patchSslPinning(wsDir: File, dryRun: Boolean, patches: MutableList<String>) {
        listSmaliFiles(wsDir).forEach { file ->
            val content = runCatching { file.readText() }.getOrDefault("")
            if (content.contains("checkServerTrusted") || content.contains("CertificatePinner")) {
                val lines = content.lines().toMutableList()
                var modified = false

                // 先收集所有插入位置，再倒序插入：避免在固定范围循环内 add 导致后续索引错位、生成损坏 smali
                val insertPositions = mutableListOf<Int>()
                for (i in lines.indices) {
                    if (lines[i].contains(".method") &&
                        (lines[i].contains("checkServerTrusted") || lines[i].contains("check(")) &&
                        lines[i].endsWith(")V")
                    ) {
                        for (j in i + 1 until minOf(lines.size, i + 15)) {
                            if (lines[j].trim().startsWith(".locals") || lines[j].trim().startsWith(".registers")) {
                                insertPositions.add(j + 1)
                                patches.add("${file.relativeToOrSelf(wsDir).path}:${j + 1} — 置空 SSL Pinning 校验方法")
                                break
                            }
                        }
                    }
                }
                if (!dryRun && insertPositions.isNotEmpty()) {
                    insertPositions.sortedDescending().forEach { pos -> lines.add(pos, "    return-void") }
                    modified = true
                }

                if (modified) {
                    file.writeText(lines.joinToString("\n"))
                }
            }
        }
    }

    private fun patchDebuggable(wsDir: File, dryRun: Boolean, patches: MutableList<String>) {
        val manifestFile = wsDir.walkTopDown()
            .onEnter { it.name != ".snapshots" }
            .find { it.isFile && it.name == "AndroidManifest.xml" } ?: return

        val raw = manifestFile.readBytes()
        if (com.apkagent.apktools.AxmlParser.isBinaryAxml(raw)) {
            val (patched, changes) = com.apkagent.apktools.AxmlParser.patchBinaryManifest(raw, debuggable = true)
            if (changes.isNotEmpty()) {
                if (!dryRun) manifestFile.writeBytes(patched)
                patches.add("AndroidManifest.xml (二进制 AXML) — ${changes.joinToString()}")
            }
            return
        }

        var content = String(raw, Charsets.UTF_8)
        if (!content.contains("android:debuggable=\"true\"")) {
            if (!dryRun) {
                content = if (content.contains("android:debuggable=\"false\"")) {
                    content.replace("android:debuggable=\"false\"", "android:debuggable=\"true\"")
                } else {
                    content.replace("<application", "<application android:debuggable=\"true\"")
                }
                manifestFile.writeText(content)
            }
            patches.add(L("AndroidManifest.xml — 启用 android:debuggable=\"true\"", "AndroidManifest.xml — Enabled android:debuggable=\"true\""))
        }
    }
}
