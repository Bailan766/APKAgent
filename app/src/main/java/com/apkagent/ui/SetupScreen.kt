package com.apkagent.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkagent.apktools.PythonRunner
import com.apkagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class InstallStatus {
    IDLE, CHECKING, NOT_INSTALLED, INSTALLING, INSTALLED, FAILED, NEED_MANUAL
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onSetupComplete: () -> Unit, onOpenTerminal: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var pythonStatus by remember { mutableStateOf(InstallStatus.CHECKING) }
    var nodeStatus by remember { mutableStateOf(InstallStatus.CHECKING) }
    var statusMessage by remember { mutableStateOf("检测环境...") }
    var progress by remember { mutableFloatStateOf(0f) }
    var pythonVersion by remember { mutableStateOf("") }
    var nodeVersion by remember { mutableStateOf("") }

    // 启动时自动检测
    LaunchedEffect(Unit) {
        delay(300)
        // ── Python ──
        statusMessage = "检测 Python 环境..."
        val pyPath = PythonRunner.findPython()
        if (pyPath != null) {
            pythonVersion = PythonRunner.getVersion() ?: "unknown"
            pythonStatus = InstallStatus.INSTALLED
            statusMessage = "✅ Python 已就绪: $pyPath"
            progress = 0.4f
        } else {
            pythonStatus = InstallStatus.NOT_INSTALLED
            statusMessage = "未检测到 Python 环境"
        }

        delay(200)
        // ── Node.js ──
        statusMessage = "检测 Node.js 环境..."
        nodeStatus = try {
            val paths = listOf(
                "/data/data/com.termux/files/usr/bin/node",
                "/system/bin/node",
                "/sdcard/nodejs/bin/node"
            )
            val found = paths.any { File(it).canExecute() }
                    || run {
                val p = ProcessBuilder("which", "node").start()
                val out = p.inputStream.bufferedReader().readLine()
                p.waitFor()
                !out.isNullOrBlank()
            }
            if (found) {
                nodeVersion = try {
                    val p = ProcessBuilder("node", "--version").start()
                    val v = p.inputStream.bufferedReader().readLine()?.trim() ?: ""
                    p.waitFor(); v
                } catch (_: Exception) { "" }
                InstallStatus.INSTALLED
            } else {
                InstallStatus.NOT_INSTALLED
            }
        } catch (_: Exception) {
            InstallStatus.NOT_INSTALLED
        }
        progress = if (pythonStatus == InstallStatus.INSTALLED) 0.6f else 0.1f
        statusMessage = if (pythonStatus == InstallStatus.INSTALLED && nodeStatus == InstallStatus.INSTALLED)
            "✅ 环境就绪，点击「完成」开始使用" else "请安装下方缺少的组件"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // ── Logo ──
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Android,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "欢迎使用 APKAgent",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "首次使用需要配置运行环境",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 进度条
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Python ──
            EnvCard(
                title = "Python 运行环境",
                subtitle = if (pythonVersion.isNotEmpty()) "v$pythonVersion" else "必需 · 脚本分析引擎",
                icon = Icons.Default.Code,
                status = pythonStatus,
                required = true,
                onAction = {
                    when (pythonStatus) {
                        InstallStatus.NOT_INSTALLED, InstallStatus.FAILED -> {
                            scope.launch {
                                pythonStatus = InstallStatus.INSTALLING
                                statusMessage = "正在查找 Python..."
                                progress = 0.15f

                                val ok = installPython(context) { msg, p ->
                                    statusMessage = msg
                                    progress = p
                                }
                                pythonStatus = if (ok) {
                                    pythonVersion = PythonRunner.getVersion() ?: ""
                                    statusMessage = "✅ Python 就绪"
                                    InstallStatus.INSTALLED
                                } else {
                                    statusMessage = "自动安装失败，请手动安装 Termux + Python"
                                    InstallStatus.NEED_MANUAL
                                }
                            }
                        }
                        InstallStatus.NEED_MANUAL -> {
                            // 打开 Termux F-Droid 页面
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://f-droid.org/packages/com.termux/")))
                            } catch (_: Exception) {}
                        }
                        else -> {}
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Node.js ──
            EnvCard(
                title = "Node.js 运行环境",
                subtitle = if (nodeVersion.isNotEmpty()) nodeVersion else "可选 · JS 工具支持",
                icon = Icons.Default.AccountTree,
                status = nodeStatus,
                required = false,
                onAction = {
                    when (nodeStatus) {
                        InstallStatus.NOT_INSTALLED, InstallStatus.FAILED -> {
                            scope.launch {
                                nodeStatus = InstallStatus.INSTALLING
                                statusMessage = "正在查找 Node.js..."

                                val ok = installNode(context) { msg, _ ->
                                    statusMessage = msg
                                }
                                nodeStatus = if (ok) {
                                    statusMessage = "✅ Node.js 就绪"
                                    InstallStatus.INSTALLED
                                } else {
                                    statusMessage = "请在 Termux 中运行: pkg install nodejs"
                                    InstallStatus.NEED_MANUAL
                                }
                            }
                        }
                        InstallStatus.NEED_MANUAL -> {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://f-droid.org/packages/com.termux/")))
                            } catch (_: Exception) {}
                        }
                        else -> {}
                    }
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 状态消息
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(14.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 内置终端按钮
            OutlinedButton(
                onClick = onOpenTerminal,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Terminal, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("打开内置终端安装环境", fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 完成按钮
            Button(
                onClick = onSetupComplete,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = pythonStatus == InstallStatus.INSTALLED,
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("开始使用", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            // 跳过
            TextButton(onClick = onSetupComplete, modifier = Modifier.padding(top = 4.dp)) {
                Text("稍后配置", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EnvCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    status: InstallStatus,
    required: Boolean,
    onAction: () -> Unit
) {
    val bgColor = when (status) {
        InstallStatus.INSTALLED -> MaterialTheme.colorScheme.primaryContainer
        InstallStatus.FAILED, InstallStatus.NEED_MANUAL -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (required) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text("必需", fontSize = 9.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            when (status) {
                InstallStatus.CHECKING, InstallStatus.INSTALLING -> {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                InstallStatus.NOT_INSTALLED, InstallStatus.FAILED -> {
                    FilledTonalButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                        Text("安装", fontSize = 13.sp)
                    }
                }
                InstallStatus.NEED_MANUAL -> {
                    FilledTonalButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                        Text("下载 Termux", fontSize = 12.sp)
                    }
                }
                InstallStatus.INSTALLED -> {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                InstallStatus.IDLE -> {}
            }
        }
    }
}

// ──── 安装逻辑 ────

private suspend fun installPython(
    context: android.content.Context,
    onProgress: (String, Float) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    // 1) 已有 Python?
    PythonRunner.findPython()?.let {
        onProgress("✅ 已找到: $it", 0.4f)
        return@withContext true
    }

    // 2) 检查 Termux Python
    onProgress("检查 Termux...", 0.15f)
    val termuxPy = "/data/data/com.termux/files/usr/bin/python3"
    if (File(termuxPy).canExecute()) {
        PythonRunner.pythonPath = termuxPy
        onProgress("✅ Termux Python 就绪", 0.4f)
        return@withContext true
    }

    // 3) 检查 QPython
    onProgress("检查 QPython...", 0.2f)
    val qpyPaths = listOf(
        "/data/data/org.qpython.qpy3/files/bin/python3",
        "/data/data/org.qpython.qpy/files/bin/python"
    )
    for (p in qpyPaths) {
        if (File(p).canExecute()) {
            PythonRunner.pythonPath = p
            onProgress("✅ QPython 就绪", 0.4f)
            return@withContext true
        }
    }

    // 4) 系统 which
    onProgress("搜索系统 Python...", 0.25f)
    try {
        val proc = ProcessBuilder("sh", "-c", "which python3 2>/dev/null || which python 2>/dev/null").start()
        val out = proc.inputStream.bufferedReader().readLine()?.trim()
        proc.waitFor()
        if (!out.isNullOrEmpty() && File(out).canExecute()) {
            PythonRunner.pythonPath = out
            onProgress("✅ 找到: $out", 0.4f)
            return@withContext true
        }
    } catch (_: Exception) {}

    onProgress("未找到 Python，请安装 Termux 后运行: pkg install python", 0.3f)
    false
}

private suspend fun installNode(
    context: android.content.Context,
    onProgress: (String, Float) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    val nodePaths = listOf(
        "/data/data/com.termux/files/usr/bin/node",
        "/system/bin/node",
        "/sdcard/nodejs/bin/node"
    )
    for (p in nodePaths) {
        if (File(p).canExecute()) {
            onProgress("✅ Node.js: $p", 0.8f)
            return@withContext true
        }
    }
    try {
        val proc = ProcessBuilder("which", "node").start()
        val out = proc.inputStream.bufferedReader().readLine()?.trim()
        proc.waitFor()
        if (!out.isNullOrEmpty()) {
            onProgress("✅ Node.js: $out", 0.8f)
            return@withContext true
        }
    } catch (_: Exception) {}

    onProgress("未找到 Node.js，请在 Termux 中运行: pkg install nodejs", 0.7f)
    false
}
