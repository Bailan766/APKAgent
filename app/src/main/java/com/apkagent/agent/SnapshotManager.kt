package com.apkagent.agent

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 操作前快照管理器。
 * 在执行危险工具前，自动备份相关文件，出错时可一键回滚到快照状态。
 */
object SnapshotManager {
    private const val TAG = "Snapshot"
    private const val MAX_SNAPSHOTS = 10

    data class Snapshot(
        val id: String,
        val timestamp: Long,
        val toolName: String,
        val description: String,
        val backedUpFiles: List<Pair<String, String>> // original -> backup path
    )

    private val snapshots = mutableListOf<Snapshot>()

    /** 单调递增序号，配合时间戳确保快照 ID 唯一，避免毫秒碰撞 */
    private val idCounter = AtomicLong(0)

    /**
     * 创建快照。在执行 DANGER 级工具前调用。
     */
    @Synchronized
    fun create(
        toolName: String,
        files: List<File>,
        baseDir: File
    ): Snapshot? {
        if (files.isEmpty()) return null

        // 唯一 ID：时间戳 + AtomicLong 序号
        val id = "snap_${System.currentTimeMillis()}_${idCounter.incrementAndGet()}"
        val snapDir = File(baseDir, ".snapshots/$id").apply { mkdirs() }

        // 规范化 baseDir 路径用于越界校验
        val canonicalBase = baseDir.canonicalFile

        val backed = mutableListOf<Pair<String, String>>()
        for (file in files) {
            if (!file.exists() || !file.isFile) continue
            val canonicalFile = file.canonicalFile
            // 越界校验：文件必须在 baseDir 内（canonical 比较），防止备份工作区外文件
            if (!canonicalFile.path.startsWith(canonicalBase.path + File.separator) &&
                canonicalFile != canonicalBase) {
                try { Log.w(TAG, "跳过越界文件: ${file.path}") } catch (_: Throwable) {}
                continue
            }
            val relPath = canonicalFile.relativeToOrSelf(canonicalBase).path
            val backup = File(snapDir, relPath).apply { parentFile?.mkdirs() }
            try {
                file.copyTo(backup, overwrite = true)
                backed.add(file.absolutePath to backup.absolutePath)
            } catch (e: Exception) {
                try { Log.w(TAG, "备份失败: ${file.name}: ${e.message}") } catch (_: Throwable) {}
            }
        }

        if (backed.isEmpty()) {
            snapDir.deleteRecursively()
            return null
        }

        val snap = Snapshot(id, System.currentTimeMillis(), toolName, "备份 ${backed.size} 个文件", backed)
        snapshots.add(snap)

        while (snapshots.size > MAX_SNAPSHOTS) {
            val old = snapshots.removeFirst()
            try { File(baseDir, ".snapshots/${old.id}").deleteRecursively() } catch (_: Exception) {}
        }

        try { Log.i(TAG, "快照创建: $id (${backed.size} 文件)") } catch (_: Throwable) {}
        return snap
    }

    /**
     * 回滚到指定快照。
     */
    @Synchronized
    fun rollback(snapshot: Snapshot): Boolean {
        var success = true
        for ((original, backup) in snapshot.backedUpFiles) {
            val origFile = File(original)
            val backupFile = File(backup)
            if (!backupFile.exists()) {
                try { Log.w(TAG, "快照文件不存在: $backup") } catch (_: Throwable) {}
                success = false
                continue
            }
            try {
                origFile.parentFile?.mkdirs()
                backupFile.copyTo(origFile, overwrite = true)
            } catch (e: Exception) {
                try { Log.e(TAG, "回滚失败: $original: ${e.message}") } catch (_: Throwable) {}
                success = false
            }
        }
        try { Log.i(TAG, "回滚 ${if (success) "成功" else "部分失败"}: ${snapshot.id}") } catch (_: Throwable) {}
        return success
    }

    @Synchronized
    fun listSnapshots(): List<Snapshot> = snapshots.toList()

    @Synchronized
    fun latestSnapshot(): Snapshot? = snapshots.lastOrNull()
}
