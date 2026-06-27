package com.apkagent.service

import android.content.Context
import android.os.PowerManager
import com.apkagent.util.Logger

/**
 * 后台保活管理器 — 管理前台服务 + WakeLock。
 *
 * 用法：
 *   KeepAliveManager.acquire(context)  // Agent 开始任务时调用
 *   KeepAliveManager.release()          // Agent 任务结束时调用
 *   KeepAliveManager.updateTask("分析 DEX...")  // 更新通知文字
 */
object KeepAliveManager {

    private const val TAG = "KeepAlive"
    private const val WAKELOCK_TAG = "APKAgent::AgentTask"

    private var wakeLock: PowerManager.WakeLock? = null
    private var service: KeepAliveService? = null
    private var refCount = 0  // 支持嵌套调用

    /** Agent 任务开始时调用（可嵌套，内部引用计数） */
    fun acquire(context: Context) {
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
            Logger.i(TAG, "WakeLock + 前台服务已获取")
        }
        Logger.i(TAG, "acquire: refCount=$refCount")
    }

    /** Agent 任务结束时调用（与 acquire 配对） */
    fun release() {
        refCount = (refCount - 1).coerceAtLeast(0)
        Logger.i(TAG, "release: refCount=$refCount")
        if (refCount == 0) {
            // 释放 WakeLock
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            // 停止前台服务
            service?.let {
                // service 在 onDestroy 中会调用 onServiceDestroyed
                try { it.stopSelf() } catch (_: Throwable) {}
            }
            Logger.i(TAG, "WakeLock + 前台服务已释放")
        }
    }

    /** 更新通知文字（如当前正在执行什么任务） */
    fun updateTask(taskName: String) {
        service?.updateNotification("正在执行：$taskName")
    }

    /** 是否正在保活 */
    fun isActive(): Boolean = refCount > 0

    /** 检查电池优化是否已豁免 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // ── Service 回调 ──

    internal fun onServiceCreated(svc: KeepAliveService) {
        service = svc
    }

    internal fun onServiceDestroyed(svc: KeepAliveService) {
        if (service === svc) {
            service = null
            // 如果 service 被系统杀死但 refCount > 0，清理状态
            if (refCount > 0) {
                Logger.w(TAG, "前台服务被系统杀死，清理状态")
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                refCount = 0
            }
        }
    }
}
