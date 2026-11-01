package com.apkagent.service

import com.apkagent.i18n.L

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
 * 深度兼容 ColorOS 14+ / 15 / 16 流体云（Pantanal 泛在服务 / OplusLiveEvent / Notification Capsule），
 * 无论在状态栏胶囊、灵动岛、通知中心还是锁屏/AOD 上，均可实时动态展示当前目标 APK 与执行状态。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "apkagent_keepalive"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "KeepAlive"

        /** 当前目标 APK 名称（流体云胶囊标题） */
        @Volatile var currentApkName: String = ""

        /** 当前胶囊状态提示，如 "⚡ 快速模式" 或 "🛡️ 安全模式" */
        @Volatile var capsuleStatus: String = "⚡ 快速模式"

        /** 当前任务状态（供流体云显示） */
        @Volatile var currentTask: String = L("就绪", "Ready")
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

        /**
         * 将底层工具名映射为友好的人性化中文动作，用于 ColorOS 状态栏胶囊与通知大卡片
         */
        fun humanReadableTool(toolName: String): String {
            return when {
                toolName == L("就绪", "Ready") || toolName == "Ready" -> L("等待指令...", "Waiting for instructions...")
                toolName == "smart_patch_apply" -> "🩹 应用智能 Patch 补丁"
                toolName == "smart_patch_plan" -> "📋 生成智能补丁方案"
                toolName == "dex_search" -> "🔍 检索 DEX 类与方法"
                toolName == "dex_dump" -> "📑 反编译与解析 DEX 结构"
                toolName == "dex_edit" -> "⚙️ 修改 Smali / DEX 字节码"
                toolName == "dex_diff" -> "📊 对比 DEX 差异"
                toolName == "apk_unpack" -> "📦 解包 APK 资源与清单"
                toolName == "apk_repack" -> "🔨 重新打包 APK"
                toolName == "apk_sign" -> "✍️ 对 APK 进行 V1/V2 签名"
                toolName == "bypass_signature" -> "🛡️ 注入签名校验绕过补丁"
                toolName == "risk_scan" -> "🛡️ 扫描 APK 漏洞与敏感权限"
                toolName == "run_python_script" || toolName == "python_exec" -> "🐍 执行 Python 自动化脚本"
                toolName == "frida_generate" -> "💉 生成 Frida 动态 Hook 脚本"
                toolName == "frida_push" -> "🚀 推送 Frida 脚本"
                toolName == "file_read" -> "📖 读取工程文件"
                toolName == "file_write" -> "📝 写入工程文件"
                toolName == "file_tree" -> "📁 遍历工程目录"
                toolName == "shizuku_exec" -> "⚡ Shizuku 高权限执行"
                toolName.startsWith("dex_") -> "⚙️ DEX 操作: $toolName"
                toolName.startsWith("apk_") -> "📦 APK 操作: $toolName"
                toolName.startsWith("shizuku") -> "🔧 系统操作: $toolName"
                toolName.startsWith("file_") -> "📄 文件操作: $toolName"
                toolName.startsWith("run_") -> "▶️ 脚本执行: $toolName"
                toolName.startsWith("python") -> "🐍 Python: $toolName"
                toolName.startsWith("smart_patch") -> "🩹 智能Patch: $toolName"
                toolName.startsWith("risk_scan") -> "🔍 安全扫描: $toolName"
                else -> "🔄 $toolName"
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(L("就绪", "Ready")))
        KeepAliveManager.onServiceCreated(this)
        Logger.i(TAG, L("前台服务已启动（ColorOS 流体云支持）", "Front desk service started."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        KeepAliveManager.onServiceDestroyed(this)
        Logger.i(TAG, L("前台服务已停止", "Front desk service stopped."))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 更新通知内容（实时同步到 ColorOS 流体云药丸胶囊）
     */
    fun updateNotification(
        taskName: String,
        apkName: String? = null,
        status: String? = null,
        progress: Int? = null
    ) {
        currentTask = taskName
        if (!apkName.isNullOrBlank()) currentApkName = apkName
        if (!status.isNullOrBlank()) capsuleStatus = status
        if (progress != null) taskProgress = progress
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(taskName))
    }

    // ══════════════════════════════════════════
    //  Notification Channel — ColorOS 流体云通道
    // ══════════════════════════════════════════

    private val oemAdapter by lazy { OemAdapterFactory.get() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                L("APKAgent 流体云任务状态", "APKAgent Live Task Status"),
                NotificationManager.IMPORTANCE_HIGH  // 流体云需 HIGH
            ).apply {
                description = L("实时显示 APKAgent 逆向分析任务进度及 ColorOS 流体云状态", "Show APKAgent reverse task progress in real time")
                setShowBadge(true)
                enableVibration(false)
                setSound(null, null)

                // OEM 适配器：针对 ColorOS / HyperOS 等配置特权通道
                oemAdapter.applyChannelExtras(this)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    // ══════════════════════════════════════════
    //  Build Notification — ColorOS 流体云 Capsule
    // ══════════════════════════════════════════

    private fun buildNotification(text: String): Notification {
        val targetTitle = currentApkName.ifBlank { "APKAgent" }
        val richTask = humanReadableTool(text)
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("APKAgent · $targetTitle")
            .setContentText(richTask)
            .setSubText(capsuleStatus)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            // 进度条（不确定进度时用 0,0,true）
            .setProgress(100, if (taskProgress >= 0) taskProgress else 0, taskProgress < 0)
            // 大文本样式，展开时显示完整信息
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("目标: $targetTitle\n动作: $richTask\n模式: $capsuleStatus · 轮次 $roundCount")
                    .setSummaryText("$targetTitle · $capsuleStatus")
            )

        // OEM 适配器：注入 ColorOS 泛在服务 / OplusNotificationCapsule 私有 Extras
        oemAdapter.applyNotificationExtras(builder, richTask)

        val notification = builder.build()
        try {
            notification.extras.apply {
                putBoolean("oplusLiveEvent", true)
                putBoolean("oplusNotificationCapsule", true)
                putBoolean("oplus_show_in_capsule", true)
                putString("capsuleTitle", targetTitle)
                putString("capsuleContent", richTask)
                putString("capsuleStatus", capsuleStatus)
            }
        } catch (_: Throwable) {}

        return notification
    }
}
