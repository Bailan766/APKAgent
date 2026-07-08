package com.apkagent.tools

import com.apkagent.agent.*
import kotlinx.serialization.json.*
import java.io.File

class AutoPatchTool : Tool {
    override val name = "auto_patch"
    override val description = "智能批量 Patch：自动扫描工作区并应用多种修改策略。参数：workspace（解包目录路径）、patchTypes（可选，patch 类型列表）。类型：signature/root/emulator/debug/virtual/device_binding/license/antidebug/anti_hook/ssl_pinning/time_limit/ads/permissions。"
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "workspace" to strProp("解包目录路径"),
            "patchTypes" to JsonObject(mapOf(
                "type" to JsonPrimitive("array"),
                "description" to JsonPrimitive("要应用的 patch 类型列表"),
                "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))
            ))
        ),
        required = listOf("workspace")
    )
    override val sensitive = true

    private data class PatchStrategy(
        val name: String, val patterns: List<String>,
        val fixIfEqz: Boolean = true, val description: String
    )

    private val strategies = listOf(
        PatchStrategy("signature", listOf("SIGNATURES", "checkSignature", "verifySignature", "GET_SIGNATURES"), description = "签名校验绕过"),
        PatchStrategy("root", listOf("/system/bin/su", "/system/xbin/su", "isRooted", "checkRoot", "RootBeer", "com.noshufou.android.su"), description = "Root 检测绕过"),
        PatchStrategy("emulator", listOf("isEmulator", "checkEmulator", "sdk_gphone", "generic.*goldfish"), description = "模拟器检测绕过"),
        PatchStrategy("debug", listOf("isDebuggerConnected", "TracerPid", "waitForDebugger"), description = "调试检测绕过"),
        PatchStrategy("ssl_pinning", listOf("CertificatePinner", "X509TrustManager", "TrustManager"), description = "SSL Pinning 绕过"),
        PatchStrategy("antidebug", listOf("REJECT", "fridaserver", "LIBFRIDA", "isFridaRunning"), description = "反Frida/反调试绕过"),
        PatchStrategy("anti_hook", listOf("XposedBridge", "de.robv.android.xposed", "isXposedInstalled", "substrate"), description = "反Hook 绕过"),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val workspace = args["workspace"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.err("缺少 workspace")
        val patchTypes = args["patchTypes"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()

        val dir = File(workspace)
        if (!dir.exists()) return ToolResult.err("工作区不存在: $workspace")

        val appliedPatches = mutableListOf<String>()
        val smaliFiles = dir.walkTopDown().filter { it.extension == "smali" }.toList()

        for (strategy in strategies) {
            if (patchTypes != null && strategy.name !in patchTypes) continue

            for (file in smaliFiles) {
                try {
                    val lines = file.readLines().toMutableList()
                    var modified = false

                    for (i in lines.indices) {
                        val line = lines[i]
                        if (strategy.patterns.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(line) }) {
                            // 查找附近的条件跳转并反转
                            for (j in maxOf(0, i - 2)..minOf(lines.size - 1, i + 2)) {
                                val nearby = lines[j].trim()
                                if (nearby.startsWith("if-eqz")) {
                                    lines[j] = lines[j].replace("if-eqz", "if-nez")
                                    modified = true
                                    break
                                } else if (nearby.startsWith("if-nez")) {
                                    lines[j] = lines[j].replace("if-nez", "if-eqz")
                                    modified = true
                                    break
                                }
                            }
                        }
                    }

                    if (modified) {
                        file.writeText(lines.joinToString("\n"))
                        appliedPatches.add("${strategy.description}: ${file.name}")
                    }
                } catch (_: Exception) {}
            }
        }

        return if (appliedPatches.isEmpty()) {
            ToolResult.ok("未发现需要 patch 的代码点")
        } else {
            ToolResult.ok("✅ 已应用 ${appliedPatches.size} 个 patch:\n${appliedPatches.joinToString("\n") { "  - $it" }}")
        }
    }
}
