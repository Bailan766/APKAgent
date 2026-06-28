package com.apkagent.service

import android.app.NotificationChannel
import android.app.Notification
import androidx.core.app.NotificationCompat

/**
 * OEM 通知适配器接口。
 * 将 OPPO/vivo/小米/华为等厂商的私有 API 抽象为策略模式，
 * 新增厂商只需实现此接口。
 */
interface OemAdapter {
    /** 设备名称（用于日志） */
    val name: String

    /** 是否适用于当前设备 */
    fun isSupported(): Boolean

    /** 在 NotificationChannel 上设置厂商私有属性 */
    fun applyChannelExtras(channel: NotificationChannel) {}

    /** 在 Notification.Builder/Compat.Builder 上设置厂商私有属性 */
    fun applyNotificationExtras(builder: NotificationCompat.Builder, task: String) {}
}
