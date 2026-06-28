package com.apkagent.service

import android.app.NotificationChannel
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * OPPO ColorOS 流体云适配器。
 * 通过反射调用 OPPO 私有 API，在状态栏显示实时任务胶囊。
 */
class ColorOsAdapter : OemAdapter {
    override val name = "ColorOS"

    override fun isSupported(): Boolean {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        return brand in listOf("oppo", "oneplus", "realme", "oplus")
                || manufacturer in listOf("oppo", "oneplus", "realme", "oplus")
                || getSystemProp("ro.build.version.opporom")?.isNotBlank() == true
    }

    override fun applyChannelExtras(channel: NotificationChannel) {
        try {
            val m1 = channel.javaClass.getMethod("setOplusImportant", Boolean::class.javaPrimitiveType)
            m1.invoke(channel, true)
            Log.i(name, "setOplusImportant(true)")
        } catch (_: Throwable) {}
        try {
            val m2 = channel.javaClass.getMethod("setAllowBubbles", Boolean::class.javaPrimitiveType)
            m2.invoke(channel, true)
            Log.i(name, "setAllowBubbles(true)")
        } catch (_: Throwable) {}
    }

    override fun applyNotificationExtras(builder: NotificationCompat.Builder, task: String) {
        // 方式1: extras 注入
        val extras = Bundle().apply {
            putBoolean("oplusLiveEvent", true)
            putBoolean("oplusNotificationCapsule", true)
            putInt("capsuleType", 1)
            putString("capsuleTitle", "APKAgent")
            putString("capsuleContent", task)
            putBoolean("android.ongoingActivity", true)
            putInt("android.ongoingActivityStyle", 1)
        }
        builder.addExtras(extras)

        // 方式2: 反射调用
        try {
            val innerBuilder = builder.javaClass.getDeclaredField("mBuilder")
                .apply { isAccessible = true }.get(builder) ?: return
            try {
                val m = innerBuilder.javaClass.getMethod("setOplusLiveEvent", Boolean::class.javaPrimitiveType)
                m.invoke(innerBuilder, true)
            } catch (_: Throwable) {}
            try {
                val m2 = innerBuilder.javaClass.getMethod("setOplusNotificationCapsule", Boolean::class.javaPrimitiveType)
                m2.invoke(innerBuilder, true)
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private fun getSystemProp(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            clazz.getMethod("get", String::class.java).invoke(null, key) as? String
        } catch (_: Throwable) { null }
    }
}
