package com.apkagent.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkagent.ApkAgentApp
import com.apkagent.agent.OpenAIClient
import com.apkagent.i18n.AppLanguage
import com.apkagent.i18n.LocalAppStrings
import com.apkagent.service.KeepAliveManager
import com.apkagent.shizuku.ShizukuManager
import com.apkagent.store.AgentConfig
import com.apkagent.store.ExecutionMode
import com.apkagent.store.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onOpenTerminal: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as ApkAgentApp
    val s = LocalAppStrings.current
    var language by remember { mutableStateOf(app.settingsStore.getLanguage()) }
    var showLangMenu by remember { mutableStateOf(false) }

    val cfg by app.settingsStore.config.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var selectedProviderId by remember { mutableStateOf(cfg.providerId) }
    var apiKey by remember { mutableStateOf(cfg.apiKey) }
    var baseUrl by remember {
        mutableStateOf(cfg.baseUrl.ifBlank { AiProvider.byId(cfg.providerId).baseUrl })
    }
    var model by remember {
        mutableStateOf(cfg.model.ifBlank { AiProvider.byId(cfg.providerId).defaultModel })
    }
    var temp by remember { mutableDoubleStateOf(cfg.temperature) }
    var sysExtra by remember { mutableStateOf(cfg.systemExtra) }
    var showKey by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showProviderMenu by remember { mutableStateOf(false) }

    val selectedProvider = AiProvider.byId(selectedProviderId)

    // 动态模型列表与在线校验状态
    var dynamicModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var modelFetchError by remember { mutableStateOf<String?>(null) }
    var verifySuccessMsg by remember { mutableStateOf<String?>(null) }

    var execMode by remember { mutableStateOf(app.settingsStore.getExecutionMode()) }

    // 统一保存函数：对所有字段做规范化兜底并立即持久化
    fun saveCurrentConfig(
        pid: String = selectedProviderId,
        rawUrl: String = baseUrl,
        rawKey: String = apiKey,
        rawModel: String = model,
        temperature: Double = temp,
        extra: String = sysExtra,
        mode: ExecutionMode = execMode
    ) {
        val provider = AiProvider.byId(pid)
        val cleanKey = OpenAIClient.sanitizeApiKey(rawKey)
        val cleanUrl = OpenAIClient.normalizeBaseUrl(rawUrl).ifBlank {
            provider.baseUrl.ifBlank { "https://api.deepseek.com/v1" }
        }
        val cleanModel = rawModel.trim().ifBlank {
            provider.defaultModel.ifBlank {
                dynamicModels.firstOrNull() ?: provider.models.firstOrNull() ?: "deepseek-chat"
            }
        }
        val newCfg = AgentConfig(
            providerId = pid,
            baseUrl = cleanUrl,
            apiKey = cleanKey,
            model = cleanModel,
            temperature = temperature,
            systemExtra = extra,
            executionMode = mode.id
        )
        app.settingsStore.save(newCfg)
        app.settingsStore.setExecutionMode(mode)
    }

    // 退出页面时强制保存一次，确保左滑返回或点击返回箭头不丢失任何输入
    val handleBack = {
        saveCurrentConfig()
        onBack()
    }
    BackHandler { handleBack() }

    // 智能验证 API Key 并拉取可用模型（支持跨平台自动识别回退）
    fun verifyAndFetchModels(autoTriggered: Boolean = false) {
        val cleanKey = OpenAIClient.sanitizeApiKey(apiKey)
        if (cleanKey.isBlank() && selectedProviderId != "ollama") {
            if (!autoTriggered) {
                scope.launch { snackbar.showSnackbar(s.enterApiKeyFirst) }
            }
            return
        }
        apiKey = cleanKey
        saveCurrentConfig(rawKey = cleanKey)

        isLoadingModels = true
        modelFetchError = null
        if (!autoTriggered) verifySuccessMsg = null

        scope.launch(Dispatchers.IO) {
            val targetUrl = baseUrl.ifBlank { selectedProvider.baseUrl }
            val client = OpenAIClient(targetUrl, cleanKey)
            val result = client.fetchAvailableModels(
                fallbackModels = selectedProvider.models,
                testModel = model.ifBlank { selectedProvider.defaultModel }
            )

            result.onSuccess { models ->
                withContext(Dispatchers.Main) {
                    dynamicModels = models
                    if (models.isNotEmpty() && (model.isBlank() || (model !in models && selectedProviderId != "custom"))) {
                        val preferred = selectedProvider.defaultModel.takeIf { it in models } ?: models.first()
                        model = preferred
                    }
                    saveCurrentConfig(rawKey = cleanKey, rawModel = model)
                    verifySuccessMsg = "API Key 验证有效 (${selectedProvider.label} · ${models.size} 个模型)"
                    modelFetchError = null
                    isLoadingModels = false
                    if (!autoTriggered) {
                        snackbar.showSnackbar("✅ $verifySuccessMsg")
                    }
                }
            }.onFailure { err ->
                // 如果用户未修改自定义 URL 且报鉴权错误，自动探测该 Key 属于哪个预设平台
                val isUsingDefaultPresetUrl = selectedProviderId != "custom" &&
                    OpenAIClient.normalizeBaseUrl(baseUrl) == OpenAIClient.normalizeBaseUrl(selectedProvider.baseUrl)
                val probed = if (isUsingDefaultPresetUrl || baseUrl.isBlank()) {
                    OpenAIClient.probeWorkingProvider(cleanKey)
                } else null

                withContext(Dispatchers.Main) {
                    if (probed != null) {
                        val (matchedProvider, matchedModels) = probed
                        selectedProviderId = matchedProvider.id
                        baseUrl = matchedProvider.baseUrl
                        dynamicModels = matchedModels
                        model = matchedProvider.defaultModel.takeIf { it in matchedModels }
                            ?: matchedModels.firstOrNull()
                            ?: matchedProvider.defaultModel
                        saveCurrentConfig(
                            pid = matchedProvider.id,
                            rawUrl = matchedProvider.baseUrl,
                            rawKey = cleanKey,
                            rawModel = model
                        )
                        verifySuccessMsg = "已自动识别为【${matchedProvider.label}】并完成配置"
                        modelFetchError = null
                        isLoadingModels = false
                        snackbar.showSnackbar("✅ $verifySuccessMsg")
                    } else {
                        modelFetchError = err.message
                        verifySuccessMsg = null
                        isLoadingModels = false
                        if (!autoTriggered) {
                            snackbar.showSnackbar(s.fmt(s.fetchFailed, "msg" to err.message?.take(60)))
                        }
                    }
                }
            }
        }
    }

    // 当用户输入/粘贴 API Key 后，自动根据特征前缀切换平台并在后台静默校验
    LaunchedEffect(apiKey) {
        val clean = OpenAIClient.sanitizeApiKey(apiKey)
        if (clean.length >= 12) {
            if (selectedProviderId != "custom") {
                AiProvider.detectFromKeyPrefix(clean)?.let { detected ->
                    if (detected.id != selectedProviderId) {
                        selectedProviderId = detected.id
                        baseUrl = detected.baseUrl
                        model = detected.defaultModel
                        dynamicModels = emptyList()
                    }
                }
            }
            saveCurrentConfig(rawKey = clean)
            delay(450)
            verifyAndFetchModels(autoTriggered = true)
        }
    }

    // Theme state
    var currentTheme by remember { mutableStateOf(ThemeState.currentTheme) }

    val wallpaperLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                val tmp = java.io.File(context.cacheDir, "wallpaper_tmp")
                input?.use { it.copyTo(tmp.outputStream()) }
                input?.close()
                ThemeState.loadWallpaper(tmp.absolutePath)
                ThemeState.currentTheme = AppTheme.CUSTOM_WALLPAPER
                currentTheme = AppTheme.CUSTOM_WALLPAPER
                scope.launch { snackbar.showSnackbar(s.wallpaperSet) }
            } catch (_: Throwable) {
                scope.launch { snackbar.showSnackbar(s.wallpaperLoadFailed) }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(s.settings) },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, s.back)
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            saveCurrentConfig()
                            verifyAndFetchModels(autoTriggered = false)
                        }
                    ) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(s.save, fontWeight = FontWeight.Bold)
                    }
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
            // ── 1. AI 模型与 API Key 配置（置顶第一优先级） ──
            SectionTitle(s.sectionAi)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 实时生效状态指示条
                    val isConfigured = cfg.isValid()
                    Surface(
                        color = if (isConfigured)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else
                            MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isConfigured) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (isConfigured) {
                                    "当前已生效：${AiProvider.byId(cfg.providerId).label} · ${cfg.model}"
                                } else {
                                    "尚未填写 API Key — 粘贴后将自动识别并保存"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // AI 提供商选择
                    ExposedDropdownMenuBox(
                        expanded = showProviderMenu,
                        onExpandedChange = { showProviderMenu = it }
                    ) {
                        OutlinedTextField(
                            value = selectedProvider.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(s.aiProvider) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showProviderMenu) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = showProviderMenu,
                            onDismissRequest = { showProviderMenu = false }
                        ) {
                            AiProvider.PRESETS.forEach { p ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(p.label, fontWeight = FontWeight.Medium)
                                            if (p.baseUrl.isNotBlank()) {
                                                Text(p.baseUrl, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                                            }
                                        }
                                    },
                                    onClick = {
                                        selectedProviderId = p.id
                                        showProviderMenu = false
                                        dynamicModels = emptyList()
                                        modelFetchError = null
                                        verifySuccessMsg = null
                                        if (p.id != "custom") {
                                            baseUrl = p.baseUrl
                                            model = p.defaultModel
                                        }
                                        saveCurrentConfig(
                                            pid = p.id,
                                            rawUrl = if (p.id != "custom") p.baseUrl else baseUrl,
                                            rawModel = if (p.id != "custom") p.defaultModel else model
                                        )
                                    },
                                    leadingIcon = {
                                        if (p.id == selectedProviderId) {
                                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // API Key 输入框（支持一键粘贴、清空、显示/隐藏，实时自动保存）
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { newValue ->
                            apiKey = newValue
                            modelFetchError = null
                            saveCurrentConfig(rawKey = newValue)
                        },
                        label = { Text("API Key") },
                        placeholder = { Text(s.pasteApiKey) },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 一键粘贴按钮
                                IconButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        val pasted = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                        if (pasted.isNotBlank()) {
                                            val clean = OpenAIClient.sanitizeApiKey(pasted)
                                            apiKey = clean
                                            saveCurrentConfig(rawKey = clean)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.ContentPaste, "粘贴", modifier = Modifier.size(18.dp))
                                }
                                if (apiKey.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            apiKey = ""
                                            verifySuccessMsg = null
                                            modelFetchError = null
                                            saveCurrentConfig(rawKey = "")
                                        }
                                    ) {
                                        Icon(Icons.Default.Clear, "清空", modifier = Modifier.size(18.dp))
                                    }
                                }
                                TextButton(onClick = { showKey = !showKey }) {
                                    Text(if (showKey) s.hide else s.show, fontSize = 12.sp)
                                }
                            }
                        }
                    )

                    // API Base URL（直接展示，所有提供商均可查看或修改为中转站地址）
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { newUrl ->
                            baseUrl = newUrl
                            saveCurrentConfig(rawUrl = newUrl)
                        },
                        label = { Text("API Base URL (接口地址)") },
                        placeholder = { Text("https://api.deepseek.com/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (selectedProvider.baseUrl.isNotBlank() && baseUrl != selectedProvider.baseUrl) {
                                TextButton(
                                    onClick = {
                                        baseUrl = selectedProvider.baseUrl
                                        saveCurrentConfig(rawUrl = selectedProvider.baseUrl)
                                    }
                                ) {
                                    Text("默认", fontSize = 11.sp)
                                }
                            }
                        }
                    )

                    // 模型名称（既可直接编辑输入任意模型名，也可从下拉菜单选择）
                    val candidateModels = remember(dynamicModels, selectedProviderId) {
                        (dynamicModels + selectedProvider.models).distinct()
                    }
                    var modelExpanded by remember { mutableStateOf(false) }

                    ExposedDropdownMenuBox(
                        expanded = modelExpanded && candidateModels.isNotEmpty(),
                        onExpandedChange = { if (candidateModels.isNotEmpty()) modelExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = model,
                            onValueChange = { newModel ->
                                model = newModel
                                saveCurrentConfig(rawModel = newModel)
                            },
                            singleLine = true,
                            label = { Text("${s.model} (可下拉选择或直接输入)") },
                            trailingIcon = {
                                if (candidateModels.isNotEmpty()) {
                                    IconButton(onClick = { modelExpanded = !modelExpanded }) {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        if (candidateModels.isNotEmpty()) {
                            ExposedDropdownMenu(
                                expanded = modelExpanded,
                                onDismissRequest = { modelExpanded = false }
                            ) {
                                candidateModels.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m) },
                                        onClick = {
                                            model = m
                                            modelExpanded = false
                                            saveCurrentConfig(rawModel = m)
                                        },
                                        leadingIcon = {
                                            if (m == model) {
                                                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 快捷模型标签（一键切换）
                    if (candidateModels.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(candidateModels.take(8)) { m ->
                                FilterChip(
                                    selected = model == m,
                                    onClick = {
                                        model = m
                                        saveCurrentConfig(rawModel = m)
                                    },
                                    label = { Text(m, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    // 校验状态反馈
                    if (verifySuccessMsg != null) {
                        Text(
                            text = "✅ $verifySuccessMsg",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (modelFetchError != null) {
                        Text(
                            text = "⚠️ $modelFetchError",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // 操作按钮行：验证并获取模型 + 保存配置
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { verifyAndFetchModels(autoTriggered = false) },
                            enabled = (apiKey.isNotBlank() || selectedProviderId == "ollama") && !isLoadingModels,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isLoadingModels) {
                                CircularProgressIndicator(
                                    Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(s.fetching, fontSize = 12.sp)
                            } else {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("验证 Key / 获取模型", fontSize = 12.sp)
                            }
                        }

                        Button(
                            onClick = {
                                saveCurrentConfig()
                                scope.launch {
                                    snackbar.showSnackbar(
                                        s.fmt(s.savedAs, "name" to "${selectedProvider.label} (${model.ifBlank { selectedProvider.defaultModel }})")
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.save, fontSize = 12.sp)
                        }
                    }

                    // 高级参数折叠区（温度 + 附加系统提示词）
                    TextButton(
                        onClick = { showAdvanced = !showAdvanced },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(if (showAdvanced) s.collapseAdvanced else s.expandAdvanced, fontSize = 12.sp)
                    }
                    if (showAdvanced) {
                        Text(s.temperature.format(temp), fontSize = 13.sp)
                        Slider(
                            value = temp.toFloat(),
                            onValueChange = {
                                temp = it.toDouble()
                                saveCurrentConfig(temperature = temp)
                            },
                            valueRange = 0f..2f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = sysExtra,
                            onValueChange = {
                                sysExtra = it
                                saveCurrentConfig(extra = sysExtra)
                            },
                            label = { Text(s.systemExtra) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
                        )
                    }
                }
            }

            // ── 1.5 自动化运行模式 / Execution Mode ──
            SectionTitle(s.executionModeLabel)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            s.executionModeLabel,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    Text(
                        "控制 Agent 自动化执行时的敏感操作拦截策略。默认采用快速模式，实现无阻碍自动化逆向。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 快速模式选项
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (execMode == ExecutionMode.FAST) Color(0xFFF59E0B).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (execMode == ExecutionMode.FAST) Color(0xFFF59E0B) else Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                execMode = ExecutionMode.FAST
                                saveCurrentConfig(mode = ExecutionMode.FAST)
                            }
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = execMode == ExecutionMode.FAST,
                                onClick = {
                                    execMode = ExecutionMode.FAST
                                    saveCurrentConfig(mode = ExecutionMode.FAST)
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    s.modeFast + " (推荐)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (execMode == ExecutionMode.FAST) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    s.modeFastDesc,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 安全模式选项
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (execMode == ExecutionMode.SAFE) Color(0xFF3B82F6).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (execMode == ExecutionMode.SAFE) Color(0xFF3B82F6) else Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                execMode = ExecutionMode.SAFE
                                saveCurrentConfig(mode = ExecutionMode.SAFE)
                            }
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = execMode == ExecutionMode.SAFE,
                                onClick = {
                                    execMode = ExecutionMode.SAFE
                                    saveCurrentConfig(mode = ExecutionMode.SAFE)
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    s.modeSafe,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (execMode == ExecutionMode.SAFE) Color(0xFF3B82F6) else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    s.modeSafeDesc,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ── 2. 语言 / Language ──
            SectionTitle(s.sectionLanguage)
            Text(s.languageHint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ExposedDropdownMenuBox(expanded = showLangMenu, onExpandedChange = { showLangMenu = it }) {
                val langLabel = when (language) {
                    AppLanguage.SYSTEM -> s.langSystem
                    AppLanguage.ZH -> s.langZh
                    AppLanguage.EN -> s.langEn
                }
                OutlinedTextField(
                    value = langLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(s.languageLabel) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showLangMenu) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = showLangMenu, onDismissRequest = { showLangMenu = false }) {
                    listOf(AppLanguage.SYSTEM, AppLanguage.ZH, AppLanguage.EN).forEach { lang ->
                        val label = when (lang) {
                            AppLanguage.SYSTEM -> s.langSystem
                            AppLanguage.ZH -> s.langZh
                            AppLanguage.EN -> s.langEn
                        }
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                language = lang
                                showLangMenu = false
                                app.settingsStore.setLanguage(lang)
                                scope.launch { snackbar.showSnackbar(s.languageChanged) }
                            },
                            leadingIcon = {
                                if (lang == language) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        )
                    }
                }
            }

            // ── 3. 主题 ──
            SectionTitle(s.sectionTheme)
            Text(s.chooseColorScheme, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(AppTheme.entries.toList()) { theme ->
                    ThemeChip(
                        theme = theme,
                        selected = currentTheme == theme,
                        onClick = {
                            ThemeState.currentTheme = theme
                            currentTheme = theme
                        }
                    )
                }
            }
            TextButton(onClick = { wallpaperLauncher.launch("image/*") }) {
                Icon(Icons.Default.Wallpaper, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(s.chooseWallpaper, fontSize = 13.sp)
            }
            if (ThemeState.wallpaperPath != null) {
                Text(
                    "${s.wallpaperPrefix}${ThemeState.wallpaperPath!!.takeLast(30)}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── 4. Shizuku & 系统权限 ──
            SectionTitle(s.sectionPermissions)
            ShizukuStatusCard()
            BatteryOptimizationCard()

            // ── 5. Python & 终端 ──
            SectionTitle(s.sectionPython)
            PythonStatusCard()

            OutlinedButton(
                onClick = onOpenTerminal,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Terminal, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(s.openBuiltinTerminal, fontSize = 13.sp)
            }

            // ── 6. 关于 ──
            SectionTitle(s.sectionAbout)
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpenAbout() },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.aboutApp, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(s.aboutSubtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                }
            }

            Text(s.apiKeyEncrypted, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ThemeChip(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    val previewColor = when (theme) {
        AppTheme.AMOLED_BLACK -> Color(0xFF000000)
        AppTheme.PURE_WHITE -> Color(0xFFFFFFFF)
        AppTheme.CREAM -> Color(0xFFFAF8F5)
        AppTheme.MD3_BLUE -> Color(0xFF1A3A5C)
        AppTheme.MD3_GREEN -> Color(0xFFE8F5E9)
        AppTheme.MD3_PURPLE -> Color(0xFF2D1B4E)
        AppTheme.MD3_ORANGE -> Color(0xFFFFF3E0)
        AppTheme.MD3_MONO -> Color(0xFFEEEEEE)
        AppTheme.CUSTOM_WALLPAPER -> Color(0xFF607D8B)
        AppTheme.CUSTOM_WALLPAPER_DARK -> Color(0xFF263238)
        else -> Color(0xFF888888)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(previewColor)
                .then(
                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else Modifier.border(1.dp, Color.Gray.copy(alpha = 0.3f), CircleShape)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    null,
                    tint = if (theme.isDark) Color.White else Color.Black,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(theme.localizedLabel(), fontSize = 10.sp, maxLines = 1)
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
private fun ShizukuStatusCard() {
    val s = LocalAppStrings.current
    val status = ShizukuManager.status
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var requesting by remember { mutableStateOf(false) }

    data class StatusInfo(val text: String, val color: Color, val label: String?, val action: (() -> Unit)?)
    val statusInfo = when (status) {
        is ShizukuManager.Status.Authorized -> StatusInfo(
            s.shizukuAuthorized + if (status.uid > 0) " (UID: ${status.uid})" else "",
            MaterialTheme.colorScheme.primary, null, null
        )
        is ShizukuManager.Status.Running -> StatusInfo(
            s.shizukuRunning, MaterialTheme.colorScheme.tertiary, s.authorize,
            { requesting = true; scope.launch { ShizukuManager.requestPermission(); requesting = false } }
        )
        is ShizukuManager.Status.Installed -> StatusInfo(
            s.shizukuInstalled, MaterialTheme.colorScheme.error, s.authorize,
            { requesting = true; scope.launch { ShizukuManager.requestPermission(); requesting = false } }
        )
        else -> StatusInfo(
            s.shizukuMissing, MaterialTheme.colorScheme.error, s.install,
            { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }
        )
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Security, contentDescription = null, tint = statusInfo.color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(statusInfo.text, fontSize = 12.sp, color = statusInfo.color, modifier = Modifier.weight(1f))
            if (statusInfo.label != null && statusInfo.action != null) {
                Button(
                    onClick = { if (!requesting) statusInfo.action!!.invoke() },
                    enabled = !requesting,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    if (requesting) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Text(statusInfo.label!!, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimizationCard() {
    val s = LocalAppStrings.current
    val context = LocalContext.current
    val isIgnoring by remember { mutableStateOf(KeepAliveManager.isIgnoringBatteryOptimizations(context)) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isIgnoring)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.BatteryChargingFull, null,
                tint = if (isIgnoring) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(s.batteryWhitelist, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (isIgnoring) s.batteryExempted else s.batteryNotExempted,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isIgnoring) {
                TextButton(onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (_: Throwable) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: Throwable) {}
                    }
                }) {
                    Text(s.goToSettings, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PythonStatusCard() {
    val s = LocalAppStrings.current
    var checking by remember { mutableStateOf(true) }
    var available by remember { mutableStateOf(false) }
    var pythonPath by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf<String?>(null) }
    var customPath by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            available = com.apkagent.apktools.PythonRunner.isAvailable()
            pythonPath = com.apkagent.apktools.PythonRunner.findPython()
            version = com.apkagent.apktools.PythonRunner.getVersion()
            customPath = com.apkagent.apktools.PythonRunner.pythonPath ?: ""
            checking = false
        }
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Code, contentDescription = null,
                    tint = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                if (checking) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(s.checking, fontSize = 12.sp)
                } else if (available) {
                    Text(s.pythonReady, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(s.pythonMissing, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            if (!checking) {
                if (available && version != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(s.fmt(s.pathLabel, "path" to pythonPath), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s.fmt(s.versionLabel, "ver" to version), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customPath,
                    onValueChange = { customPath = it },
                    label = { Text(s.pythonPathManual, fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            com.apkagent.apktools.PythonRunner.pythonPath = customPath.ifBlank { null }
                            scope.launch(Dispatchers.IO) {
                                available = com.apkagent.apktools.PythonRunner.isAvailable()
                                pythonPath = com.apkagent.apktools.PythonRunner.findPython()
                                version = com.apkagent.apktools.PythonRunner.getVersion()
                            }
                        }) {
                            Icon(Icons.Filled.Refresh, s.detect, modifier = Modifier.size(18.dp))
                        }
                    }
                )
                Text(s.pythonTermuxHint, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
