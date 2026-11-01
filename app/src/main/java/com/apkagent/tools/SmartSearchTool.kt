package com.apkagent.tools

import com.apkagent.i18n.L
import com.apkagent.agent.*
import com.apkagent.apktools.AxmlParser
import com.apkagent.apktools.DexParser
import com.apkagent.apktools.Sandbox
import kotlinx.serialization.json.*
import java.io.File
import java.util.zip.ZipFile

/**
 * 智能搜索工具 — 在 smali、DEX、资源、原生库中快速搜索关键信息
 *
 * 修复说明：
 * 1. 修复 search_type 为 "urls" 或 "keys" 时完全不执行搜索分支（恒返回“未找到匹配”）的 Bug。
 * 2. 支持对二进制 AXML (.xml) 自动解码后搜索，并排除 .snapshots / audit 目录。
 * 3. 当工作区尚未反编译出 .smali 时，自动回退直接扫描 APK 内的 DEX 字符串池、类名与 Manifest。
 */
class SmartSearchTool : Tool {
    companion object : Tool by SmartSearchTool()
    override val name = "smart_search"
    override val description get() = L(
        "在 APK 工作区或 DEX 中智能搜索字符串、类名、方法名、URL、密钥等（比逐步 grep 快 10 倍）",
        "Smart search for strings, class names, method names, URLs, keys in APK workspace or DEX"
    )

