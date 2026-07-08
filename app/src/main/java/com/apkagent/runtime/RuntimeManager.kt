package com.apkagent.runtime

import android.content.Context
import com.apkagent.apktools.PythonRunner
import com.apkagent.shizuku.ShizukuManager
import com.apkagent.terminal.CommandExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class RuntimeManager(private val context: Context) {

    data class RuntimeStatus(
        val pythonInstalled: Boolean,
        val pythonPath: String?,
        val pythonVersion: String?,
        val nodeInstalled: Boolean,
        val nodePath: String?,
        val nodeVersion: String?,
        val shizukuAuthorized: Boolean
    )

    suspend fun getStatus(): RuntimeStatus = withContext(Dispatchers.IO) {
        PythonRunner.init(context)
        val pythonPath = PythonRunner.findPython()
        val pythonVersion = PythonRunner.getVersion()

        val nodeCandidates = listOf(
            File(context.filesDir, "nodejs/bin/node").absolutePath,
            "/data/local/tmp/apkagent_node/bin/node",
            "/data/data/com.termux/files/usr/bin/node"
        )
        val nodePath = nodeCandidates.firstOrNull { File(it).exists() }
        val nodeVersion = nodePath?.let { detectVersion(it) }

        RuntimeStatus(
            pythonInstalled = pythonPath != null,
            pythonPath = pythonPath,
            pythonVersion = pythonVersion,
            nodeInstalled = nodePath != null,
            nodePath = nodePath,
            nodeVersion = nodeVersion,
            shizukuAuthorized = ShizukuManager.isAuthorized()
        )
    }

    suspend fun runShell(
        command: String,
        workDir: File? = null,
        timeoutSeconds: Int = 120
    ): CommandExecutor.ExecResult {
        return CommandExecutor.execute(command = command, workDir = workDir, timeoutSeconds = timeoutSeconds)
    }

    private suspend fun detectVersion(binaryPath: String): String? {
        val quoted = binaryPath.replace("'", "'\\''")
        val direct = runShell("'$quoted' --version", timeoutSeconds = 20)
        return direct.output?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }
}
