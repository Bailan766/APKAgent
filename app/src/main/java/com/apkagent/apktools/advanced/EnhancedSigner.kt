1|package com.apkagent.apktools.advanced
2|
3|import com.android.apksig.ApkSigner
4|import com.android.apksig.ApkVerifier
5|import com.apkagent.agent.Tool
6|import com.apkagent.agent.ToolContext
7|import com.apkagent.agent.ToolResult
8|import com.apkagent.agent.strProp
9|import com.apkagent.agent.schemaObject
10|import com.apkagent.apktools.str
11|import com.apkagent.apktools.int
12|import com.apkagent.apktools.bool
13|import com.apkagent.apktools.smali.DebugKeyProvider
14|import kotlinx.serialization.json.JsonObject
15|import java.io.File
16|import java.security.KeyStore
17|
18|/**
19| * 增强版签名工具：支持 v1/v2/v3/v4 签名方案。
20| *
21| * 基于 AEE 的 APKSigner 实现，扩展支持：
22| * 1. v1 签名（JAR/PKCS#7）
23| * 2. v2 签名（APK Signature Scheme v2）
24| * 3. v3 签名（APK Signature Scheme v3，支持密钥轮换）
25| * 4. v4 签名（APK Signature Scheme v4，增量安装）
26| * 5. 自定义 keystore 支持
27| * 6. 签名验证
28| * 7. 多种签名方案组合
29| */
30|object EnhancedSigner : Tool {
31|    override val name = "apk_enhanced_sign"
32|    override val description = "增强版 APK 签名，支持 v1/v2/v3/v4 签名方案，支持自定义 keystore 和签名验证。"
33|    override val parameters = schemaObject(
34|        mapOf(
35|            "apk_path" to strProp("APK 文件路径"),
36|            "output_path" to strProp("输出路径（默认覆盖原文件）"),
37|            "keystore_path" to strProp("Keystore 文件路径（可选，默认使用内置调试密钥）"),
38|            "keystore_pass" to strProp("Keystore 密码（可选）"),
39|            "key_alias" to strProp("密钥别名（可选）"),
40|            "key_pass" to strProp("密钥密码（可选）"),
41|            "sign_schemes" to strProp("签名方案，逗号分隔（默认 v1,v2,v3,v4）"),
42|            "verify_only" to strProp("仅验证签名，不重新签名（true/false）")
43|        )
44|    )
45|
46|    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
47|        val apkPath = args.str("apk_path") ?: return ToolResult(false, "缺少 apk_path")
48|        val outputPath = args.str("output_path")
49|        val keystorePath = args.str("keystore_path")
50|        val keystorePass = args.str("keystore_pass")
51|        val keyAlias = args.str("key_alias")
52|        val keyPass = args.str("key_pass")
53|        val signSchemesStr = args.str("sign_schemes") ?: "v1,v2,v3,v4"
54|        val verifyOnly = args.str("verify_only")?.toBoolean() ?: false
55|
56|        val inputApk = File(apkPath)
57|        if (!inputApk.exists()) return ToolResult(false, "文件不存在: $apkPath")
58|
59|        // 验证模式
60|        if (verifyOnly) {
61|            return verifySignature(inputApk)
62|        }
63|
64|        // 签名模式
65|        val outputApk = if (outputPath != null) File(outputPath) else File(inputApk.parent, "${inputApk.nameWithoutExtension}_signed.apk")
66|
67|        // 解析签名方案
68|        val schemes = signSchemesStr.split(",").map { it.trim().lowercase() }
69|        val v1 = schemes.contains("v1")
70|        val v2 = schemes.contains("v2")
71|        val v3 = schemes.contains("v3")
72|        val v4 = schemes.contains("v4")
73|
74|        return try {
75|            val result = signApk(inputApk, outputApk, v1, v2, v3, v4, keystorePath, keystorePass, keyAlias, keyPass)
76|            if (result.success) {
77|                ToolResult(true, "✅ 签名完成\n${result.message}\n输出: ${outputApk.absolutePath}")
78|            } else {
79|                ToolResult(false, "❌ 签名失败: ${result.message}")
80|            }
81|        } catch (e: Exception) {
82|            ToolResult(false, "❌ 签名异常: ${e.message}")
83|        }
84|    }
85|
86|    private fun signApk(
87|        inputApk: File,
88|        outputApk: File,
89|        v1: Boolean,
90|        v2: Boolean,
91|        v3: Boolean,
92|        v4: Boolean,
93|        keystorePath: String?,
94|        keystorePass: String?,
95|        keyAlias: String?,
96|        keyPass: String?
97|    ): SignResult {
98|        try {
99|            // 准备密钥
100|            val ks: KeyStore
101|            val alias: String
102|            val kPass: String
103|            val ksPass: String
104|
105|            if (keystorePath != null && keystorePass != null && keyAlias != null) {
106|                // 使用自定义 keystore
107|                val ksFile = File(keystorePath)
108|                if (!ksFile.exists()) return SignResult(false, "Keystore 文件不存在: $keystorePath")
109|
110|                ks = KeyStore.getInstance(ksFile, keystorePass.toCharArray())
111|                alias = keyAlias
112|                kPass = keyPass ?: keystorePass
113|                ksPass = keystorePass
114|            } else {
115|                // 使用内置调试密钥
116|                val debugKey = DebugKeyProvider.getOrCreate()
117|                ks = java.security.KeyStore.getInstance(debugKey.keystoreFile, debugKey.storePass.toCharArray())
118|                alias = debugKey.alias
119|                kPass = debugKey.keyPass
120|                ksPass = debugKey.storePass
121|            }
122|
123|            // 获取私钥和证书链
124|            val privateKey = ks.getKey(alias, kPass.toCharArray()) as java.security.PrivateKey
125|            val certChain = ks.getCertificateChain(alias).map { it as java.security.cert.X509Certificate }
126|
127|            // 配置签名
128|            val signerConfig = ApkSigner.SignerConfig.Builder(
129|                "APKAgent",
130|                privateKey,
131|                certChain
132|            ).build()
133|
134|            val builder = ApkSigner.Builder(listOf(signerConfig))
135|            builder.setInputApk(inputApk)
136|            builder.setOutputApk(outputApk)
137|            builder.setCreatedBy("APKAgent v3.16.0")
138|
139|            // 设置签名方案
140|            builder.setV1SigningEnabled(v1)
141|            builder.setV2SigningEnabled(v2)
142|            builder.setV3SigningEnabled(v3)
143|            builder.setV4SigningEnabled(v4)
144|
145|            builder.setMinSdkVersion(-1) // 使用原始 APK 的 minSdkVersion
146|
147|            // 执行签名
148|            val signer = builder.build()
149|            signer.sign()
150|
151|            // 验证签名
152|            val verifier = ApkVerifier.Builder(outputApk).build()
153|            val result = verifier.verify()
154|
155|            if (result.isVerified) {
156|                val sb = StringBuilder()
157|                sb.appendLine("签名方案:")
158|                if (v1) sb.appendLine("  • v1 (JAR/PKCS#7)")
159|                if (v2) sb.appendLine("  • v2 (APK Signature Scheme v2)")
160|                if (v3) sb.appendLine("  • v3 (APK Signature Scheme v3)")
161|                if (v4) sb.appendLine("  • v4 (APK Signature Scheme v4)")
162|                sb.appendLine("证书信息:")
163|                sb.appendLine("  颁发者: ${certChain[0].issuerX500Principal.name}")
164|                sb.appendLine("  使用者: ${certChain[0].subjectX500Principal.name}")
165|                sb.appendLine("  有效期: ${certChain[0].notBefore} - ${certChain[0].notAfter}")
166|
167|                return SignResult(true, sb.toString())
168|            } else {
169|                return SignResult(false, "签名验证失败: ${result.warnings}")
170|            }
171|        } catch (e: Exception) {
172|            return SignResult(false, "签名失败: ${e.message}")
173|        }
174|    }
175|
176|    private fun verifySignature(apkFile: File): ToolResult {
177|        return try {
178|            val verifier = ApkVerifier.Builder(apkFile).build()
179|            val result = verifier.verify()
180|
181|            val sb = StringBuilder()
182|            sb.appendLine("🔍 APK 签名验证结果：")
183|            sb.appendLine()
184|
185|            if (result.isVerified) {
186|                sb.appendLine("✅ 签名验证通过")
187|                sb.appendLine()
188|
189|                // 签名方案信息
190|                sb.appendLine("📋 签名方案:")
191|                if (result.isV1SchemeVerified) sb.appendLine("  • v1 (JAR/PKCS#7) ✅")
192|                if (result.isV2SchemeVerified) sb.appendLine("  • v2 (APK Signature Scheme v2) ✅")
193|                if (result.isV3SchemeVerified) sb.appendLine("  • v3 (APK Signature Scheme v3) ✅")
194|                if (result.isV4SchemeVerified) sb.appendLine("  • v4 (APK Signature Scheme v4) ✅")
195|
196|                // 证书信息
197|                val signers = result.signers
198|                if (signers.isNotEmpty()) {
199|                    sb.appendLine()
200|                    sb.appendLine("📜 证书信息:")
201|                    signers.forEachIndexed { index, signer ->
202|                        sb.appendLine("  签名者 ${index + 1}:")
203|                        signer.certs.forEach { cert ->
204|                            sb.appendLine("    颁发者: ${cert.issuerX500Principal.name}")
205|                            sb.appendLine("    使用者: ${cert.subjectX500Principal.name}")
206|                            sb.appendLine("    有效期: ${cert.notBefore} - ${cert.notAfter}")
207|                        }
208|                    }
209|                }
210|
211|                // 警告信息
212|                if (result.warnings.isNotEmpty()) {
213|                    sb.appendLine()
214|                    sb.appendLine("⚠️ 警告:")
215|                    result.warnings.forEach { warning ->
216|                        sb.appendLine("  • $warning")
217|                    }
218|                }
219|
220|                ToolResult(true, sb.toString())
221|            } else {
222|                sb.appendLine("❌ 签名验证失败")
223|                sb.appendLine()
224|                sb.appendLine("错误:")
225|                result.errors.forEach { error ->
226|                    sb.appendLine("  • $error")
227|                }
228|
229|                ToolResult(false, sb.toString())
230|            }
231|        } catch (e: Exception) {
232|            ToolResult(false, "验证失败: ${e.message}")
233|        }
234|    }
235|
236|    private data class SignResult(val success: Boolean, val message: String)
237|}
238|