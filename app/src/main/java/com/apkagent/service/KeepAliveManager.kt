package com.apkagent.service

import com.apkagent.i18n.L

import android.content.Context
import android.os.PowerManager
import com.apkagent.util.Logger

/**
 * 后台保活管理器 — 管理前台服务 + WakeLock + ColorOS 流体云药丸胶囊状态同步。
 *
 * 用法：
 *   KeepAliveManager.acquire(context)  // Agent 开始任务时调用
 *   KeepAliveManager.release()          // Agent 任务结束时调用
 *   KeepAliveManager.updateTask(...)    // 更新流体云与通知文字
 */
object KeepAliveManager {

    private const val TAG = "KeepAlive"
    private const val WAKELOCK_TAG = "APKAgent::AgentTask"

    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var service: KeepAliveService? = null
    // 保存 applicationContext：startForegroundService 是异步的，service 可能为 null，
    // 释放时必须能无条件 stopService 兜底
    @Volatile private var appContext: Context? = null
    // 由 @Synchronized 保护，避免 refCount++ 非原子导致重复启动 / 覆盖 wakeLock
    private var refCount = 0

    /** Agent 任务开始时调用（可嵌套，内部引用计数） */
    @Synchronized
    fun acquire(context: Context) {
        appContext = context.applicationContext
        refCount++
        if (refCount == 1) {
            // 启动前台服务
            KeepAliveService.start(context)
            // 获取 WakeLock（防止 CPU 休眠）
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                acquire(30 * 60 * 1000L)  // 最长 30 分钟自动释放（安全兜底）
            }
            Logger.i(TAG, L("WakeLock + 前台服务已获取", "WakeLock + Front desk service acquired"))
        }
        Logger.i(TAG, "acquire: refCount=$refCount")
    }

    /** Agent 任务结束时调用（与 acquire 配对） */
    @Synchronized
    fun release() {
        // 多余的 release 直接忽略：只在 1→0 时清理，避免归零后重复 release 误停刚启动的新任务
        if (refCount <= 0) {
            Logger.w(TAG, "release 多于 acquire，忽略: refCount=$refCount")
            return
        }
        refCount--
        Logger.i(TAG, "release: refCount=$refCount")
        if (refCount == 0) {
            // 释放 WakeLock
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            // 停止前台服务
            service?.let {
                try { it.stopSelf() } catch (_: Throwable) {}
            }
            service = null
            // 无条件 stopService 兜底：service 可能仍为 null（startForegroundService 异步）
            stopServiceFallback()
            Logger.i(TAG, L("WakeLock + 前台服务已释放", "WakeLock + Front desk service released"))
        }
    }

    private fun stopServiceFallback() {
        try {
            appContext?.let { KeepAliveService.stop(it) }
        } catch (_: Throwable) {}
    }

    /**
     * 更新通知文字与 ColorOS 流体云胶囊
     */
    fun updateTask(
        taskName: String,
        apkName: String? = null,
        status: String? = null,
        progress: Int? = null
    ) {
        service?.updateNotification(taskName, apkName, status, progress)
    }

    /** 简化更新重载 */
    fun updateTask(taskName: String) {
        service?.updateNotification(taskName)
    }

    /** 是否正在保活 */
    @Synchronized
    fun isActive(): Boolean = refCount > 0

    /** 检查电池优化是否已豁免 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // ── Service 回调 ──

    @Synchronized
    internal fun onServiceCreated(svc: KeepAliveService) {
        service = svc
        // 系统重建 START_STICKY 服务时 refCount 已清零，立即停止，避免幽灵常驻通知
        if (refCount == 0) {
            Logger.w(TAG, L("无活跃任务，停止重建的前台服务", "No active task, stop the recreated service"))
            try { svc.stopSelf() } catch (_: Throwable) {}
        }
    }

    @Synchronized
    internal fun onServiceDestroyed(svc: KeepAliveService) {
        if (service === svc) {
            service = null
            // 如果 service 被系统杀死但 refCount > 0，清理状态
            if (refCount > 0) {
                Logger.w(TAG, L("前台服务被系统杀死，清理状态", "The front desk service was killed. Clean up."))
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                refCount = 0
            }
        }
    }
}
