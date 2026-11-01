package com.apkagent.apktools.smali

import com.apkagent.i18n.L
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.zip.ZipFile

/**
 * 签名校验调用点扫描 + Patch 引擎。
 *
 * 修复说明：
 * 1. 修正 PackageManager 类描述符为 Landroid/content/pm/PackageManager;（原误写为 Landroid/app/PackageManager;）。
 * 2. 补充 Signature->toByteArray / toCharsString / hashCode 等核心签名提取特征。
 * 3. 避免对全应用所有 String.equals() 产生海量误报，仅在包含签名特征的类中关联标记比对逻辑。
 * 4. 修复 patchMethodReturnTrue 中正则替换将方法后续全部文件文本重复拼接两遍（导致回编译必败）及 .locals 0 寄存器越界 Bug。
 */
object SignatureCheckerScanner {

    data class ScanResult(
        val success: Boolean,
        val message: String,
        val hits: List<CheckHit>,
        val dexFile: File? = null
    )

    data class CheckHit(
        val className: String,
        val methodName: String,
        val pattern: String,
        val detail: String,
        val patchable: Boolean = true
    )

    /** 核心签名校验方法模式（直接命中） */
    private val PRIMARY_PATTERNS = listOf(
        Pattern("getPackageInfo", "PackageManager;->getPackageInfo(", "GET_SIGN"),
        Pattern("hasSignature", "Landroid/content/pm/SigningInfo;->hasSignature", "HAS_SIG"),
        Pattern("hasMultipleSigners", "Landroid/content/pm/SigningInfo;->hasMultipleSigners", "MULTI_SIG"),
        Pattern("getApkContentsSigners", "Landroid/content/pm/SigningInfo;->getApkContentsSigners", "SIGNERS"),
        Pattern("getSigningCertificateHistory", "Landroid/content/pm/SigningInfo;->getSigningCertificateHistory", "CERT_HIST"),
        Pattern("Signature.toByteArray", "Landroid/content/pm/Signature;->toByteArray()[B", "SIG_BYTES"),
        Pattern("Signature.toCharsString", "Landroid/content/pm/Signature;->toCharsString()Ljava/lang/String;", "SIG_CHARS"),
        Pattern("Signature.hashCode", "Landroid/content/pm/Signature;->hashCode()I", "SIG_HASH")
    )

    /** 辅助比对模式（仅在同方法或同类已命中核心签名特征时才记录，杜绝全包误报） */
    private val SECONDARY_PATTERNS = listOf(
        Pattern("equals", "Ljava/lang/String;->equals(Ljava/lang/Object;)Z", "EQUALS"),
        Pattern("Arrays.equals", "Ljava/util/Arrays;->equals([B[B)Z", "ARR_EQUALS"),
        Pattern("digest", "Ljava/security/MessageDigest;->digest", "DIGEST")
    )

    private data class Pattern(val name: String, val sig: String, val tag: String)

    fun scanDex(dexFile: File): ScanResult {
        if (!dexFile.exists()) return ScanResult(false, L("DEX 文件不存在", "DEX file does not exist"), emptyList())
        return try {
            val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
            val hits = mutableListOf<CheckHit>()
            for (cls in dex.classes) {
                val className = clsToName(cls.type)
                val classHits = mutableListOf<CheckHit>()
                val secondaryCandidates = mutableListOf<CheckHit>()
                for (method in cls.methods) {
                    scanMethod(method, className, classHits, secondaryCandidates)
                }
                if (classHits.isNotEmpty()) {
                    hits.addAll(classHits)
                    // 仅当该类已存在真实签名获取行为时，才纳入同类的比对调用点
                    hits.addAll(secondaryCandidates)
                }
            }
            val msg = if (hits.isEmpty()) L("未发现签名校验调用点", "No signature-check call sites found")
            else "发现 ${hits.size} 处疑似签名校验调用点"
            ScanResult(true, msg, hits, dexFile)
        } catch (e: Throwable) {
            ScanResult(false, "扫描失败：${e.message}", emptyList())
        }
    }

