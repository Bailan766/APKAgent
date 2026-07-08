package com.apkagent.apktools.smali

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.zip.ZipFile

/**
 * Smali 反编译/回编译引擎：基于 dexlib2 + baksmali + smali (3.0.9)。
 * 全部在 Android 上原生运行，无需外部进程。
 */
object SmaliEngine {

    /** 反编译单个 DEX 文件为 smali 文本，输出到 outDir */
    fun disassembleDex(dexFile: File, outDir: File): DisasmResult {
        if (!dexFile.exists()) return DisasmResult(false, "DEX 文件不存在", 0)
        outDir.mkdirs()
        return try {
            val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
            val opts = BaksmaliOptions()
            val ok = Baksmali.disassembleDexFile(dex, outDir, 4, opts)
            val count = outDir.walkTopDown().filter { it.extension == "smali" }.count()
            if (ok) DisasmResult(true, "成功反编译 $count 个类", count)
            else DisasmResult(false, "反编译失败", 0)
        } catch (e: Throwable) {
            DisasmResult(false, "反编译异常：${e.message}", 0)
        }
    }

    /**
     * 并行反编译 APK 内所有 DEX 到目录。
     * 先串行提取 DEX（ZIP 顺序读取），再并行反编译。
     */
    suspend fun disassembleApk(apkFile: File, outDir: File): DisasmResult = coroutineScope {
        try {
            // 第一步：串行提取所有 DEX 到临时文件
            val dexFiles = mutableListOf<Pair<String, File>>()
            ZipFile(apkFile).use { zip ->
                val dexEntries = zip.entries().toList().filter { it.name.endsWith(".dex") }
                    .sortedBy { it.name }
                for (entry in dexEntries) {
                    val tmp = File.createTempFile(entry.name.replace("/", "_"), ".dex")
                    zip.getInputStream(entry).use { it.copyTo(tmp.outputStream()) }
                    dexFiles.add(entry.name to tmp)
                }
            }

            // 第二步：并行反编译所有 DEX
            val results = dexFiles.map { (name, tmp) ->
                async(Dispatchers.IO) {
                    val subDir = File(outDir, name.removeSuffix(".dex"))
                    val r = disassembleDex(tmp, subDir)
                    try { tmp.delete() } catch (_: Throwable) {}
                    r
                }
            }.awaitAll()

            var total = 0
            results.forEach { if (it.success) total += it.classCount }
            DisasmResult(true, "反编译 ${dexFiles.size} 个 DEX，共 $total 个类", total)
        } catch (e: Throwable) {
            DisasmResult(false, "APK 反编译异常：${e.message}", 0)
        }
    }

    /** 回编译 smali 目录为单个 DEX 文件 */
    fun assembleSmali(smaliDir: File, outputDex: File): AsmResult {
        if (!smaliDir.exists()) return AsmResult(false, "smali 目录不存在", 0)
        val smaliFiles = smaliDir.walkTopDown().filter { it.extension == "smali" }.toList()
        if (smaliFiles.isEmpty()) return AsmResult(false, "未找到 .smali 文件", 0)

        return try {
            val opts = SmaliOptions()
            outputDex.parentFile?.mkdirs()
            // Smali.assemble 是公共入口：接受 SmaliOptions + 文件路径列表
            val fileList = smaliFiles.map { it.absolutePath }
            opts.outputDexFile = outputDex.absolutePath
            val ok = Smali.assemble(opts, fileList)
            if (ok) AsmResult(true, "成功回编译为 DEX", smaliFiles.size)
            else AsmResult(false, "回编译失败（Smali.assemble 返回 false）", 0)
        } catch (e: Throwable) {
            AsmResult(false, "回编译失败：${e.message}", 0)
        }
    }

    data class DisasmResult(val success: Boolean, val message: String, val classCount: Int)
    data class AsmResult(val success: Boolean, val message: String, val classCount: Int)
}
