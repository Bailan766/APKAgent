package com.apkagent.service

/**
 * 默认 OEM 适配器 — 不做任何特殊处理。
 * 适用于 Pixel、三星、Moto 等标准 AOSP 设备。
 */
class DefaultOemAdapter : OemAdapter {
    override val name = "Default"
    override fun isSupported(): Boolean = true // 总是兜底
}
