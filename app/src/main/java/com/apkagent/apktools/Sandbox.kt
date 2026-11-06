package com.apkagent.apktools

import java.io.File

/**
 * 路径解析与访问策略占位层。
 *
 * 本 App 是工具型应用，需要对用户指定目录（含工作区外）的 APK/文件干活，
 * 因此默认不做「必须落在 workspace 内」的强拦截，只负责：
 * - 相对路径基于 workspace 解析
 * - 始终做 canonical 规范化并消解 `..`，消除路径穿越歧义
 * - 保留未来接入真正 FileAccessPolicy 的 seam（strict 开关）
 */
object Sandbox {

    /**
     * 严格沙箱开关（默认关闭，保功能优先）。
     * 关闭时仅拒绝明显异常路径（规范化后为空 / 文件系统根目录）；
     * 开启后 assertWritable/assertReadable 会用 isInside 强制校验路径落在 workspace 内
     * （assertReadable 仍允许当前打开的 APK，以保留跨目录分析能力）。
     */
    @Volatile
    var strict: Boolean = false

    /** 将相对路径基于 workspace 解析，并始终做 canonical 规范化、消解 `.` / `..`。 */
    fun resolve(file: File, workspace: File): File {
        val base = if (file.isAbsolute) file else File(workspace, file.path)
        return try {
            base.canonicalFile
        } catch (_: Exception) {
            // canonicalFile 依赖文件系统，异常时退化为纯字符串分段消解，避免原样返回含 .. 的路径
            normalizeLexically(base.absolutePath)
        }
    }

    /** canonical 不可用时按路径段消解 . / .. 的兜底（不访问文件系统）。 */
    private fun normalizeLexically(path: String): File {
        val root = when {
            path.startsWith("/") -> "/"
            path.length >= 2 && path[1] == ':' -> path.substring(0, 2) + File.separator
            else -> ""
        }
        val segments = mutableListOf<String>()
        for (seg in path.substring(root.length).split('/', '\\')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (segments.isNotEmpty() && segments.last() != "..") {
                    segments.removeAt(segments.lastIndex)
                } else if (root.isEmpty()) {
                    segments.add("..")
                }
                else -> segments.add(seg)
            }
        }
        return File(root + segments.joinToString(File.separator))
    }

    /** 无限制可写 — 默认直接通过；严格模式下校验在 workspace 内。 */
    fun assertWritable(file: File, workspace: File) {
        assertNotRootOrEmpty(file, "写入")
        if (strict && !isInside(file, workspace)) {
            throw SecurityException("严格沙箱已开启，拒绝写入工作区外路径: ${file.path}")
        }
    }

    /** 无限制可读 — 默认直接通过；严格模式下校验在 workspace 内或为当前打开的 APK。 */
    fun assertReadable(file: File, workspace: File, openApk: File?) {
        assertNotRootOrEmpty(file, "读取")
        if (!strict) return
        if (isInside(file, workspace)) return
        val apk = openApk?.let { resolve(it, workspace) }
        if (apk != null && samePath(file, apk)) return
        throw SecurityException("严格沙箱已开启，拒绝读取工作区外路径: ${file.path}")
    }

    /** 即使未开启 strict，也拒绝规范化后为空或指向文件系统根目录等明显异常路径。 */
    private fun assertNotRootOrEmpty(file: File, op: String) {
        val path = try {
            file.canonicalPath
        } catch (_: Exception) {
            throw SecurityException("[$op] 无法规范化路径: ${file.path}")
        }
        val rootPath = try {
            File(File.separator).canonicalPath
        } catch (_: Exception) {
            File.separator
        }
        if (path.isBlank() || path == rootPath) {
            throw SecurityException("[$op] 拒绝明显异常路径: $path")
        }
    }

    private fun samePath(a: File, b: File): Boolean = try {
        a.canonicalPath == b.canonicalPath
    } catch (_: Exception) {
        false
    }

    /**
     * 是否拥有特权（始终为 true）。
     * 这里的「特权」仅表示本工具型 App 允许执行高权限/跨目录操作（不作路径拦截），
     * 并不代表绕过 Android 系统权限或 Shizuku 授权；具体能力仍由系统与 Shizuku 决定。
     */
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
