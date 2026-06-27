package com.apkagent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.apkagent.ApkAgentApp
import com.apkagent.shizuku.ShizukuManager
import com.apkagent.store.AgentConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ApkAgentApp
    val cfg = app.settingsStore.config.value
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var baseUrl by remember { mutableStateOf(cfg.baseUrl) }
    var apiKey by remember { mutableStateOf(cfg.apiKey) }
    var model by remember { mutableStateOf(cfg.model) }
    var temp by remember { mutableDoubleStateOf(cfg.temperature) }
    var sysExtra by remember { mutableStateOf(cfg.systemExtra) }
    var showKey by remember { mutableStateOf(false) }

    // Shizuku 状态
    var shizukuStatus by remember { mutableStateOf<ShizukuManager.Status>(ShizukuManager.status) }
    var shizukuRequesting by remember { mutableStateOf(false) }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* 返回后刷新状态 */ }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Shizuku 授权 ──
            SectionTitle("🔐 系统权限")
            ShizukuCard(
                status = shizukuStatus,
                requesting = shizukuRequesting,
                onRequestShizuku = {
                    shizukuRequesting = true
                    scope.launch {
                        val ok = ShizukuManager.requestPermission()
                        shizukuStatus = ShizukuManager.status
                        shizukuRequesting = false
                        snackbar.showSnackbar(
                            if (ok) "✅ Shizuku 授权成功，已获得系统级权限"
                            else "❌ Shizuku 授权失败/超时，请确保 Shizuku App 正在运行"
                        )
                    }
                },
                onRequestManageStorage = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        manageStorageLauncher.launch(intent)
                    } else {
                        scope.launch { snackbar.showSnackbar("Android 11 以下无需此权限") }
                    }
                },
                onInstallShizuku = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://shizuku.rikka.app/download/")
                    }
                    context.startActivity(intent)
                }
            )

            SectionTitle("AI 模型配置")

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("API Base URL") },
                placeholder = { Text("https://api.openai.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "兼容 OpenAI 协议的接口地址，可填第三方中转。会自动拼接 /chat/completions。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "隐藏" else "显示", fontSize = 12.sp)
                    }
                }
            )

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型名") },
                placeholder = { Text("gpt-4o-mini / deepseek-chat / qwen-plus …") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("温度：%.2f".format(temp), fontSize = 13.sp)
            Slider(
                value = temp.toFloat(),
                onValueChange = { temp = it.toDouble() },
                valueRange = 0f..2f,
                modifier = Modifier.fillMaxWidth()
            )

            SectionTitle("系统提示补充（可选）")
            OutlinedTextField(
                value = sysExtra,
                onValueChange = { sysExtra = it },
                label = { Text("附加指令") },
                placeholder = { Text("例如：默认用英文类名分析…") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
            )

            Spacer(Modifier.padding(4.dp))
            Button(
                onClick = {
                    val newCfg = AgentConfig(baseUrl.trim(), apiKey.trim(), model.trim(), temp, sysExtra)
                    app.settingsStore.save(newCfg)
                    scope.launch { snackbar.showSnackbar("已保存") }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.padding(4.dp))
                Text("保存配置")
            }

            Text(
                "提示：API Key 通过 EncryptedSharedPreferences 加密存储于本机。所有文件工具默认在 App 工作区沙箱内操作。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ShizukuCard(
    status: ShizukuManager.Status,
    requesting: Boolean,
    onRequestShizuku: () -> Unit,
    onRequestManageStorage: () -> Unit,
    onInstallShizuku: () -> Unit
) {
    data class StatusInfo(
        val text: String,
        val color: androidx.compose.ui.graphics.Color,
        val actionLabel: String?,
        val action: (() -> Unit)?
    )

    val info = when (status) {
        is ShizukuManager.Status.Authorized -> {
            val extra = if (status.uid > 0) " (UID: ${status.uid})" else ""
            StatusInfo("✅ Shizuku 已授权，拥有系统级权限$extra", MaterialTheme.colorScheme.primary, null, null)
        }
        is ShizukuManager.Status.Running ->
            StatusInfo("Shizuku 运行中，待授权", MaterialTheme.colorScheme.tertiary, "点击授权", onRequestShizuku)
        is ShizukuManager.Status.Installed ->
            StatusInfo("⚠ Shizuku 已安装但未运行", MaterialTheme.colorScheme.error, "启动 Shizuku 并授权", onRequestShizuku)
        is ShizukuManager.Status.Unavailable ->
            StatusInfo("❌ 未检测到 Shizuku", MaterialTheme.colorScheme.error, "安装 Shizuku", onInstallShizuku)
        is ShizukuManager.Status.Error ->
            StatusInfo("⚠ Shizuku 错误：${status.message}", MaterialTheme.colorScheme.error, "重试", onRequestShizuku)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Security,
                    contentDescription = null,
                    tint = info.color,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Shizuku 系统权限",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }

            Text(
                info.text,
                fontSize = 12.sp,
                color = info.color,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (info.actionLabel != null && info.action != null) {
                    Button(
                        onClick = {
                            if (!requesting) info.action()
                        },
                        enabled = !requesting,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        if (requesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(info.actionLabel, fontSize = 13.sp)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    !Environment.isExternalStorageManager()
                ) {
                    OutlinedButton(
                        onClick = onRequestManageStorage,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("存储权限", fontSize = 12.sp)
                    }
                }
            }

            if (status !is ShizukuManager.Status.Authorized) {
                Text(
                    "Shizuku 可获得 ADB 级权限，允许 APKAgent 直接访问 /data/app/ 等受限目录，绕过 SAF 限制。需先在 Shizuku App 中启动服务。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp)
                )
            }
        }
    }
}
