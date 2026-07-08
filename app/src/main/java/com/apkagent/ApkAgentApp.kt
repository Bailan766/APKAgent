package com.apkagent

import android.app.Application
import com.apkagent.agent.ToolRegistry
import com.apkagent.apktools.PythonRunner
import com.apkagent.apktools.buildToolRegistry
import com.apkagent.project.ProjectStore
import com.apkagent.project.ReverseProject
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

    lateinit var projectStore: ProjectStore
        private set

    // 工作区放在 /sdcard/Download/APKAgent/（外部存储，shell uid 可访问，用户可见）
    val workspace: File by lazy {
        val ext = getExternalFilesDir(null)
        val downloadWs = File("/sdcard/Download/APKAgent")
        val ws = if (ext != null) {
            try {
                downloadWs.apply { mkdirs() }
                if (downloadWs.canWrite()) downloadWs else File(filesDir, "workspace")
            } catch (_: Throwable) {
                File(filesDir, "workspace")
            }
        } else File(filesDir, "workspace")
        ws.apply { if (!exists()) mkdirs() }
    }

    private val _currentProject = MutableStateFlow<ReverseProject?>(null)
    val currentProject: StateFlow<ReverseProject?> = _currentProject.asStateFlow()

    fun setCurrentProject(project: ReverseProject?) {
        _currentProject.value = project
    }

    fun currentOpenApk(): File? = currentProject.value?.importedApkPath?.let(::File)?.takeIf { it.exists() }

    override fun onCreate() {
        super.onCreate()
        Logger.init()
        Logger.setupCrashHandler()
        Logger.i("App", "APKAgent v3.5.0 启动 — SDK=${android.os.Build.VERSION.SDK_INT}")
        settingsStore = SettingsStore(this)
        projectStore = ProjectStore(this, workspace)
        PythonRunner.init(this)
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