    override val parameters get() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("workspace") {
                put("type", "string")
                put("description", L("APK 解包后的工作目录路径（留空默认当前工作区）", "Unpacked APK workspace directory (defaults to current workspace)"))
            }
            putJsonObject("query") {
                put("type", "string")
                put("description", L("搜索关键词或正则表达式（当 search_type=urls/keys 时可留空自动提取）", "Search keyword or regex (optional when search_type=urls/keys)"))
            }
            putJsonObject("search_type") {
                put("type", "string")
                put("description", L("搜索类型", "Search type"))
                putJsonArray("enum") {
                    add("all")
                    add("strings")
                    add("classes")
                    add("methods")
                    add("resources")
                    add("urls")
                    add("keys")
                }
            }
            putJsonObject("max_results") {
                put("type", "integer")
                put("description", L("最大返回结果数（默认 50）", "Maximum number of results to return (default 50)"))
            }
        }
        putJsonArray("required") {
            add("search_type")
        }
    }

    private val URL_REGEX = Regex("""https?://[a-zA-Z0-9._~:/?#\[\]@!$&'()*+,;=%\-]{6,200}""", RegexOption.IGNORE_CASE)
    private val KEY_REGEX = Regex(
        """(?i)(?:api[_-]?key|secret|token|password|passwd|access[_-]?key|private[_-]?key|app[_-]?secret|aes|des|rsa|bearer|authorization)[^\r\n]{0,80}"""
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawWs = args["workspace"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val wsDir = if (rawWs != null) Sandbox.resolve(File(rawWs), ctx.workspace) else ctx.workspace
        val rawQuery = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val searchType = args["search_type"]?.jsonPrimitive?.contentOrNull ?: "all"
        val maxResults = (args["max_results"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 500)

        if (!wsDir.exists()) {
            return ToolResult(false, "工作目录不存在: ${wsDir.absolutePath}")
        }

        val regex: Regex = try {
            when {
                searchType == "urls" && rawQuery.isBlank() -> URL_REGEX
                searchType == "keys" && rawQuery.isBlank() -> KEY_REGEX
                rawQuery.isBlank() -> return ToolResult(false, L("缺少 query 参数", "Missing query parameter"))
                else -> Regex(rawQuery, RegexOption.IGNORE_CASE)
            }
        } catch (_: Exception) {
            Regex(Regex.escape(rawQuery), RegexOption.IGNORE_CASE)
        }

        val results = mutableListOf<String>()

        val smaliFiles = wsDir.walkTopDown()
            .onEnter { it.name != ".snapshots" && it.name != "audit" && it.name != "projects" }
            .filter { it.isFile && it.extension == "smali" }
            .toList()

        // 1. 搜索 smali 文件
        if (searchType in listOf("all", "strings", "classes", "methods", "urls", "keys") && smaliFiles.isNotEmpty()) {
            for (file in smaliFiles) {
                if (results.size >= maxResults) break
                val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
                val relPath = file.relativeToOrSelf(wsDir).path
                lines.forEachIndexed { idx, line ->
                    if (results.size >= maxResults) return@forEachIndexed
                    val shouldCheck = when (searchType) {
                        "strings" -> line.contains("const-string")
                        "classes" -> line.startsWith(".class")
                        "methods" -> line.startsWith(".method")
                        "urls" -> line.contains("http://", ignoreCase = true) || line.contains("https://", ignoreCase = true)
                        "keys" -> line.contains("const-string") || line.startsWith(".field")
                        else -> true
                    }
                    if (shouldCheck && regex.containsMatchIn(line)) {
                        results.add("[$relPath:${idx + 1}] ${line.trim().take(180)}")
                    }
                }
            }
        }

        // 2. 搜索资源与配置文件（含二进制 AXML 自动解码）
        if (searchType in listOf("all", "resources", "urls", "keys") && results.size < maxResults) {
            val resFiles = wsDir.walkTopDown()
                .onEnter { it.name != ".snapshots" && it.name != "audit" && it.name != "projects" }
                .filter { it.isFile && it.extension.lowercase() in listOf("xml", "json", "properties", "txt", "js", "html") }
                .toList()

            for (file in resFiles) {
                if (results.size >= maxResults) break
                val text = runCatching {
                    val bytes = file.readBytes()
                    if (file.extension.equals("xml", ignoreCase = true) && AxmlParser.isBinaryAxml(bytes)) {
                        AxmlParser.parseCached(bytes)
                    } else {
                        String(bytes, Charsets.UTF_8)
                    }
                }.getOrDefault("")
                val relPath = file.relativeToOrSelf(wsDir).path
                text.lineSequence().forEachIndexed { idx, line ->
                    if (results.size < maxResults && regex.containsMatchIn(line)) {
                        results.add("[$relPath:${idx + 1}] ${line.trim().take(180)}")
                    }
                }
            }
        }

        // 3. 若工作区尚未解包/反编译，自动直接扫描当前打开的 APK (DEX 类名、DEX 字符串池、AndroidManifest.xml)
        val fallbackApk = ctx.openApk ?: File(wsDir, "source/base.apk").takeIf { it.exists() } ?: File(wsDir, "base.apk").takeIf { it.exists() }
        if (results.size < maxResults && fallbackApk != null && fallbackApk.exists()) {
            runCatching {
                ZipFile(fallbackApk).use { zip ->
                    if (searchType in listOf("all", "resources", "urls", "keys")) {
                        val manifestEntry = zip.getEntry("AndroidManifest.xml")
                        if (manifestEntry != null) {
                            val xml = AxmlParser.parseCached(zip.getInputStream(manifestEntry).readBytes())
                            xml.lineSequence().forEachIndexed { idx, line ->
                                if (results.size < maxResults && regex.containsMatchIn(line)) {
                                    results.add("[APK:AndroidManifest.xml:${idx + 1}] ${line.trim().take(180)}")
                                }
                            }
                        }
                    }

                    if (searchType in listOf("all", "strings", "classes", "urls", "keys") && smaliFiles.isEmpty()) {
                        val dexEntries = zip.entries().asSequence().filter { it.name.endsWith(".dex") }.toList()
                        for (dexEntry in dexEntries) {
                            if (results.size >= maxResults) break
                            val bytes = zip.getInputStream(dexEntry).use { it.readBytes() }
                            val dexInfo = DexParser.parseCached(bytes)

                            if (searchType in listOf("all", "classes")) {
                                for (cls in dexInfo.classes) {
                                    if (results.size >= maxResults) break
                                    if (regex.containsMatchIn(cls)) {
                                        results.add("[APK:${dexEntry.name}:class] $cls")
                                    }
                                }
                            }

                            if (searchType in listOf("all", "strings", "urls", "keys")) {
                                for (str in dexInfo.strings) {
                                    if (results.size >= maxResults) break
                                    val candidate = when (searchType) {
                                        "urls" -> str.contains("http://", ignoreCase = true) || str.contains("https://", ignoreCase = true)
                                        else -> str.length in 3..500
                                    }
                                    if (candidate && regex.containsMatchIn(str)) {
                                        results.add("[APK:${dexEntry.name}:string] ${str.replace("\n", "\\n").take(180)}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val output = if (results.isEmpty()) {
            "未找到匹配 \"${rawQuery.ifBlank { searchType }}\" 的结果（搜索类型: $searchType）"
        } else {
            buildString {
                appendLine("找到 ${results.size} 条结果（搜索类型: $searchType）:")
                appendLine()
                results.forEach { appendLine(it) }
                if (results.size >= maxResults) {
                    appendLine()
                    appendLine(L("（已达上限 $maxResults 条，可调整 max_results 或细化关键词）", "(Reached limit of $maxResults results)"))
                }
            }
        }

        return ToolResult(true, output)
    }
}
