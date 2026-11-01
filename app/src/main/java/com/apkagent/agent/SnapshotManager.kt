package com.apkagent.agent

import android.util.Log
import java.io.File

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

        val id = "snap_${System.currentTimeMillis()}"
        val snapDir = File(baseDir, ".snapshots/$id").apply { mkdirs() }

        val backed = mutableListOf<Pair<String, String>>()
        for (file in files) {
            if (!file.exists() || !file.isFile) continue
            val relPath = file.relativeToOrSelf(baseDir).path
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
