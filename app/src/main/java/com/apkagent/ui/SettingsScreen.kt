package com.apkagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkagent.ApkAgentApp
import com.apkagent.store.AgentConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ApkAgentApp
    val cfg = app.settingsStore.config.value
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var baseUrl by remember { mutableStateOf(cfg.baseUrl) }
    var apiKey by remember { mutableStateOf(cfg.apiKey) }
    var model by remember { mutableStateOf(cfg.model) }
    var temp by remember { mutableDoubleStateOf(cfg.temperature) }
    var sysExtra by remember { mutableStateOf(cfg.systemExtra) }
    var showKey by remember { mutableStateOf(false) }

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
                    Text(
                        if (showKey) "隐藏" else "显示",
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { showKey = !showKey }
                    )
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
                "提示：API Key 通过 EncryptedSharedPreferences 加密存储于本机。所有文件工具仅在 App 工作区沙箱内操作。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