    /**
     * 并行扫描 APK 中所有 DEX 文件的签名校验调用点。
     */
    suspend fun scanApk(apkFile: File): ScanResult = coroutineScope {
        val allHits = mutableListOf<CheckHit>()
        var dexCount = 0
        try {
            val dexFiles = mutableListOf<Pair<String, File>>()
            ZipFile(apkFile).use { zip ->
                val dexEntries = zip.entries().toList().filter { it.name.endsWith(".dex") }
                for (entry in dexEntries) {
                    val tmp = File.createTempFile(entry.name.replace("/", "_"), ".dex")
                    zip.getInputStream(entry).use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    dexFiles.add(entry.name to tmp)
                }
            }

            val results = dexFiles.map { (name, tmp) ->
                async(Dispatchers.IO) {
                    val r = scanDex(tmp)
                    try { tmp.delete() } catch (_: Throwable) {}
                    Triple(name, r.success, r.hits)
                }
            }.awaitAll()

            for ((_, success, hits) in results) {
                if (success) {
                    allHits.addAll(hits)
                    dexCount++
                }
            }
            val msg = if (allHits.isEmpty()) "扫描 $dexCount 个 DEX，未发现签名校验调用点"
            else "扫描 $dexCount 个 DEX，发现 ${allHits.size} 处疑似签名校验调用点"
            ScanResult(true, msg, allHits)
        } catch (e: Throwable) {
            ScanResult(false, "APK 扫描失败：${e.message}", emptyList())
        }
    }

    /**
     * 并行 patch：对所有 DEX 并行反编译→修改 smali→回编译。
     */
    suspend fun patchAllDex(
        dexFiles: List<Pair<String, File>>,
        outputDir: File
    ): String = coroutineScope {
        dexFiles.map { (name, tmp) ->
            async(Dispatchers.IO) {
                val scan = scanDex(tmp)
                if (scan.success && scan.hits.isNotEmpty()) {
                    val outDex = File(outputDir, name)
                    val pr = patchDex(tmp, outDex, PatchMode.METHOD_RETURN_TRUE)
                    "$name: ${scan.hits.size} 处调用点, ${pr.message}"
                } else {
                    "$name: 无校验调用点，跳过"
                }
            }
        }.awaitAll().joinToString("\n")
    }

    private fun scanMethod(
        method: Method,
        className: String,
        primaryHits: MutableList<CheckHit>,
        secondaryHits: MutableList<CheckHit>
    ) {
        val impl = method.implementation ?: return
        val instructions = impl.instructions
        for (inst in instructions) {
            if (inst is ReferenceInstruction && inst.reference is MethodReference) {
                val ref = inst.reference as MethodReference
                val fullSig = "${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}"
                for (p in PRIMARY_PATTERNS) {
                    if (fullSig.contains(p.sig)) {
                        primaryHits.add(
                            CheckHit(
                                className = className,
                                methodName = method.name,
                                pattern = p.tag,
                                detail = "${p.name}: ${ref.definingClass}->${ref.name} (在 ${method.name} 方法中)",
                                patchable = true
                            )
                        )
                    }
                }
                for (p in SECONDARY_PATTERNS) {
                    if (fullSig.contains(p.sig)) {
                        secondaryHits.add(
                            CheckHit(
                                className = className,
                                methodName = method.name,
                                pattern = p.tag,
                                detail = "${p.name}: ${ref.definingClass}->${ref.name} (在 ${method.name} 方法中)",
                                patchable = true
                            )
                        )
                    }
                }
            }
            if (inst is ReferenceInstruction && inst.reference is FieldReference) {
                val fref = inst.reference as FieldReference
                if (fref.name == "signatures" && fref.definingClass == "Landroid/content/pm/PackageInfo;") {
                    primaryHits.add(
                        CheckHit(
                            className = className,
                            methodName = method.name,
                            pattern = "SIG_FIELD",
                            detail = L("访问 PackageInfo.signatures 字段", "Access PackageInfo.signatures field"),
                            patchable = true
                        )
                    )
                }
                if (fref.name == "signingInfo" && fref.definingClass == "Landroid/content/pm/PackageInfo;") {
                    primaryHits.add(
                        CheckHit(
                            className = className,
                            methodName = method.name,
                            pattern = "SIGINFO_FIELD",
                            detail = L("访问 PackageInfo.signingInfo 字段", "Access PackageInfo.signingInfo field"),
                            patchable = true
                        )
                    )
                }
            }
        }
    }

