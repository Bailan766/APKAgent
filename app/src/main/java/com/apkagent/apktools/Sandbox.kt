package com.apkagent.apktools

import com.apkagent.shizuku.ShizukuManager
import com.apkagent.util.Logger
import java.io.File

/**
 * 沙箱：限制 AI 工具的文件操作范围，防止越权访问。
 *
 * 路径规则：
 * - 绝对路径 → 直接校验
 * - 相对路径 → 以 workspace 为基准解析（AI 经常传相对路径）
 *
 * 可读：工作区目录 + 当前打开的 APK（Shizuku 授权后任意路径）
 * 可写：仅工作区目录
 */
object Sandbox {

    fun isInside(file: File, root: File): Boolean {
        val rootPath = root.canonicalPath.removeSuffix(File.separator)
        val filePath = file.canonicalPath
        return filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
    }

    /** 是否拥有特权（Shizuku 已授权） */
    fun isPrivileged(): Boolean = ShizukuManager.isAuthorized()

    /** 将相对路径基于 workspace 解析为绝对路径 */
    private fun normalize(file: File, workspace: File): File {
        if (file.isAbsolute) return file
        if (file.path.startsWith(workspace.absolutePath)) return file
        return File(workspace, file.path)
    }

    /** 校验可写路径必须在 workspace 内 */
    fun assertWritable(file: File, workspace: File) {
        val resolved = normalize(file, workspace)
        val f = resolved.canonicalFile
        if (!isInside(f, workspace)) {
            Logger.w("Sandbox", "写拒绝: ${file.path} → ${f.absolutePath} (workspace: ${workspace.absolutePath})")
            throw SecurityException("路径越界，拒绝访问工作区外文件：${file.path}")
        }
    }

    /** 校验可读路径在 workspace 内 或 是当前打开的 APK（Shizuku 授权后允许任意路径） */
    fun assertReadable(file: File, workspace: File, openApk: File?) {
        val resolved = normalize(file, workspace)
        val f = resolved.canonicalFile
        if (isInside(f, workspace)) return
        if (openApk != null && f == openApk.canonicalFile) return
        if (ShizukuManager.isAuthorized()) return
        Logger.w("Sandbox", "读拒绝: ${file.path} → ${f.absolutePath}")
        throw SecurityException("路径越界，拒绝访问：${file.path}")
    }

    /** 解析路径（工具可用此方法获得实际工作路径） */
    fun resolve(file: File, workspace: File): File = normalize(file, workspace)
}
