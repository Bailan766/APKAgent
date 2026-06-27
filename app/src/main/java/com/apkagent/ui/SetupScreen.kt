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
import com.apkagent.installer.InternalInstaller
import com.apkagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class InstallStatus {
    IDLE, CHECKING, NOT_INSTALLED, INSTALLING, INSTALLED, FAILED
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
    var isInstalling by remember { mutableStateOf(false) }

    // 启动时自动检测
    LaunchedEffect(Unit) {
        delay(300)
        // ── Python ──
        statusMessage = "检测 Python 环境..."
        PythonRunner.init(context)
        val pyPath = PythonRunner.findPython()
        if (pyPath != null) {
            pythonVersion = PythonRunner.getVersion() ?: "unknown"
            pythonStatus = InstallStatus.INSTALLED
            statusMessage = "✅ Python 已就绪: $pyPath"
            progress = 0.5f
        } else {
            // 尝试链接 Termux Python
            val linked = InternalInstaller.linkTermuxPython()
            if (linked != null) {
                pythonVersion = PythonRunner.getVersion() ?: "unknown"
                pythonStatus = InstallStatus.INSTALLED
                statusMessage = "✅ 已链接 Termux Python: $linked"
                progress = 0.5f
            } else {
                pythonStatus = InstallStatus.NOT_INSTALLED
                statusMessage = "未检测到 Python，点击安装"
            }
        }

        delay(200)
        // ── Node.js ──
        statusMessage = "检测 Node.js 环境..."
        nodeStatus = try {
            val paths = listOf(
                File(context.filesDir, "nodejs/bin/node").absolutePath,
                "/data/data/com.termux/files/usr/bin/node",
            )
            val found = paths.any { File(it).canExecute() }
            if (found) {
                nodeVersion = try {
                    val p = ProcessBuilder("node", "--version").start()
                    val v = p.inputStream.bufferedReader().readLine()?.trim() ?: ""
                    p.waitFor(); v
                } catch (_: Exception) { "" }
                InstallStatus.INSTALLED
            } else InstallStatus.NOT_INSTALLED
        } catch (_: Exception) { InstallStatus.NOT_INSTALLED }

        progress = if (pythonStatus == InstallStatus.INSTALLED) 0.7f else 0.1f
        statusMessage = if (pythonStatus == InstallStatus.INSTALLED && nodeStatus == InstallStatus.INSTALLED)
            "✅ 环境就绪，点击「开始使用」" else if (pythonStatus == InstallStatus.INSTALLED)
            "Python 就绪，Node.js 可选安装" else "点击下方按钮安装 Python"
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
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "APKAgent",
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
                isInstalling = isInstalling,
                onAction = {
                    when (pythonStatus) {
                        InstallStatus.NOT_INSTALLED, InstallStatus.FAILED -> {
                            scope.launch {
                                isInstalling = true
                                pythonStatus = InstallStatus.INSTALLING

                                val ok = InternalInstaller.installPython(context) { p ->
                                    statusMessage = p.message
                                    progress = 0.1f + p.progress * 0.5f
                                }

                                pythonStatus = if (ok) {
                                    pythonVersion = PythonRunner.getVersion() ?: ""
                                    InstallStatus.INSTALLED
                                } else {
                                    InstallStatus.FAILED
                                }
                                isInstalling = false
                            }
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
                isInstalling = isInstalling,
                onAction = {
                    when (nodeStatus) {
                        InstallStatus.NOT_INSTALLED, InstallStatus.FAILED -> {
                            scope.launch {
                                isInstalling = true
                                nodeStatus = InstallStatus.INSTALLING

                                val ok = InternalInstaller.installNode(context) { p ->
                                    statusMessage = p.message
                                    progress = 0.6f + p.progress * 0.3f
                                }

                                nodeStatus = if (ok) InstallStatus.INSTALLED else InstallStatus.FAILED
                                isInstalling = false
                            }
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

            // 终端按钮
            OutlinedButton(
                onClick = onOpenTerminal,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Terminal, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("打开内置终端", fontSize = 14.sp)
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
    isInstalling: Boolean,
    onAction: () -> Unit
) {
    val bgColor = when (status) {
        InstallStatus.INSTALLED -> MaterialTheme.colorScheme.primaryContainer
        InstallStatus.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
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
                    FilledTonalButton(
                        onClick = onAction,
                        enabled = !isInstalling,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("一键安装", fontSize = 13.sp)
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
