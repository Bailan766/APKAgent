package com.apkagent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * OPPO / 一加 / 真我 ColorOS 14+ / 15 / 16 流体云（Fluid Cloud）原生适配器。
 *
 * 深度接入 ColorOS 泛在服务（Pantanal）与状态栏实时胶囊（OplusNotificationCapsule / OplusLiveEvent）：
 * 1. 锁屏、状态栏左侧实时显示流体云药丸胶囊（当前逆向目标 APK + 轮次 + 当前执行工具）。
 * 2. 状态栏展开支持大卡片图文进度（进行中活动 OngoingActivity）。
 * 3. 锁屏与 AOD 常驻通知胶囊支持，点击直接无感回弹当前逆向任务界面。
 */
class ColorOsAdapter : OemAdapter {
    override val name = "ColorOS"

    override fun isSupported(): Boolean {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val isOemBrand = brand in listOf("oppo", "oneplus", "realme", "oplus")
                || manufacturer in listOf("oppo", "oneplus", "realme", "oplus")
        if (isOemBrand) return true

        val oplusProps = listOf(
            "ro.build.version.opporom",
            "ro.build.version.oplusrom",
            "ro.oplus.version.my_manifest",
            "ro.build.oplus_version",
            "ro.rom.version"
        )
        return oplusProps.any { getSystemProp(it)?.isNotBlank() == true }
    }

    override fun applyChannelExtras(channel: NotificationChannel) {
        try {
            // ColorOS 14+ 泛在服务特权通知通道
            channel.importance = NotificationManager.IMPORTANCE_HIGH
            channel.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            
            val mImportant = channel.javaClass.getMethod("setOplusImportant", Boolean::class.javaPrimitiveType)
            mImportant.invoke(channel, true)
            Log.i(name, "ColorOS: setOplusImportant(true) 成功")
        } catch (_: Throwable) {}

        try {
            val mBubbles = channel.javaClass.getMethod("setAllowBubbles", Boolean::class.javaPrimitiveType)
            mBubbles.invoke(channel, true)
            Log.i(name, "ColorOS: setAllowBubbles(true) 成功")
        } catch (_: Throwable) {}

        try {
            val mBlockable = channel.javaClass.getMethod("setBlockable", Boolean::class.javaPrimitiveType)
            mBlockable.invoke(channel, false)
        } catch (_: Throwable) {}
    }

    override fun applyNotificationExtras(builder: NotificationCompat.Builder, task: String) {
        val title = KeepAliveService.currentApkName.ifBlank { "APKAgent" }
        val status = KeepAliveService.capsuleStatus
        val progress = KeepAliveService.taskProgress

        // 方式 1: ColorOS 14/15/16 流体云 Bundle 官方泛在服务标准字段
        val extras = Bundle().apply {
            // 流体云核心开关
            putBoolean("oplusLiveEvent", true)
            putBoolean("oplusNotificationCapsule", true)
            putBoolean("oplus_show_in_capsule", true)
            putBoolean("oplus_capsule_status", true)
            putBoolean("oplus_support_fluid_cloud", true)

            // 胶囊文本与类型 (1: 状态胶囊, 2: 进度胶囊)
            putInt("capsuleType", if (progress >= 0) 2 else 1)
            putString("capsuleTitle", title)
            putString("capsuleContent", task)
            putString("capsuleStatus", status)
            if (progress >= 0) {
                putInt("capsuleProgress", progress.coerceIn(0, 100))
            }

            // Android 标准 OngoingActivity 属性（ColorOS 15/16 灵动岛兼容）
            putBoolean("android.ongoingActivity", true)
            putInt("android.ongoingActivityStyle", 1)
            putString("android.substName", "APKAgent")
            putString("oplus_live_event_type", "capsule")
            putString("oplus_live_event_state", "ongoing")
        }
        builder.addExtras(extras)

        // 方式 2: 反射调用系统底层 Builder 方法
        try {
            val innerBuilder = builder.javaClass.getDeclaredField("mBuilder")
                .apply { isAccessible = true }.get(builder) ?: return
            
            // setOplusLiveEvent
            runCatching {
                val m = innerBuilder.javaClass.getMethod("setOplusLiveEvent", Boolean::class.javaPrimitiveType)
                m.invoke(innerBuilder, true)
            }
            // setOplusNotificationCapsule
            runCatching {
                val m2 = innerBuilder.javaClass.getMethod("setOplusNotificationCapsule", Boolean::class.javaPrimitiveType)
                m2.invoke(innerBuilder, true)
            }
        } catch (_: Throwable) {}
    }

    private fun getSystemProp(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            clazz.getMethod("get", String::class.java).invoke(null, key) as? String
        } catch (_: Throwable) { null }
    }
}
