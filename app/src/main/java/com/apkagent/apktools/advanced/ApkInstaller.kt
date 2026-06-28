1|package com.apkagent.apktools.advanced
2|
3|import com.apkagent.agent.Tool
4|import com.apkagent.agent.ToolContext
5|import com.apkagent.agent.ToolResult
6|import com.apkagent.agent.strProp
7|import com.apkagent.agent.schemaObject
8|import com.apkagent.apktools.str
9|import com.apkagent.apktools.int
10|import com.apkagent.apktools.bool
11|import com.apkagent.shizuku.ShizukuManager
import android.content.Context
12|import kotlinx.serialization.json.JsonObject
13|import java.io.File
14|import java.util.zip.ZipFile
15|
16|/**
17| * APK 安装器：支持普通 APK 和 Split APK 安装。
18| *
19| * 功能：
20| * 1. 普通 APK 安装（通过 Shizuku 或系统 intent）
21| * 2. Split APK 安装（通过 pm install-create/write/commit）
22| * 3. App Bundle 安装（先解压再安装分片）
23| * 4. 安装前验证（签名、版本、权限）
24| * 5. 安装后状态检查
25| */
26|object ApkInstaller : Tool {
27|    override val name = "apk_install"
28|    override val description = "安装 APK（支持普通 APK 和 Split APK）。使用 Shizuku 提权安装，无需用户确认。"
29|    override val parameters = schemaObject(
30|        mapOf(
31|            "apk_path" to strProp("APK 文件路径（或 Bundle 路径 .apks/.apkm/.xapk）"),
32|            "action" to strProp("操作：install（安装）/ verify（仅验证）/ info（查看安装信息）")
33|        )
34|    )
35|
36|    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
37|        val apkPath = args.str("apk_path") ?: return ToolResult(false, "缺少 apk_path")
38|        val action = args.str("action") ?: "install"
39|
40|        val apkFile = File(apkPath)
41|        if (!apkFile.exists()) return ToolResult(false, "文件不存在: $apkPath")
42|
43|        return when (action) {
44|            "verify" -> verifyApk(apkFile)
45|            "info" -> getInstallInfo(apkFile)
46|            "install" -> installApk(apkFile)
47|            else -> ToolResult(false, "未知操作: $action，支持 install/verify/info")
48|        }
49|    }
50|
51|    private fun verifyApk(apkFile: File): ToolResult {
52|        val sb = StringBuilder()
53|        sb.appendLine("🔍 APK 安装验证：")
54|        sb.appendLine()
55|
56|        // 检查文件类型
57|        val fileType = when {
58|            apkFile.name.endsWith(".apk") -> "普通 APK"
59|            apkFile.name.endsWith(".apks") -> "Split APK Bundle"
60|            apkFile.name.endsWith(".apkm") -> "APKMirror Bundle"
61|            apkFile.name.endsWith(".xapk") -> "APKPure Bundle"
62|            else -> "未知格式"
63|        }
64|        sb.appendLine("📦 文件类型: $fileType")
65|        sb.appendLine("📁 文件大小: ${formatSize(apkFile.length())}")
66|        sb.appendLine()
67|
68|        // 检查签名
69|        try {
70|            val verifier = com.android.apksig.ApkVerifier.Builder(apkFile).build()
71|            val result = verifier.verify()
72|            if (result.isVerified) {
73|                sb.appendLine("✅ 签名验证: 通过")
74|            } else {
75|                sb.appendLine("❌ 签名验证: 失败")
76|                result.errors.forEach { sb.appendLine("  • $it") }
77|            }
78|        } catch (e: Exception) {
79|            sb.appendLine("⚠️ 签名验证: 无法验证 (${e.message})")
80|        }
81|
82|        // 检查是否为 Split APK
83|        if (fileType != "普通 APK") {
84|            try {
85|                val splits = listSplits(apkFile)
86|                sb.appendLine()
87|                sb.appendLine("📦 Split APK 分片:")
88|                splits.forEach { sb.appendLine("  • ${it.first} (${formatSize(it.second)})") }
89|            } catch (e: Exception) {
90|                sb.appendLine("⚠️ 无法解析分片: ${e.message}")
91|            }
92|        }
93|
94|        return ToolResult(true, sb.toString())
95|    }
96|
97|    private fun getInstallInfo(apkFile: File): ToolResult {
98|        val sb = StringBuilder()
99|        sb.appendLine("📋 APK 安装信息：")
100|        sb.appendLine()
101|
102|        try {
103|            // 读取 AndroidManifest.xml 获取包信息
104|            val manifestData = extractManifest(apkFile)
105|            if (manifestData != null) {
106|                val parser = com.apkagent.apktools.AxmlParser(manifestData)
107|                val xml = parser.parse()
108|
109|                // 提取关键信息
110|                val packageName = extractAttribute(xml, "package")
111|                val versionName = extractAttribute(xml, "android:versionName")
112|                val versionCode = extractAttribute(xml, "android:versionCode")
113|                val minSdk = extractAttribute(xml, "android:minSdkVersion")
114|                val targetSdk = extractAttribute(xml, "android:targetSdkVersion")
115|
116|                sb.appendLine("📦 包名: ${packageName ?: "未知"}")
117|                sb.appendLine("📌 版本: ${versionName ?: "未知"} (${versionCode ?: "未知"})")
118|                sb.appendLine("📱 最低 SDK: ${minSdk ?: "未知"}")
119|                sb.appendLine("🎯 目标 SDK: ${targetSdk ?: "未知"}")
120|                sb.appendLine()
121|
122|                // 提取权限
123|                val permissions = extractPermissionsFromXml(xml)
124|                if (permissions.isNotEmpty()) {
125|                    sb.appendLine("🔐 权限 (${permissions.size} 个):")
126|                    permissions.take(10).forEach { sb.appendLine("  • $it") }
127|                    if (permissions.size > 10) {
128|                        sb.appendLine("  ... 还有 ${permissions.size - 10} 个权限")
129|                    }
130|                }
131|            } else {
132|                sb.appendLine("⚠️ 无法读取 AndroidManifest.xml")
133|            }
134|        } catch (e: Exception) {
135|            sb.appendLine("❌ 解析失败: ${e.message}")
136|        }
137|
138|        return ToolResult(true, sb.toString())
139|    }
140|
141|    private fun installApk(apkFile: File): ToolResult {
142|        return try {
143|            // 检查 Shizuku 是否可用
144|            if (!ShizukuManager.isRunning() || !ShizukuManager.isAuthorized()) {
145|                return ToolResult(false, "❌ Shizuku 不可用，无法安装 APK\n请先启动 Shizuku 服务")
146|            }
147|
148|            when {
149|                apkFile.name.endsWith(".apk") -> installSingleApk(apkFile)
150|                apkFile.name.endsWith(".apks") || apkFile.name.endsWith(".apkm") || apkFile.name.endsWith(".xapk") -> installBundle(apkFile)
151|                else -> ToolResult(false, "不支持的文件格式: ${apkFile.name}")
152|            }
153|        } catch (e: Exception) {
154|            ToolResult(false, "❌ 安装失败: ${e.message}")
155|        }
156|    }
157|
158|    private fun installSingleApk(apkFile: File): ToolResult {
159|        val result = ShizukuManager.execAndGet("pm install -r -d \"${apkFile.absolutePath}\"") ?: "null"
160|        return if (result.contains("Success")) {
161|            ToolResult(true, "✅ APK 安装成功\n${apkFile.name}")
162|        } else {
163|            ToolResult(false, "❌ APK 安装失败\n$result")
164|        }
165|    }
166|
167|    private fun installBundle(bundleFile: File): ToolResult {
168|        // 先解压 Bundle
169|        val tempDir = File(bundleFile.parent, "temp_install_${System.currentTimeMillis()}")
170|        tempDir.mkdirs()
171|
172|        try {
173|            ZipFile(bundleFile).use { zip ->
174|                zip.entries().toList().forEach { entry ->
175|                    if (entry.name.endsWith(".apk")) {
176|                        val outFile = File(tempDir, entry.name)
177|                        outFile.parentFile?.mkdirs()
178|                        zip.getInputStream(entry).use { input ->
179|                            outFile.outputStream().use { output ->
180|                                input.copyTo(output)
181|                            }
182|                        }
183|                    }
184|                }
185|            }
186|
187|            // 查找 base.apk
188|            val baseApk = File(tempDir, "base.apk")
189|            if (!baseApk.exists()) {
190|                return ToolResult(false, "Bundle 中未找到 base.apk")
191|            }
192|
193|            // 使用 pm install-create/write/commit 安装 Split APK
194|            val apkFiles = tempDir.listFiles()?.filter { it.name.endsWith(".apk") } ?: emptyList()
195|            val totalSize = apkFiles.sumOf { it.length() }
196|
197|            // 创建安装会话
198|            val sessionResult = ShizukuManager.execAndGet("pm install-create -S $totalSize") ?: "null"
199|            val sessionId = Regex("""sessionId=(\d+)""").find(sessionResult)?.groupValues?.get(1)
200|                ?: return ToolResult(false, "创建安装会话失败\n$sessionResult")
201|
202|            // 写入每个分片
203|            for (apk in apkFiles) {
204|                val writeResult = ShizukuManager.execAndGet("pm install-write $sessionId ${apk.name} \"${apk.absolutePath}\"") ?: "null"
205|                if (!writeResult.contains("Success")) {
206|                    return ToolResult(false, "写入分片失败: ${apk.name}\n$writeResult")
207|                }
208|            }
209|
210|            // 提交安装
211|            val commitResult = ShizukuManager.execAndGet("pm install-commit $sessionId") ?: "null"
212|            return if (commitResult.contains("Success")) {
213|                ToolResult(true, "✅ Split APK 安装成功\n分片数量: ${apkFiles.size}")
214|            } else {
215|                ToolResult(false, "❌ Split APK 安装失败\n$commitResult")
216|            }
217|        } catch (e: Exception) {
218|            return ToolResult(false, "❌ 安装异常: ${e.message}")
219|        } finally {
220|            // 清理临时目录
221|            tempDir.deleteRecursively()
222|        }
223|    }
224|
225|    private fun listSplits(bundleFile: File): List<Pair<String, Long>> {
226|        val splits = mutableListOf<Pair<String, Long>>()
227|        ZipFile(bundleFile).use { zip ->
228|            zip.entries().toList().forEach { entry ->
229|                if (entry.name.endsWith(".apk")) {
230|                    splits.add(entry.name to entry.size)
231|                }
232|            }
233|        }
234|        return splits
235|    }
236|
237|    private fun extractManifest(apkFile: File): ByteArray? {
238|        ZipFile(apkFile).use { zip ->
239|            val entry = zip.getEntry("AndroidManifest.xml") ?: return null
240|            return zip.getInputStream(entry).readBytes()
241|        }
242|    }
243|
244|    private fun extractAttribute(xml: String, attribute: String): String? {
245|        val regex = Regex("""$attribute="([^"]+)"""")
246|        return regex.find(xml)?.groupValues?.get(1)
247|    }
248|
249|    private fun extractPermissionsFromXml(xml: String): List<String> {
250|        val permissions = mutableListOf<String>()
251|        val regex = Regex("""android:name="([^"]*permission[^"]*)"""", RegexOption.IGNORE_CASE)
252|        regex.findAll(xml).forEach { match ->
253|            permissions.add(match.groupValues[1])
254|        }
255|        return permissions
256|    }
257|
258|    private fun formatSize(bytes: Long): String {
259|        return when {
260|            bytes < 1024 -> "$bytes B"
261|            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
262|            else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
263|        }
264|    }
265|}
266|