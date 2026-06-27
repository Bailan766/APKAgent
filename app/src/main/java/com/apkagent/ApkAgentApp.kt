package com.apkagent

import android.app.Application
import com.apkagent.agent.ToolRegistry
import com.apkagent.apktools.buildToolRegistry
import com.apkagent.shizuku.ShizukuManager
import com.apkagent.store.SettingsStore
import com.apkagent.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class ApkAgentApp : Application() {

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var toolRegistry: ToolRegistry
        private set

    /** 工作区目录：Agent 所有可写文件操作的沙箱根 */
    val workspace: File by lazy {
        File(filesDir, "workspace").apply { if (!exists()) mkdirs() }
    }

    private val _openApk = MutableStateFlow<File?>(null)
    /** 当前导入的 APK 文件（可空） */
    val openApk: StateFlow<File?> = _openApk.asStateFlow()

    fun setOpenApk(file: File?) { _openApk.value = file }

    override fun onCreate() {
        super.onCreate()
        Logger.init()
        Logger.i("App", "APKAgent v1.2.1 启动")
        settingsStore = SettingsStore(this)
        toolRegistry = buildToolRegistry()
        ShizukuManager.init()
    }

    override fun onTerminate() {
        super.onTerminate()
        ShizukuManager.destroy()
        Logger.close()
    }
}
