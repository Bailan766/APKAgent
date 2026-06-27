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

    val workspace: File by lazy {
        File(filesDir, "workspace").apply { if (!exists()) mkdirs() }
    }

    private val _openApk = MutableStateFlow<File?>(null)
    val openApk: StateFlow<File?> = _openApk.asStateFlow()

    fun setOpenApk(file: File?) { _openApk.value = file }

    override fun onCreate() {
        super.onCreate()
        Logger.init()
        Logger.setupCrashHandler()
        Logger.i("App", "APKAgent v1.2.4 启动 — SDK=${android.os.Build.VERSION.SDK_INT}")
        settingsStore = SettingsStore(this)
        toolRegistry = buildToolRegistry()
        ShizukuManager.init()
        Logger.i("App", "初始化完成 — workspace=${workspace.absolutePath}")
    }

    override fun onTerminate() {
        super.onTerminate()
        ShizukuManager.destroy()
        Logger.close()
    }
}