    /**
     * Patch：将签名校验结果改为「通过」。
     */
    fun patchDex(dexFile: File, outputDex: File, mode: PatchMode = PatchMode.METHOD_RETURN_TRUE): PatchResult {
        return try {
            val scan = scanDex(dexFile)
            if (!scan.success) return PatchResult(false, scan.message, 0)
            if (scan.hits.isEmpty()) return PatchResult(false, L("无校验调用点，无需 patch", "No signature check found, no patch needed"), 0)

            val tmpDir = File(dexFile.parentFile, "_smali_patch_${System.currentTimeMillis()}")
            tmpDir.mkdirs()
            val disasm = SmaliEngine.disassembleDex(dexFile, tmpDir)
            if (!disasm.success) {
                tmpDir.deleteRecursively()
                return PatchResult(false, L("反编译失败：${disasm.message}", "Decompilation failed: ${disasm.message}"), 0)
            }

            val targetMethods = scan.hits.map { it.className to it.methodName }.toSet()
            var patched = 0
            tmpDir.walkTopDown().filter { it.extension == "smali" }.forEach { sf ->
                val text = sf.readText()
                val clsName = smaliFileToClassName(sf, tmpDir)
                var modified = text
                for ((className, methodName) in targetMethods) {
                    if (clsName == className || clsName.replace('/', '.') == className.replace('/', '.')) {
                        modified = patchMethodReturnTrue(modified, methodName)
                    }
                }
                if (modified != text) {
                    sf.writeText(modified)
                    patched++
                }
            }

            val asm = SmaliEngine.assembleSmali(tmpDir, outputDex)
            tmpDir.deleteRecursively()
            if (asm.success) {
                PatchResult(true, "已 patch $patched 个类的方法（返回 true），回编译成功", patched)
            } else {
                PatchResult(false, "patch 完成 $patched 个但回编译失败：${asm.message}", patched)
            }
        } catch (e: Throwable) {
            PatchResult(false, "Patch 失败：${e.message}", 0)
        }
    }

    /**
     * 将指定返回布尔值 (Z) 的方法在方法体开头插入 const/4 v0, 0x1; return v0。
     * 仅替换单个方法内部，绝不重复拼接后续文件内容，并确保 .locals >= 1。
     */
    internal fun patchMethodReturnTrue(smali: String, methodName: String): String {
        if (methodName == "<init>" || methodName == "<clinit>") return smali
        val methodBlockRegex = Regex(
            """(\.method\s+[^\n]*\b${Regex.escape(methodName)}\s*\([^\n]*\)\s*Z\s*\r?\n)([\s\S]*?)(\.end method)"""
        )
        return methodBlockRegex.replace(smali) { mr ->
            val header = mr.groupValues[1]
            var body = mr.groupValues[2]
            val footer = mr.groupValues[3]

            // 抽象方法或 native 方法无指令体，不可插入
            if (header.contains(" abstract ") || header.contains(" native ")) {
                return@replace mr.value
            }
            // 已插入过则跳过
            if (body.contains("const/4 v0, 0x1\n    return v0")) {
                return@replace mr.value
            }

            // 若 .locals 0 或 .registers 0，则提升至 1 以保证 v0 可用
            body = body.replace(Regex("""\.locals\s+0\b"""), ".locals 1")
                .replace(Regex("""\.registers\s+0\b"""), ".registers 1")

            val localsRegex = Regex("""([ \t]*\.(?:registers|locals)\s+\d+\s*\r?\n)""")
            val localsMatch = localsRegex.find(body)
            val newBody = if (localsMatch != null) {
                val insertPos = localsMatch.range.last + 1
                body.substring(0, insertPos) +
                    "    const/4 v0, 0x1\n    return v0\n" +
                    body.substring(insertPos)
            } else {
                "    .locals 1\n    const/4 v0, 0x1\n    return v0\n" + body
            }
            header + newBody + footer
        }
    }

    private fun smaliFileToClassName(file: File, root: File): String {
        val rel = root.toURI().relativize(file.toURI()).path
        return rel.removeSuffix(".smali").replace('/', '.')
    }

    enum class PatchMode {
        METHOD_RETURN_TRUE,
        INSTRUCTION_LEVEL
    }

    data class PatchResult(
        val success: Boolean,
        val message: String,
        val patchedCount: Int
    )

    private fun clsToName(type: String): String =
        if (type.startsWith("L") && type.endsWith(";"))
            type.substring(1, type.length - 1).replace('/', '.')
        else type
}
