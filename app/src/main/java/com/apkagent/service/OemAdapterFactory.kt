package com.apkagent.service

import android.util.Log

/**
 * OEM 适配器工厂。
 * 按优先级检测设备类型，返回最匹配的适配器。
 */
object OemAdapterFactory {
    private const val TAG = "OemFactory"

    private val adapters = listOf(
        ColorOsAdapter(),
        // 未来可添加:
        // MiuiAdapter(),
        // EmuiAdapter(),
        // OneUiAdapter(),
        DefaultOemAdapter()
    )

    private var cached: OemAdapter? = null

    fun get(): OemAdapter {
        cached?.let { return it }
        val adapter = adapters.firstOrNull { it.isSupported() } ?: DefaultOemAdapter()
        Log.i(TAG, "检测到 OEM: ${adapter.name}")
        cached = adapter
        return adapter
    }

    /** 测试用：清除缓存 */
    fun resetForTest() { cached = null }
}
