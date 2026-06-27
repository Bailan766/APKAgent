package com.apkagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.apkagent.MainActivity
import com.apkagent.R
import com.apkagent.util.Logger

/**
 * 前台服务 — 保持 APKAgent 在后台运行不被系统杀死。
 *
 * 配合 WakeLock 防止 CPU 休眠，确保 Agent 长任务（分析、脚本执行、重打包）不中断。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "apkagent_keepalive"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "KeepAlive"

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("就绪"))
        KeepAliveManager.onServiceCreated(this)
        Logger.i(TAG, "前台服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: 被系统杀死后自动重启
        return START_STICKY
    }

    override fun onDestroy() {
        KeepAliveManager.onServiceDestroyed(this)
        Logger.i(TAG, "前台服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "APKAgent 后台运行",
                NotificationManager.IMPORTANCE_LOW  // 低优先级，不弹出
            ).apply {
                description = "保持 APKAgent 在后台运行，确保分析任务不被中断"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("APKAgent 运行中")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
