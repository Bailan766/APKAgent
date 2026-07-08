package com.apkagent.apktools

import java.io.File

/**
 * 路径解析与访问策略占位层。
 *
 * 当前实现不做沙箱拦截，只负责：
 * - 相对路径基于 workspace 解析
 * - 保留未来接入真正 FileAccessPolicy 的 seam
 */
object Sandbox {

    /** 将相对路径基于 workspace 解析为绝对路径 */
    fun resolve(file: File, workspace: File): File {
        if (file.isAbsolute) return file
        return File(workspace, file.path)
    }

    /** 无限制可写 — 直接通过 */
    fun assertWritable(file: File, workspace: File) {}

    /** 无限制可读 — 直接通过 */
    fun assertReadable(file: File, workspace: File, openApk: File?) {}

    /** 是否拥有特权（始终为 true） */
    fun isPrivileged(): Boolean = true

    /** 检查路径是否在指定目录内 */
    fun isInside(file: File, root: File): Boolean {
        return try {
            val rootPath = root.canonicalPath.removeSuffix(File.separator)
            val filePath = file.canonicalPath
            filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
        } catch (_: Exception) { false }
    }
}
