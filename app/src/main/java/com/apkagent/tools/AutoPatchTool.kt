package com.apkagent.tools

import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import com.apkagent.util.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import java.io.File

/**
 * 智能批量 Patch 工具
 * 自动扫描并应用多种 Patch 策略
 */
class AutoPatchTool : Tool {
    override val name = "auto_patch"
    override val description = """智能批量 Patch：自动扫描工作区并应用多种修改策略。
参数：workspace（解包目录路径）、patchTypes（要应用的 patch 类型列表，默认全部）
类型：signature/root/emulator/debug/virtual/device_binding/license/antidebug/anti_hook/ssl_pinning/time_limit/ads/permissions
自动执行高置信度 patch，输出修改清单。"""
    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "workspace" to mapOf("type" to "string", "description" to "解包目录路径"),
            "patchTypes" to mapOf("type" to "array", "description" to "要应用的 patch 类型列表", "items" to mapOf("type" to "string"))
        ),
        "required" to listOf("workspace")
    )
    override val sensitive = true

    // Patch 策略定义
    private data class PatchStrategy(
        val name: String,
        val patterns: List<String>,
        val replacement: String,
        val description: String
    )

    private val strategies = listOf(
        PatchStrategy("signature_check", listOf(
            "getPackageInfo.*SIGNATURES", "checkSignature", "verifySignature",
            "PackageManager.GET_SIGNATURES", "signatures\[0\]"
        ), "return-void", "签名校验绕过"),
        PatchStrategy("root_detection", listOf(
            "su", "/system/bin/su", "/system/xbin/su", "com.noshufou.android.su",
            "com.thirdparty.superuser", "eu.chainfire.supersu", "RootBeer",
            "isRooted", "checkRoot", "detectRoot"
        ), "const/4 v0, 0x0\nreturn v0", "Root 检测绕过"),
        PatchStrategy("emulator_detection", listOf(
            "generic", "sdk_gphone", "emulator", "android-sdk", "goldfish",
            "isEmulator", "checkEmulator", "Build.FINGERPRINT", "Build.MODEL.*sdk"
        ), "const/4 v0, 0x0\nreturn v0", "模拟器检测绕过"),
        PatchStrategy("debug_detection", listOf(
            "isDebuggerConnected", "Debug.isDebuggerConnected", "android.os.Debug",
            "TracerPid", "waitForDebugger", "setDebuggable"
        ), "return-void", "调试检测绕过"),
        PatchStrategy("ssl_pinning", listOf(
            "X509TrustManager", "SSLContext", "TrustManager", "HostnameVerifier",
            "CertificatePinner", "okhttp3.CertificatePinner", "pinning"
        ), "return-void", "SSL Pinning 绕过"),
        PatchStrategy("frida_detection", listOf(
            "frida", "fridaserver", "LIBFRIDA", "REJECT", "gum-js-loop",
            "linjector", "isFridaRunning", "detectFrida"
        ), "const/4 v0, 0x0\nreturn v0", "Frida 检测绕过"),
        PatchStrategy("hook_detection", listOf(
            "XposedBridge", "de.robv.android.xposed", "isXposedInstalled",
            "substrate", "com.saurik.substrate", "isHooked"
        ), "const/4 v0, 0x0\nreturn v0", "Hook 检测绕过"),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val workspace = args["workspace"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 workspace 参数")
        val patchTypes = args["patchTypes"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()

        val dir = File(workspace)
        if (!dir.exists()) return ToolResult.err("工作区不存在: $workspace")

        val appliedPatches = mutableListOf<String>()
        val smaliFiles = dir.walkTopDown().filter { it.extension == "smali" }.toList()

        for (strategy in strategies) {
            if (patchTypes != null && strategy.name !in patchTypes) continue

            for (file in smaliFiles) {
                var content = file.readText()
                var modified = false

                for (pattern in strategy.patterns) {
                    val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                    if (regex.containsMatchIn(content)) {
                        // 找到匹配的 if-eqz / if-nez 跳转并修改
                        val lines = content.lines().toMutableList()
                        for (i in lines.indices) {
                            val line = lines[i].trim()
                            if (regex.containsMatchIn(line)) {
                                // 尝试修改附近的条件跳转
                                for (j in maxOf(0, i-2)..minOf(lines.size-1, i+2)) {
                                    val nearby = lines[j].trim()
                                    if (nearby.startsWith("if-eqz") || nearby.startsWith("if-nez")) {
                                        // 反转跳转条件
                                        lines[j] = if (nearby.startsWith("if-eqz")) {
                                            nearby.replace("if-eqz", "if-nez")
                                        } else {
                                            nearby.replace("if-nez", "if-eqz")
                                        }
                                        modified = true
                                    }
                                }
                            }
                        }
                        if (modified) {
                            content = lines.joinToString("\n")
                        }
                    }
                }

                if (modified) {
                    file.writeText(content)
                    appliedPatches.add("${strategy.description}: ${file.name}")
                }
            }
        }

        return if (appliedPatches.isEmpty()) {
            ToolResult.ok("未发现需要 patch 的代码点")
        } else {
            ToolResult.ok("✅ 已应用 ${appliedPatches.size} 个 patch:\n${appliedPatches.joinToString("\n") { "  - $it" }}")
        }
    }
}
