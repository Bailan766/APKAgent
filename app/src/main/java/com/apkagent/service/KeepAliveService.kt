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
import androidx.core.app.NotificationCompat
import com.apkagent.MainActivity
import com.apkagent.R
import com.apkagent.service.OemAdapterFactory
import com.apkagent.util.Logger

/**
 * 前台服务 — 保持 APKAgent 在后台运行不被系统杀死。
 *
 * 兼容 ColorOS 16 流体云（OplusLiveEvent / Capsule），
 * 在状态栏实时显示当前 Agent 任务进度。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "apkagent_keepalive"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "KeepAlive"

        /** 当前任务状态（供流体云显示） */
        @Volatile var currentTask: String = "就绪"
        @Volatile var taskProgress: Int = -1  // -1 = 不确定, 0-100 = 进度
        @Volatile var roundCount: Int = 0

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
        Logger.i(TAG, "前台服务已启动（流体云兼容模式）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        KeepAliveManager.onServiceDestroyed(this)
        Logger.i(TAG, "前台服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 更新通知内容（实时同步到流体云胶囊）
     */
    fun updateNotification(taskName: String) {
        currentTask = taskName
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(taskName))
    }

    // ══════════════════════════════════════════
    //  Notification Channel — OPPO 流体云兼容
    // ══════════════════════════════════════════

    private val oemAdapter by lazy { OemAdapterFactory.get() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "APKAgent 任务状态",
                NotificationManager.IMPORTANCE_HIGH  // 流体云需要 HIGH
            ).apply {
                description = "实时显示 APKAgent 逆向分析任务进度"
                setShowBadge(true)
                enableVibration(false)
                setSound(null, null)

                // OEM 适配器：通过反射设置厂商私有属性
                oemAdapter.applyChannelExtras(this)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    // ══════════════════════════════════════════
    //  Build Notification — 流体云 Capsule
    // ══════════════════════════════════════════

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("APKAgent 分析中")
            .setContentText(buildRichText(text))
            .setSubText("轮次 $roundCount")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            // 进度条（不确定进度时用 0,0,true）
            .setProgress(100, if (taskProgress >= 0) taskProgress else 0, taskProgress < 0)
            // 大文本样式，展开时显示更多信息
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(buildRichText(text))
                    .setSummaryText("APKAgent · 轮次 $roundCount")
            )

        // OEM 适配器：通过反射注入厂商私有 Extras
        oemAdapter.applyNotificationExtras(builder, text)

        return builder.build()
    }

    private fun buildRichText(task: String): String {
        return when {
            task == "就绪" -> "等待指令..."
            task.startsWith("dex_") -> "⚙️ DEX 操作: $task"
            task.startsWith("apk_") -> "📦 APK 操作: $task"
            task.startsWith("shizuku") -> "🔧 系统操作: $task"
            task.startsWith("file_") -> "📄 文件操作: $task"
            task.startsWith("run_") -> "▶️ 脚本执行: $task"
            task.startsWith("python") -> "🐍 Python: $task"
            task.startsWith("smart_patch") -> "🩹 智能Patch: $task"
            task.startsWith("risk_scan") -> "🔍 安全扫描: $task"
            else -> "🔄 $task"
        }
    }

    // ColorOS 适配逻辑已迁移至 OemAdapter 策略接口（ColorOsAdapter.kt）
}
