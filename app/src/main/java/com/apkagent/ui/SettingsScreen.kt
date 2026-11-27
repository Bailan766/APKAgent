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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apkagent.ApkAgentApp
import com.apkagent.agent.OpenAIClient
import com.apkagent.i18n.AppLanguage
import com.apkagent.i18n.LocalAppStrings
import com.apkagent.service.KeepAliveManager
import com.apkagent.shizuku.ShizukuManager
import com.apkagent.store.AgentConfig
import com.apkagent.store.AiProvider
import com.apkagent.store.ExecutionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    var dynamicModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var modelFetchError by remember { mutableStateOf<String?>(null) }
    var verifySuccessMsg by remember { mutableStateOf<String?>(null) }

    var execMode by remember { mutableStateOf(app.settingsStore.getExecutionMode()) }
    var saveDebounceJob by remember { mutableStateOf<Job?>(null) }

    fun buildNormalizedConfig(
        pid: String = selectedProviderId,
        rawUrl: String = baseUrl,
        rawKey: String = apiKey,
        rawModel: String = model,
        temperature: Double = temp,
        extra: String = sysExtra,
        mode: ExecutionMode = execMode
    ): AgentConfig {
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
        return cfg.copy(
            providerId = pid,
            baseUrl = cleanUrl,
            apiKey = cleanKey,
            model = cleanModel,
            temperature = temperature,
            systemExtra = extra,
            executionMode = mode.id
        )
    }

    /** 立即在后台 IO 线程保存配置，避免阻塞主线程导致掉帧 */
    fun saveCurrentConfigAsync(
        pid: String = selectedProviderId,
        rawUrl: String = baseUrl,
        rawKey: String = apiKey,
        rawModel: String = model,
        temperature: Double = temp,
        extra: String = sysExtra,
        mode: ExecutionMode = execMode
    ) {
        saveDebounceJob?.cancel()
        val snapshot = buildNormalizedConfig(pid, rawUrl, rawKey, rawModel, temperature, extra, mode)
        scope.launch(Dispatchers.IO) {
            app.settingsStore.save(snapshot)
        }
    }

    /** 输入框防抖保存：用户停顿 350ms 后在 IO 线程加密写盘 */
    fun scheduleSaveConfig(
        pid: String = selectedProviderId,
        rawUrl: String = baseUrl,
        rawKey: String = apiKey,
        rawModel: String = model,
        temperature: Double = temp,
        extra: String = sysExtra,
        mode: ExecutionMode = execMode
    ) {
        saveDebounceJob?.cancel()
        val snapshot = buildNormalizedConfig(pid, rawUrl, rawKey, rawModel, temperature, extra, mode)
        saveDebounceJob = scope.launch(Dispatchers.IO) {
            delay(350)
            app.settingsStore.save(snapshot)
        }
    }

    // 离开设置页面时保障最后一次输入不丢失
    DisposableEffect(Unit) {
        onDispose {
            saveDebounceJob?.cancel()
            val snapshot = buildNormalizedConfig()
            CoroutineScope(Dispatchers.IO).launch {
                app.settingsStore.save(snapshot)
            }
        }
    }

    BackHandler {
        saveCurrentConfigAsync()
        onBack()
    }

    fun verifyAndFetchModels(autoTriggered: Boolean = false) {
        val cleanKey = OpenAIClient.sanitizeApiKey(apiKey)
        if (cleanKey.isBlank() && selectedProviderId != "ollama") {
            if (!autoTriggered) {
                scope.launch { snackbar.showSnackbar(s.enterApiKeyFirst) }
            }
            return
        }
        apiKey = cleanKey
        saveCurrentConfigAsync(rawKey = cleanKey)

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
                    saveCurrentConfigAsync(rawKey = cleanKey, rawModel = model)
                    verifySuccessMsg = "API Key 验证有效 (${selectedProvider.label} · ${models.size} 个模型)"
                    modelFetchError = null
                    isLoadingModels = false
                    if (!autoTriggered) {
                        snackbar.showSnackbar("✅ $verifySuccessMsg")
                    }
                }
            }.onFailure { err ->
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
                        saveCurrentConfigAsync(
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
            delay(450)
            saveCurrentConfigAsync(rawKey = clean)
            verifyAndFetchModels(autoTriggered = true)
        }
    }

    var currentTheme by remember { mutableStateOf(ThemeState.currentTheme) }
    var currentWallpaperPath by remember { mutableStateOf(ThemeState.wallpaperPath) }

    val wallpaperLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val dest = File(context.filesDir, "custom_wallpaper.img")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { out -> input.copyTo(out) }
                    }
                    ThemeState.loadWallpaper(dest.absolutePath)
                    app.settingsStore.setWallpaperPath(dest.absolutePath)
                    app.settingsStore.setThemeName(AppTheme.CUSTOM_WALLPAPER_DARK.name)
                    withContext(Dispatchers.Main) {
                        ThemeState.currentTheme = AppTheme.CUSTOM_WALLPAPER_DARK
                        currentTheme = AppTheme.CUSTOM_WALLPAPER_DARK
                        currentWallpaperPath = dest.absolutePath
                        snackbar.showSnackbar(s.wallpaperSet)
                    }
                } catch (_: Throwable) {
                    withContext(Dispatchers.Main) {
                        snackbar.showSnackbar(s.wallpaperLoadFailed)
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.padding(bottom = 84.dp)
            )
        },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(s.settings, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                            Text(
                                "模型配置、自动化模式与个性化",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = {
                            saveCurrentConfigAsync()
                            verifyAndFetchModels(autoTriggered = false)
                        },
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(s.save, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── 1. AI 模型与 API Key 配置（MD3 无边框暖灰分层卡片） ──
            SectionTitle(s.sectionAi)

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val isConfigured = cfg.isValid()
                    Surface(
                        color = if (isConfigured)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isConfigured) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isConfigured) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = if (isConfigured) {
                                    "当前已生效：${AiProvider.byId(cfg.providerId).label} · ${cfg.model}"
                                } else {
                                    "尚未填写 API Key — 粘贴后将自动识别并保存"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isConfigured) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    ExposedDropdownMenuBox(
                        expanded = showProviderMenu,
                        onExpandedChange = { showProviderMenu = it }
                    ) {
                        OutlinedTextField(
                            value = selectedProvider.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(s.aiProvider) },
                            shape = RoundedCornerShape(16.dp),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showProviderMenu) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
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
                                                Text(p.baseUrl, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                        saveCurrentConfigAsync(
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

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { newValue ->
                            apiKey = newValue
                            modelFetchError = null
                            scheduleSaveConfig(rawKey = newValue)
                        },
                        label = { Text("API Key") },
                        placeholder = { Text(s.pasteApiKey) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        val pasted = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                        if (pasted.isNotBlank()) {
                                            val clean = OpenAIClient.sanitizeApiKey(pasted)
                                            apiKey = clean
                                            saveCurrentConfigAsync(rawKey = clean)
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
                                            saveCurrentConfigAsync(rawKey = "")
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

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { newUrl ->
                            baseUrl = newUrl
                            scheduleSaveConfig(rawUrl = newUrl)
                        },
                        label = { Text("API Base URL (接口地址)") },
                        placeholder = { Text("https://api.deepseek.com/v1") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (selectedProvider.baseUrl.isNotBlank() && baseUrl != selectedProvider.baseUrl) {
                                TextButton(
                                    onClick = {
                                        baseUrl = selectedProvider.baseUrl
                                        saveCurrentConfigAsync(rawUrl = selectedProvider.baseUrl)
                                    }
                                ) {
                                    Text("默认", fontSize = 11.sp)
                                }
                            }
                        }
                    )

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
                                scheduleSaveConfig(rawModel = newModel)
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            label = { Text("${s.model} (可下拉选择或直接输入)") },
                            trailingIcon = {
                                if (candidateModels.isNotEmpty()) {
                                    IconButton(onClick = { modelExpanded = !modelExpanded }) {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryEditable)
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
                                            saveCurrentConfigAsync(rawModel = m)
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

                    if (candidateModels.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(candidateModels.take(8)) { m ->
                                val selectedModel = model == m
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (selectedModel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable {
                                            model = m
                                            saveCurrentConfigAsync(rawModel = m)
                                        }
                                ) {
                                    Text(
                                        text = m,
                                        fontSize = 11.sp,
                                        fontWeight = if (selectedModel) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selectedModel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { verifyAndFetchModels(autoTriggered = false) },
                            enabled = (apiKey.isNotBlank() || selectedProviderId == "ollama") && !isLoadingModels,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(50)
                        ) {
                            if (isLoadingModels) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(s.fetching, fontSize = 12.sp)
                            } else {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("验证 / 拉取模型", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Button(
                            onClick = {
                                saveCurrentConfigAsync()
                                scope.launch {
                                    snackbar.showSnackbar(
                                        s.fmt(s.savedAs, "name" to "${selectedProvider.label} (${model.ifBlank { selectedProvider.defaultModel }})")
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(50)
                        ) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.save, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

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
                            onValueChange = { temp = it.toDouble() },
                            onValueChangeFinished = { saveCurrentConfigAsync(temperature = temp) },
                            valueRange = 0f..2f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = sysExtra,
                            onValueChange = {
                                sysExtra = it
                                scheduleSaveConfig(extra = it)
                            },
                            label = { Text(s.systemExtra) },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
                        )
                    }
                }
            }

            // ── 1.5 自动化运行模式 / Execution Mode (MD3 无边框暖粉层级) ──
            SectionTitle(s.executionModeLabel)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.executionModeLabel, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.width(8.dp))
                                TechBadge(
                                    text = if (execMode == ExecutionMode.FAST) "默认 · 快速" else "安全拦截",
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                "控制自动化逆向时的敏感操作策略。默认采用快速模式，全自动放行并留存审计快照。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (execMode == ExecutionMode.FAST) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                execMode = ExecutionMode.FAST
                                saveCurrentConfigAsync(mode = ExecutionMode.FAST)
                            }
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = execMode == ExecutionMode.FAST,
                                onClick = {
                                    execMode = ExecutionMode.FAST
                                    saveCurrentConfigAsync(mode = ExecutionMode.FAST)
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        s.modeFast,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = if (execMode == ExecutionMode.FAST) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    TechBadge(
                                        text = "默认",
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    s.modeFastDesc,
                                    fontSize = 11.sp,
                                    color = if (execMode == ExecutionMode.FAST) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (execMode == ExecutionMode.SAFE) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                execMode = ExecutionMode.SAFE
                                saveCurrentConfigAsync(mode = ExecutionMode.SAFE)
                            }
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = execMode == ExecutionMode.SAFE,
                                onClick = {
                                    execMode = ExecutionMode.SAFE
                                    saveCurrentConfigAsync(mode = ExecutionMode.SAFE)
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.modeSafe,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (execMode == ExecutionMode.SAFE) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    s.modeSafeDesc,
                                    fontSize = 11.sp,
                                    color = if (execMode == ExecutionMode.SAFE) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ── 2. 语言 / Language ──
            SectionTitle(s.sectionLanguage)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.languageHint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            shape = RoundedCornerShape(16.dp),
                            label = { Text(s.languageLabel) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showLangMenu) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
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
                }
            }

            // ── 3. 主题与壁纸持久化 ──
            SectionTitle(s.sectionTheme)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.chooseColorScheme, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(AppTheme.entries.toList()) { theme ->
                            ThemeChip(
                                theme = theme,
                                selected = currentTheme == theme,
                                onClick = {
                                    ThemeState.currentTheme = theme
                                    currentTheme = theme
                                    app.settingsStore.setThemeName(theme.name)
                                }
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { wallpaperLauncher.launch("image/*") },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Wallpaper, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.chooseWallpaper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (currentWallpaperPath != null) {
                            TextButton(
                                onClick = {
                                    ThemeState.wallpaperBitmap = null
                                    ThemeState.wallpaperPath = null
                                    currentWallpaperPath = null
                                    app.settingsStore.setWallpaperPath(null)
                                    if (currentTheme.hasWallpaper) {
                                        ThemeState.currentTheme = AppTheme.AMOLED_BLACK
                                        currentTheme = AppTheme.AMOLED_BLACK
                                        app.settingsStore.setThemeName(AppTheme.AMOLED_BLACK.name)
                                    }
                                },
                                shape = RoundedCornerShape(50)
                            ) {
                                Text("清除壁纸", fontSize = 12.sp)
                            }
                        }
                    }
                    if (currentWallpaperPath != null) {
                        Text(
                            "${s.wallpaperPrefix}${currentWallpaperPath!!.takeLast(30)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── 4. Shizuku & 系统权限 ──
            SectionTitle(s.sectionPermissions)
            ShizukuStatusCard()
            BatteryOptimizationCard()

            // ── 5. Python & 终端 ──
            SectionTitle(s.sectionPython)
            PythonStatusCard()

            FilledTonalButton(
                onClick = onOpenTerminal,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                Icon(Icons.Default.Terminal, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(s.openBuiltinTerminal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            // ── 6. 关于 ──
            SectionTitle(s.sectionAbout)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { onOpenAbout() },
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.aboutApp, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(s.aboutSubtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text(s.apiKeyEncrypted, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(92.dp))
        }
    }
}

@Composable
private fun ThemeChip(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    val brush: Brush? = if (theme == AppTheme.AMOLED_BLACK) M3RoseGradient else null
    val previewColor = when (theme) {
        AppTheme.AMOLED_BLACK -> M3RoseAccent
        AppTheme.PURE_WHITE -> Color(0xFFFFFFFF)
        AppTheme.CREAM -> Color(0xFFFAF8F5)
        AppTheme.MD3_BLUE -> Color(0xFF1A3A5C)
        AppTheme.MD3_GREEN -> Color(0xFF10B981)
        AppTheme.MD3_PURPLE -> Color(0xFF7C3AED)
        AppTheme.MD3_ORANGE -> Color(0xFFF59E0B)
        AppTheme.MD3_MONO -> Color(0xFF94A3B8)
        AppTheme.CUSTOM_WALLPAPER -> Color(0xFFF3B8C8)
        AppTheme.CUSTOM_WALLPAPER_DARK -> Color(0xFF491B2A)
        else -> M3RoseAccent
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .then(
                    if (brush != null) Modifier.background(brush)
                    else Modifier.background(previewColor)
                )
                .then(
                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    null,
                    tint = if (theme == AppTheme.AMOLED_BLACK) M3RoseHeroTitleColor else if (theme.isDark) Color.White else Color.Black,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            theme.localizedLabel(),
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
    )
}

@Composable
private fun ShizukuStatusCard() {
    val s = LocalAppStrings.current
    val status = ShizukuManager.status
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var requesting by remember { mutableStateOf(false) }

    data class StatusInfo(val text: String, val color: Color, val container: Color, val label: String?, val action: (() -> Unit)?)
    val statusInfo = when (status) {
        is ShizukuManager.Status.Authorized -> StatusInfo(
            s.shizukuAuthorized + if (status.uid > 0) " (UID: ${status.uid})" else "",
            MaterialTheme.colorScheme.onPrimaryContainer,
            MaterialTheme.colorScheme.primaryContainer,
            null,
            null
        )
        is ShizukuManager.Status.Running -> StatusInfo(
            s.shizukuRunning,
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.surfaceVariant,
            s.authorize,
            { requesting = true; scope.launch { ShizukuManager.requestPermission(); requesting = false } }
        )
        is ShizukuManager.Status.Installed -> StatusInfo(
            s.shizukuInstalled,
            MaterialTheme.colorScheme.onErrorContainer,
            MaterialTheme.colorScheme.errorContainer,
            s.authorize,
            { requesting = true; scope.launch { ShizukuManager.requestPermission(); requesting = false } }
        )
        else -> StatusInfo(
            s.shizukuMissing,
            MaterialTheme.colorScheme.onErrorContainer,
            MaterialTheme.colorScheme.errorContainer,
            s.install,
            { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(statusInfo.container),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = statusInfo.color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(statusInfo.text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (statusInfo.label != null && statusInfo.action != null) {
                FilledTonalButton(
                    onClick = { if (!requesting) statusInfo.action!!.invoke() },
                    enabled = !requesting,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    if (requesting) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Text(statusInfo.label!!, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimizationCard() {
    val s = LocalAppStrings.current
    val context = LocalContext.current
    var isIgnoring by remember { mutableStateOf(KeepAliveManager.isIgnoringBatteryOptimizations(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoring = KeepAliveManager.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (isIgnoring) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.BatteryChargingFull, null,
                    tint = if (isIgnoring) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(s.batteryWhitelist, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (isIgnoring) s.batteryExempted else s.batteryNotExempted,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isIgnoring) {
                FilledTonalButton(
                    shape = RoundedCornerShape(50),
                    onClick = {
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
                    }
                ) {
                    Text(s.goToSettings, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
        withContext(Dispatchers.IO) {
            available = com.apkagent.apktools.PythonRunner.isAvailable()
            pythonPath = com.apkagent.apktools.PythonRunner.findPython()
            version = com.apkagent.apktools.PythonRunner.getVersion()
            customPath = com.apkagent.apktools.PythonRunner.pythonPath ?: ""
            checking = false
        }
    }

    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Code, contentDescription = null,
                        tint = if (available) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (checking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(s.checking, fontSize = 13.sp)
                } else if (available) {
                    Text(s.pythonReady, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(s.pythonMissing, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
            }
            if (!checking) {
                if (available && version != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(s.fmt(s.pathLabel, "path" to pythonPath), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s.fmt(s.versionLabel, "ver" to version), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = customPath,
                    onValueChange = { customPath = it },
                    label = { Text(s.pythonPathManual, fontSize = 11.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
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
                Spacer(Modifier.height(4.dp))
                Text(s.pythonTermuxHint, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
