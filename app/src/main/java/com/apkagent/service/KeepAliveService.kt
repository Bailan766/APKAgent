package com.apkagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.apkagent.MainActivity
import com.apkagent.R
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

                // ColorOS 流体云兼容：通过反射设置 OPPO 私有属性
                applyOplusChannelExtras(this)
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

        // ColorOS 流体云兼容：通过反射注入 OPPO 私有 Extras
        applyOplusNotificationExtras(builder, text)

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

    // ══════════════════════════════════════════
    //  ColorOS / Oplus 流体云 API（反射调用）
    // ══════════════════════════════════════════

    /**
     * 检测是否为 ColorOS（OPPO/OnePlus/realme 设备）
     */
    private fun isOplusDevice(): Boolean {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        return brand in listOf("oppo", "oneplus", "realme", "oplus")
                || manufacturer in listOf("oppo", "oneplus", "realme", "oplus")
                || getSystemProp("ro.build.version.opporom")?.isNotBlank() == true
    }

    /**
     * 读取系统属性
     */
    private fun getSystemProp(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            method.invoke(null, key) as? String
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 通过反射在 NotificationChannel 上设置 OPPO 私有属性
     * - oplusImportant: 启用流体云通道
     * - allowBubble: 允许气泡显示
     */
    private fun applyOplusChannelExtras(channel: NotificationChannel) {
        if (!isOplusDevice()) return
        try {
            // setOplusImportant(true) — 标记为 OPPO 重要通知
            val m1 = channel.javaClass.getMethod("setOplusImportant", Boolean::class.javaPrimitiveType)
            m1.invoke(channel, true)
            Logger.i(TAG, "ColorOS: setOplusImportant(true)")
        } catch (_: Throwable) {
            // 不是 ColorOS 或 API 不存在，静默跳过
        }
        try {
            // setAllowBubbles(true) — 允许浮动气泡/流体云
            val m2 = channel.javaClass.getMethod("setAllowBubbles", Boolean::class.javaPrimitiveType)
            m2.invoke(channel, true)
            Logger.i(TAG, "ColorOS: setAllowBubbles(true)")
        } catch (_: Throwable) {}
    }

    /**
     * 通过反射在 Notification.Builder 上设置 OPPO 私有属性
     * - setOplusLiveEvent(true): 标记为实时事件，触发流体云胶囊
     * - setOplusNotificationCapsule(true): 启用胶囊样式
     * - extras 中注入 capsuleType / liveEvent 标记
     */
    private fun applyOplusNotificationExtras(builder: NotificationCompat.Builder, task: String) {
        if (!isOplusDevice()) return

        // 方式1：通过 extras 注入 OPPO 流体云标记
        val extras = Bundle().apply {
            // 流体云标准标记
            putBoolean("oplusLiveEvent", true)
            putBoolean("oplusNotificationCapsule", true)
            putInt("capsuleType", 1)  // 1 = 进度型胶囊
            putString("capsuleTitle", "APKAgent")
            putString("capsuleContent", task)
            // ColorOS 16 新增字段
            putBoolean("android.ongoingActivity", true)
            putInt("android.ongoingActivityStyle", 1)
        }
        builder.addExtras(extras)

        // 方式2：通过反射调用 Notification.Builder 的 Oplus 方法
        try {
            val innerBuilder = builder.javaClass.getDeclaredField("mBuilder").apply { isAccessible = true }.get(builder)
            if (innerBuilder != null) {
                try {
                    val m = innerBuilder.javaClass.getMethod("setOplusLiveEvent", Boolean::class.javaPrimitiveType)
                    m.invoke(innerBuilder, true)
                    Logger.i(TAG, "ColorOS: setOplusLiveEvent(true)")
                } catch (_: Throwable) {}
                try {
                    val m2 = innerBuilder.javaClass.getMethod("setOplusNotificationCapsule", Boolean::class.javaPrimitiveType)
                    m2.invoke(innerBuilder, true)
                    Logger.i(TAG, "ColorOS: setOplusNotificationCapsule(true)")
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}
