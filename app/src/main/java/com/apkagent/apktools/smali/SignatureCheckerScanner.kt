package com.apkagent.apktools.smali

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import java.util.zip.ZipFile

/**
 * 签名校验调用点扫描 + Patch 引擎。
 *
 * 识别的校验模式：
 * 1. getPackageInfo(..., GET_SIGNATURES) / getPackageInfo(..., GET_SIGNING_CERTIFICATES)
 * 2. PackageInfo.signatures 字段访问
 * 3. PackageInfo.signingInfo 字段访问
 * 4. SigningInfo.hasSignature / hasMultipleSigners 调用
 * 5. equals 比对签名 / MessageDigest 签名 hash 比对
 *
 * Patch 策略：
 * - hasSignature/hasMultipleSigners 返回值 → 改为 const/true
 * - equals 比对结果 → 改为 const/true
 * - 整体替换校验方法返回值为 true（兜底）
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

    /** 签名校验相关方法签名模式 */
    private val METHOD_PATTERNS = listOf(
        // 获取 PackageInfo
        Pattern("getPackageInfo", "Landroid/app/PackageManager;->getPackageInfo(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;", "GET_SIGN"),
        // signatures 字段
        Pattern("signatures", "Landroid/content/pm/PackageInfo;->signatures:[Landroid/content/pm/Signature;", "SIG_FIELD"),
        // signingInfo 字段
        Pattern("signingInfo", "Landroid/content/pm/PackageInfo;->signingInfo:Landroid/content/pm/SigningInfo;", "SIGINFO_FIELD"),
        // SigningInfo API
        Pattern("hasSignature", "Landroid/content/pm/SigningInfo;->hasSignature(Ljava/lang/String;)Z", "HAS_SIG"),
        Pattern("hasMultipleSigners", "Landroid/content/pm/SigningInfo;->hasMultipleSigners()Z", "MULTI_SIG"),
        // 直接 byte 数组比对
        Pattern("equals", "Ljava/lang/String;->equals(Ljava/lang/Object;)Z", "EQUALS"),
        Pattern("Arrays.equals", "Ljava/util/Arrays;->equals([B[B)Z", "ARR_EQUALS"),
        // MessageDigest
        Pattern("digest", "Ljava/security/MessageDigest;->digest", "DIGEST"),
        // getSystemService("package")
        Pattern("getSystemService", "Landroid/content/Context;->getSystemService(Ljava/lang/String;)Ljava/lang/Object;", "GET_SYS_SVC")
    )

    private data class Pattern(val name: String, val sig: String, val tag: String)

    /** GET_SIGNATURES = 64 (0x40), GET_SIGNING_CERTIFICATES = 134217728 (0x08000000) */
    private const val GET_SIGNATURES = 64
    private const val GET_SIGNING_CERTIFICATES = 0x08000000

    fun scanDex(dexFile: File): ScanResult {
        if (!dexFile.exists()) return ScanResult(false, "DEX 文件不存在", emptyList())
        return try {
            val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
            val hits = mutableListOf<CheckHit>()
            for (cls in dex.classes) {
                val className = clsToName(cls.type)
                for (method in cls.methods) {
                    scanMethod(cls, method, className, hits)
                }
            }
            val msg = if (hits.isEmpty()) "未发现签名校验调用点"
            else "发现 ${hits.size} 处疑似签名校验调用点"
            ScanResult(true, msg, hits, dexFile)
        } catch (e: Throwable) {
            ScanResult(false, "扫描失败：${e.message}", emptyList())
        }
    }

    fun scanApk(apkFile: File): ScanResult {
        val allHits = mutableListOf<CheckHit>()
        var dexCount = 0
        try {
            ZipFile(apkFile).use { zip ->
                val dexEntries = zip.entries().toList().filter { it.name.endsWith(".dex") }
                for (entry in dexEntries) {
                    val tmp = File.createTempFile(entry.name.replace("/", "_"), ".dex")
                    zip.getInputStream(entry).use { it.copyTo(tmp.outputStream()) }
                    val r = scanDex(tmp)
                    if (r.success) {
                        allHits.addAll(r.hits)
                        dexCount++
                    }
                    tmp.delete()
                }
            }
            val msg = if (allHits.isEmpty()) "扫描 $dexCount 个 DEX，未发现签名校验调用点"
            else "扫描 $dexCount 个 DEX，发现 ${allHits.size} 处疑似签名校验调用点"
            return ScanResult(true, msg, allHits)
        } catch (e: Throwable) {
            return ScanResult(false, "APK 扫描失败：${e.message}", emptyList())
        }
    }

    private fun scanMethod(cls: ClassDef, method: Method, className: String, hits: MutableList<CheckHit>) {
        val impl = method.implementation ?: return
        val instructions = impl.instructions
        var prevMethodRef: MethodReference? = null
        for (inst in instructions) {
            // 检查方法调用
            if (inst is ReferenceInstruction && inst.reference is MethodReference) {
                val ref = inst.reference as MethodReference
                val fullSig = "${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}"
                for (p in METHOD_PATTERNS) {
                    if (fullSig.contains(p.sig) || fullSig == p.sig) {
                        hits.add(CheckHit(className, method.name, p.tag,
                            "${p.name}: ${ref.definingClass}->${ref.name} (在 ${method.name} 方法中)",
                            patchable = p.tag in listOf("HAS_SIG", "MULTI_SIG", "EQUALS", "ARR_EQUALS")))
                    }
                }
                prevMethodRef = ref
            }
            // 检查字段访问（signatures / signingInfo）
            if (inst is ReferenceInstruction && inst.reference is FieldReference) {
                val fref = inst.reference as FieldReference
                if (fref.name == "signatures" && fref.definingClass == "Landroid/content/pm/PackageInfo;") {
                    hits.add(CheckHit(className, method.name, "SIG_FIELD",
                        "访问 PackageInfo.signatures 字段", patchable = true))
                }
                if (fref.name == "signingInfo") {
                    hits.add(CheckHit(className, method.name, "SIGINFO_FIELD",
                        "访问 PackageInfo.signingInfo 字段", patchable = true))
                }
            }
        }
    }

    /**
     * Patch：将签名校验结果改为「通过」。
     * 策略：反编译 DEX 为 smali，扫描校验方法，将返回布尔值的校验方法
     * 在方法体开头插入 const/4 v0, 0x1; return v0（直接返回 true），
     * 再回编译为 DEX。
     */
    fun patchDex(dexFile: File, outputDex: File, mode: PatchMode = PatchMode.METHOD_RETURN_TRUE): PatchResult {
        return try {
            val scan = scanDex(dexFile)
            if (!scan.success) return PatchResult(false, scan.message, 0)
            if (scan.hits.isEmpty()) return PatchResult(false, "无校验调用点，无需 patch", 0)

            // 反编译 → 修改 smali → 回编译
            val tmpDir = File(dexFile.parentFile, "_smali_patch_${System.currentTimeMillis()}")
            tmpDir.mkdirs()
            val disasm = SmaliEngine.disassembleDex(dexFile, tmpDir)
            if (!disasm.success) {
                tmpDir.deleteRecursively()
                return PatchResult(false, "反编译失败：${disasm.message}", 0)
            }

            // 收集需要 patch 的类+方法
            val targetMethods = scan.hits.map { it.className to it.methodName }.toSet()
            var patched = 0
            tmpDir.walkTopDown().filter { it.extension == "smali" }.forEach { sf ->
                val text = sf.readText()
                val clsName = smaliFileToClassName(sf, tmpDir)
                var modified = text
                for ((className, methodName) in targetMethods) {
                    if (clsName.contains(className.replace('.', '/')) ||
                        className.replace('.', '/').contains(clsName)) {
                        // 找到 .method ... methodName( ... )Z 并 patch
                        modified = patchMethodReturnTrue(modified, methodName)
                    }
                }
                if (modified != text) {
                    sf.writeText(modified)
                    patched++
                }
            }

            // 回编译
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

    /** 将指定方法的返回值改为 true（对返回 Z/布尔的方法插入 return-true） */
    private fun patchMethodReturnTrue(smali: String, methodName: String): String {
        // 匹配 .method ... methodName(...)...Z ... .end method
        // 在方法体开头插入 const/4 v0, 0x1; return v0
        val pattern = Regex(
            """(\.method\s+[^\n]*\b${Regex.escape(methodName)}\b[^\n]*\)\s*Z\s*\n)""",
            RegexOption.MULTILINE
        )
        return pattern.replace(smali) { mr ->
            val header = mr.groupValues[1]
            // 跳过 .registers/.locals 行后插入
            val rest = smali.substring(mr.range.last + 1)
            val localsMatch = Regex("""\.(?:registers|locals)\s+(\d+)""").find(rest)
            if (localsMatch != null) {
                // 在 .locals 后插入 return true
                val insertPos = localsMatch.range.last + 1
                header + rest.substring(0, insertPos) +
                    "\n    const/4 v0, 0x1\n    return v0\n" + rest.substring(insertPos)
            } else {
                header + "\n    const/4 v0, 0x1\n    return v0\n" + rest
            }
        }
    }

    private fun smaliFileToClassName(file: File, root: File): String {
        val rel = root.toURI().relativize(file.toURI()).path
        return rel.removeSuffix(".smali").replace('/', '.')
    }

    enum class PatchMode {
        METHOD_RETURN_TRUE,  // 整个方法返回 true
        INSTRUCTION_LEVEL    // 指令级精确 patch
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
