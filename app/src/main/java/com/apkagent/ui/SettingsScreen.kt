package com.apkagent.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkagent.ApkAgentApp
import com.apkagent.shizuku.ShizukuManager
import com.apkagent.store.AgentConfig
import com.apkagent.store.AiProvider
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ApkAgentApp
    val cfg = app.settingsStore.config.value
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var selectedProviderId by remember { mutableStateOf(cfg.providerId) }
    var apiKey by remember { mutableStateOf(cfg.apiKey) }
    var model by remember { mutableStateOf(cfg.model) }
    var temp by remember { mutableDoubleStateOf(cfg.temperature) }
    var sysExtra by remember { mutableStateOf(cfg.systemExtra) }
    var showKey by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showProviderMenu by remember { mutableStateOf(false) }

    val selectedProvider = AiProvider.byId(selectedProviderId)
    var customBaseUrl by remember { mutableStateOf(if (selectedProviderId == "custom") cfg.baseUrl else "") }
    var customModel by remember { mutableStateOf(if (selectedProviderId == "custom") cfg.model else "") }

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
            // ── Shizuku ──
            SectionTitle("🔐 系统权限")
            ShizukuStatusCard()

            // ── AI Provider ──
            SectionTitle("🤖 AI 模型")
            Text("选择提供商，填入 API Key 即可使用", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Provider 选择
            ExposedDropdownMenuBox(
                expanded = showProviderMenu,
                onExpandedChange = { showProviderMenu = it }
            ) {
                OutlinedTextField(
                    value = selectedProvider.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("AI 提供商") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showProviderMenu) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = showProviderMenu, onDismissRequest = { showProviderMenu = false }) {
                    AiProvider.PRESETS.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.label) },
                            onClick = {
                                selectedProviderId = p.id
                                showProviderMenu = false
                                if (p.id != "custom") {
                                    model = p.defaultModel
                                    customBaseUrl = ""
                                    customModel = ""
                                }
                            },
                            leadingIcon = {
                                if (p.id == selectedProviderId) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        )
                    }
                }
            }

            // Model 选择（非自定义时显示下拉）
            if (selectedProvider.id != "custom" && selectedProvider.models.isNotEmpty()) {
                var modelExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = it }) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("模型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                        selectedProvider.models.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = { model = m; modelExpanded = false },
                                leadingIcon = {
                                    if (m == model) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            )
                        }
                    }
                }
            }

            // API Key
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                placeholder = { Text("在此粘贴 API Key") },
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

            // 温度
            Text("温度：%.2f".format(temp), fontSize = 13.sp)
            Slider(value = temp.toFloat(), onValueChange = { temp = it.toDouble() }, valueRange = 0f..2f, modifier = Modifier.fillMaxWidth())

            // 高级选项
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "收起高级选项 ▲" else "展开高级选项 ▼", fontSize = 12.sp)
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = if (selectedProvider.id == "custom") customBaseUrl else selectedProvider.baseUrl,
                    onValueChange = { if (selectedProvider.id == "custom") customBaseUrl = it },
                    label = { Text("API Base URL") },
                    enabled = selectedProvider.id == "custom",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (selectedProvider.id == "custom") {
                    OutlinedTextField(
                        value = customModel,
                        onValueChange = { customModel = it },
                        label = { Text("模型名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = sysExtra,
                    onValueChange = { sysExtra = it },
                    label = { Text("附加提示词") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
                )
            }

            // 保存
            Spacer(Modifier.padding(4.dp))
            Button(onClick = {
                val baseUrl = if (selectedProvider.id == "custom") customBaseUrl else selectedProvider.baseUrl
                val finalModel = if (selectedProvider.id == "custom") customModel else model
                val newCfg = AgentConfig(selectedProviderId, baseUrl, apiKey.trim(), finalModel.trim(), temp, sysExtra)
                app.settingsStore.save(newCfg)
                scope.launch { snackbar.showSnackbar("✅ 已保存 — ${selectedProvider.label} / $finalModel") }
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Check, null); Spacer(Modifier.padding(4.dp)); Text("保存配置")
            }

            Text("API Key 加密存储于本机，不上传任何服务器。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ShizukuStatusCard() {
    val status = ShizukuManager.status
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var requesting by remember { mutableStateOf(false) }

    val (text, color, label, action) = when (status) {
        is ShizukuManager.Status.Authorized -> {
            val extra = if (status.uid > 0) " (UID: ${status.uid})" else ""
            ("✅ Shizuku 已授权$extra" to MaterialTheme.colorScheme.primary to null to null)
        }
        is ShizukuManager.Status.Running ->
            ("运行中，待授权" to MaterialTheme.colorScheme.tertiary to "授权" to {
                requesting = true
                scope.launch { ShizukuManager.requestPermission(); requesting = false }
            })
        is ShizukuManager.Status.Installed ->
            ("已安装未运行" to MaterialTheme.colorScheme.error to "授权" to {
                requesting = true
                scope.launch { ShizukuManager.requestPermission(); requesting = false }
            })
        else ->
            ("未检测到 Shizuku" to MaterialTheme.colorScheme.error to "安装" to {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
            })
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Security, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(text, fontSize = 12.sp, color = color)
            }
            if (label != null && action != null) {
                Button(onClick = { if (!requesting) action() }, enabled = !requesting, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    if (requesting) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Text(label, fontSize = 12.sp)
                }
            }
        }
    }
}
