package com.apkagent.export

import com.apkagent.i18n.L
import android.os.Environment
import com.apkagent.apktools.smali.ApkRepackSigner
import com.apkagent.apktools.smali.SmaliEngine
import java.io.File

/**
 * APK 导出管理器。
 *
 * 修复说明：
 * 1. 支持直接导出工作区内已由 apk_sign / apk_enhanced_sign 生成的最新签名 APK。
 * 2. 修复多 smali_out_* 目录按时间倒序遍历时旧目录覆盖新目录的 Bug（改为只取最新目录）。
 * 3. 修复 SmaliEngine.disassembleApk 生成的 classes/、classes2/ 多 DEX 子目录被混编进单个 classes.dex 的 Bug。
 * 4. 支持同步合并 unpacked_* 目录中修改过的 AndroidManifest.xml 与 res/xml/network_security_config.xml。
 * 5. 增加 Android 11+ Scoped Storage 权限受限时的安全回退目录（workspace/exports），杜绝 EACCES 崩溃。
 */
class ApkExportManager {

    data class ExportResult(
        val success: Boolean,
        val message: String,
        val outputFile: File? = null
    )

    fun export(workspace: File, originalApk: File?): ExportResult {
        val original = originalApk
            ?: File(workspace, "source/base.apk").takeIf { it.exists() }
            ?: File(workspace, "base.apk").takeIf { it.exists() }
            ?: return ExportResult(false, L("未找到原始 APK", "Original APK not found"))

        if (!original.exists()) return ExportResult(false, L("未找到原始 APK", "Original APK not found"))

        // 0. 检查工作区内是否已有 AI 直接打包并签名好的成品 APK
        val prebuiltSigned = workspace.walkTopDown()
            .maxDepth(2)
            .filter {
                it.isFile && it.extension.equals("apk", ignoreCase = true) &&
                    it.canonicalPath != original.canonicalPath &&
                    !it.name.startsWith("_") &&
                    (it.name.contains("signed", ignoreCase = true) ||
                     it.name.contains("patched", ignoreCase = true) ||
                     it.name.contains("aligned", ignoreCase = true))
            }
            .sortedByDescending { it.lastModified() }
            .firstOrNull()

        val patchedDirs = workspace.listFiles { f -> f.isDirectory && f.name.startsWith("patched_") }
            ?.sortedByDescending { it.lastModified() }
        val smaliDirs = workspace.listFiles { f -> f.isDirectory && f.name.startsWith("smali_out") }
            ?.sortedByDescending { it.lastModified() }
        val unpackedDirs = workspace.listFiles { f -> f.isDirectory && f.name.startsWith("unpacked") }
            ?.sortedByDescending { it.lastModified() }

        if (prebuiltSigned != null && patchedDirs.isNullOrEmpty() && smaliDirs.isNullOrEmpty()) {
            val out = resolveWritableExportFile(workspace, original.nameWithoutExtension)
            return runCatching {
                prebuiltSigned.copyTo(out, overwrite = true)
                ExportResult(
                    success = true,
                    message = "导出成功\n${out.absolutePath}\n${out.length() / 1024}KB",
                    outputFile = out
                )
            }.getOrElse { e ->
                ExportResult(false, "导出复制失败: ${e.message}")
            }
        }

        if (patchedDirs.isNullOrEmpty() && smaliDirs.isNullOrEmpty() && unpackedDirs.isNullOrEmpty()) {
            return ExportResult(false, L("未找到修改产物。请先让 AI 执行 Patch、反编译修改或重打包。", "No patched artifacts found. Run a patch or modification first."))
        }

        val unpackDir = File(workspace, "_export").apply { if (exists()) deleteRecursively() }
        ApkRepackSigner.unpackApk(original, unpackDir)

        // 1. 合并最新解包目录中修改过的 Manifest 或网络安全配置
        unpackedDirs?.firstOrNull()?.let { latestUnpacked ->
            val modManifest = File(latestUnpacked, "AndroidManifest.xml")
            if (modManifest.exists()) {
                modManifest.copyTo(File(unpackDir, "AndroidManifest.xml"), overwrite = true)
            }
            val modNetSec = File(latestUnpacked, "res/xml/network_security_config.xml")
            if (modNetSec.exists()) {
                val targetNetSec = File(unpackDir, "res/xml/network_security_config.xml")
                targetNetSec.parentFile?.mkdirs()
                modNetSec.copyTo(targetNetSec, overwrite = true)
            }
        }

        // 2. 合并最新的 patched DEX 目录
        patchedDirs?.firstOrNull()?.let { latestPatched ->
            latestPatched.listFiles()?.filter { it.extension == "dex" }?.forEach { dex ->
                dex.copyTo(File(unpackDir, dex.name), overwrite = true)
            }
        }

        // 3. 仅汇编最新的 smali 目录，并区分单 DEX 与多 DEX 子目录 (classes, classes2, ...)
        smaliDirs?.firstOrNull()?.let { latestSmali ->
            val multiDexSubDirs = latestSmali.listFiles()?.filter {
                it.isDirectory && it.name.matches(Regex("""classes\d*"""))
            }.orEmpty()

            if (multiDexSubDirs.isNotEmpty()) {
                multiDexSubDirs.forEach { sub ->
                    SmaliEngine.assembleSmali(sub, File(unpackDir, "${sub.name}.dex"))
                }
            } else {
                SmaliEngine.assembleSmali(latestSmali, File(unpackDir, "classes.dex"))
            }
        }

        val repacked = File(workspace, "_unsigned.apk")
        val repackRes = ApkRepackSigner.repack(unpackDir, repacked)
        if (!repackRes.success) {
            unpackDir.deleteRecursively()
            return ExportResult(false, repackRes.message)
        }

        val out = resolveWritableExportFile(workspace, original.nameWithoutExtension)
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

    private fun resolveWritableExportFile(workspace: File, baseName: String): File {
        val fileName = "${baseName}_patched.apk"
        val candidates = listOfNotNull(
            runCatching { File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "APKAgent") }.getOrNull(),
            runCatching { File(Environment.getExternalStorageDirectory(), "APKAgent/build") }.getOrNull(),
            File(workspace, "exports")
        )
        for (dir in candidates) {
            try {
                if (dir.exists() || dir.mkdirs()) {
                    val testFile = File(dir, ".write_test_${System.nanoTime()}")
                    if (runCatching { testFile.writeText("ok"); testFile.delete(); true }.getOrDefault(false)) {
                        return File(dir, fileName)
                    }
                }
            } catch (_: Throwable) {}
        }
        val fallbackDir = File(workspace, "exports").apply { mkdirs() }
        return File(fallbackDir, fileName)
    }
}
