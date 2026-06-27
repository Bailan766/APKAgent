package com.apkagent.apktools

import com.apkagent.shizuku.ShizukuManager
import java.io.File

/**
 * 沙箱：限制 AI 工具的文件操作范围，防止越权访问。
 * 可读：工作区目录 + 当前打开的 APK。
 * 可写：仅工作区目录。
 *
 * 当 Shizuku 已授权时，放宽可读范围至任意路径（仍限制写入工作区）。
 */
object Sandbox {

    fun isInside(file: File, root: File): Boolean {
        val rootPath = root.canonicalPath.removeSuffix(File.separator)
        val filePath = file.canonicalPath
        return filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
    }

    /** 是否拥有特权（Shizuku 已授权） */
    fun isPrivileged(): Boolean = ShizukuManager.isAuthorized()

    /** 校验可写路径必须在 workspace 内 */
    fun assertWritable(file: File, workspace: File) {
        val f = file.canonicalFile
        if (!isInside(f, workspace)) {
            throw SecurityException("路径越界，拒绝访问工作区外文件：${file.path}")
        }
    }

    /** 校验可读路径在 workspace 内 或 是当前打开的 APK（Shizuku 授权后允许任意路径） */
    fun assertReadable(file: File, workspace: File, openApk: File?) {
        val f = file.canonicalFile
        if (isInside(f, workspace)) return
        if (openApk != null && f == openApk.canonicalFile) return
        if (ShizukuManager.isAuthorized()) return  // Shizuku 模式：允许读任意路径
        throw SecurityException("路径越界，拒绝访问：${file.path}")
    }
}
