package com.apkagent.export

import android.os.Environment
import com.apkagent.apktools.smali.ApkRepackSigner
import com.apkagent.apktools.smali.SmaliEngine
import java.io.File

class ApkExportManager {

    data class ExportResult(
        val success: Boolean,
        val message: String,
        val outputFile: File? = null
    )

    fun export(workspace: File, originalApk: File?): ExportResult {
        val original = originalApk ?: File(workspace, "source/base.apk")
        if (!original.exists()) return ExportResult(false, "未找到原始 APK")

        val patched = workspace.listFiles { f -> f.isDirectory && f.name.startsWith("patched_") }
            ?.sortedByDescending { it.lastModified() }
        val smali = workspace.listFiles { f -> f.isDirectory && f.name.startsWith("smali_out_") }
            ?.sortedByDescending { it.lastModified() }

        if (patched.isNullOrEmpty() && smali.isNullOrEmpty()) {
            return ExportResult(false, "未找到破解产物。请先让 AI 执行签名校验 patch。")
        }

        val unpackDir = File(workspace, "_export").apply { if (exists()) deleteRecursively() }
        ApkRepackSigner.unpackApk(original, unpackDir)

        patched?.forEach { d ->
            d.listFiles()?.filter { it.extension == "dex" }?.forEach { dex ->
                dex.copyTo(File(unpackDir, dex.name), overwrite = true)
            }
        }
        smali?.forEach { d ->
            SmaliEngine.assembleSmali(d, File(unpackDir, "classes.dex"))
        }

        val repacked = File(workspace, "_unsigned.apk")
        ApkRepackSigner.repack(unpackDir, repacked)

        val dl = File(Environment.getExternalStorageDirectory(), "APKAgent/build")
        val out = File(dl, "${original.nameWithoutExtension}_patched.apk").apply { parentFile?.mkdirs() }
        val sign = ApkRepackSigner.signApk(repacked, out, true, true)
        unpackDir.deleteRecursively()
        repacked.delete()

        return if (sign.success) {
            ExportResult(
                success = true,
                message = "导出成功\n${out.absolutePath}\n${out.length() / 1024}KB | ${sign.schemes?.joinToString("+") ?: "v1+v2"}",
                outputFile = out
            )
        } else {
            ExportResult(false, sign.message)
        }
    }
}
