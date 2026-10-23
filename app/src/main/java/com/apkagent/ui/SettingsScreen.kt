package com.apkagent.ui

import android.content.Intent
import android.net.Uri
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
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.apkagent.shizuku.ShizukuManager
import com.apkagent.service.KeepAliveManager
import com.apkagent.store.AgentConfig
import com.apkagent.store.AiProvider
import com.apkagent.i18n.AppLanguage
import com.apkagent.i18n.LocalAppStrings
import com.apkagent.i18n.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenAbout: () -> Unit = {}, onOpenTerminal: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as ApkAgentApp
    val s = LocalAppStrings.current
    var language by remember { mutableStateOf(app.settingsStore.getLanguage()) }
    var showLangMenu by remember { mutableStateOf(false) }
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

    // 动态模型列表状态
    var dynamicModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var modelFetchError by remember { mutableStateOf<String?>(null) }

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
            } catch (e: Throwable) {
                scope.launch { snackbar.showSnackbar(s.wallpaperLoadFailed) }
            }
        }
    }

    // 获取可用模型列表
    fun fetchModels() {
        if (apiKey.isBlank()) {
            scope.launch { snackbar.showSnackbar(s.enterApiKeyFirst) }
            return
        }
        isLoadingModels = true
        modelFetchError = null
        scope.launch(Dispatchers.IO) {
            val baseUrl = if (selectedProvider.id == "custom") customBaseUrl else selectedProvider.baseUrl
            val client = OpenAIClient(baseUrl, apiKey)
            val result = client.fetchAvailableModels()
            result.onSuccess { models ->
                dynamicModels = models
                if (models.isNotEmpty()) {
                    // 如果当前模型不在列表中，自动选择第一个
                    if (model !in models) {
                        model = models.first()
                    }
                    snackbar.showSnackbar(s.fmt(s.modelsFetched, "n" to models.size))
                } else {
                    snackbar.showSnackbar(s.noModels)
                }
                isLoadingModels = false
            }.onFailure { e ->
                modelFetchError = e.message
                snackbar.showSnackbar(s.fmt(s.fetchFailed, "msg" to e.message?.take(50)))
                isLoadingModels = false
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(s.settings) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, s.back) } }
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

            // ── Language ──
            SectionTitle(s.sectionLanguage)
            Text(s.languageHint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ExposedDropdownMenuBox(expanded = showLangMenu, onExpandedChange = { showLangMenu = it }) {
                val langLabel = when (language) {
                    AppLanguage.SYSTEM -> s.langSystem
                    AppLanguage.ZH -> s.langZh
                    AppLanguage.EN -> s.langEn
                }
                OutlinedTextField(
                    value = langLabel, onValueChange = {}, readOnly = true,
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

            // ── 主题 ──
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
            // 壁纸按钮
            TextButton(onClick = { wallpaperLauncher.launch("image/*") }) {
                Icon(Icons.Default.Wallpaper, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(s.chooseWallpaper, fontSize = 13.sp)
            }
            if (ThemeState.wallpaperPath != null) {
                Text("${s.wallpaperPrefix}${ThemeState.wallpaperPath!!.takeLast(30)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── Shizuku ──
            SectionTitle(s.sectionPermissions)
            ShizukuStatusCard()
            BatteryOptimizationCard()

            // ── Python ──
            SectionTitle(s.sectionPython)
            PythonStatusCard()

            // 内置终端入口
            OutlinedButton(
                onClick = onOpenTerminal,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Terminal, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(s.openBuiltinTerminal, fontSize = 13.sp)
            }

            // ── AI Provider ──
            SectionTitle(s.sectionAi)
            Text(s.aiHint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            ExposedDropdownMenuBox(expanded = showProviderMenu, onExpandedChange = { showProviderMenu = it }) {
                OutlinedTextField(
                    value = selectedProvider.label, onValueChange = {}, readOnly = true,
                    label = { Text(s.aiProvider) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showProviderMenu) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = showProviderMenu, onDismissRequest = { showProviderMenu = false }) {
                    AiProvider.PRESETS.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.label) },
                            onClick = {
                                selectedProviderId = p.id; showProviderMenu = false
                                if (p.id != "custom") { model = p.defaultModel; customBaseUrl = ""; customModel = "" }
                                dynamicModels = emptyList() // 切换提供商时清空动态列表
                            },
                            leadingIcon = {
                                if (p.id == selectedProviderId) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        )
                    }
                }
            }

            // API Key 输入
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") },
                placeholder = { Text(s.pasteApiKey) }, singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) s.hide else s.show, fontSize = 12.sp) } })

            // 自动获取模型按钮
            Button(
                onClick = { fetchModels() },
                enabled = apiKey.isNotBlank() && !isLoadingModels,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoadingModels) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(s.fetching)
                } else {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s.fetchModels)
                }
            }

            // 模型选择下拉框
            val allModels = if (dynamicModels.isNotEmpty()) dynamicModels else selectedProvider.models
            if (allModels.isNotEmpty()) {
                var modelExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = it }) {
                    OutlinedTextField(
                        value = model, onValueChange = {}, readOnly = true,
                        label = { Text(s.model) },
                        supportingText = { if (dynamicModels.isNotEmpty()) Text(s.fmt(s.modelsFetchedHint, "n" to dynamicModels.size), fontSize = 10.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                        allModels.forEach { m ->
                            DropdownMenuItem(text = { Text(m) }, onClick = { model = m; modelExpanded = false },
                                leadingIcon = { if (m == model) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) })
                        }
                    }
                }
            } else if (selectedProvider.id == "custom") {
                // 自定义提供商：手动输入模型名
                OutlinedTextField(value = customModel, onValueChange = { customModel = it }, label = { Text(s.modelName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            // 错误提示
            if (modelFetchError != null) {
                Text("⚠️ $modelFetchError", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }

            Text(s.temperature.format(temp), fontSize = 13.sp)
            Slider(value = temp.toFloat(), onValueChange = { temp = it.toDouble() }, valueRange = 0f..2f, modifier = Modifier.fillMaxWidth())

            TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) s.collapseAdvanced else s.expandAdvanced, fontSize = 12.sp) }
            if (showAdvanced) {
                OutlinedTextField(value = if (selectedProvider.id == "custom") customBaseUrl else selectedProvider.baseUrl,
                    onValueChange = { if (selectedProvider.id == "custom") customBaseUrl = it },
                    label = { Text("API Base URL") }, enabled = selectedProvider.id == "custom", singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = sysExtra, onValueChange = { sysExtra = it }, label = { Text(s.systemExtra) }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
            }

            // ── 关于 ──
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

            Spacer(Modifier.padding(4.dp))
            Button(onClick = {
                val baseUrl = if (selectedProvider.id == "custom") customBaseUrl else selectedProvider.baseUrl
                val finalModel = if (selectedProvider.id == "custom") customModel else model
                val newCfg = AgentConfig(selectedProviderId, baseUrl, apiKey.trim(), finalModel.trim(), temp, sysExtra)
                app.settingsStore.save(newCfg)
                scope.launch { snackbar.showSnackbar(s.fmt(s.savedAs, "name" to selectedProvider.label)) }
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Check, null); Spacer(Modifier.padding(4.dp)); Text(s.save)
            }
            Text(s.apiKeyEncrypted, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { onClick() }) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(previewColor)
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier.border(1.dp, Color.Gray.copy(alpha = 0.3f), CircleShape)),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Icon(Icons.Default.Check, null, tint = if (theme.isDark) Color.White else Color.Black, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(theme.localizedLabel(), fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ShizukuStatusCard() {
    val s = LocalAppStrings.current
    val status = ShizukuManager.status
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var requesting by remember { mutableStateOf(false) }

    data class StatusInfo(val text: String, val color: androidx.compose.ui.graphics.Color, val label: String?, val action: (() -> Unit)?)
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

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Security, contentDescription = null, tint = statusInfo.color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(statusInfo.text, fontSize = 12.sp, color = statusInfo.color, modifier = Modifier.weight(1f))
            if (statusInfo.label != null && statusInfo.action != null) {
                Button(onClick = { if (!requesting) statusInfo.action!!.invoke() }, enabled = !requesting, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    if (requesting) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) else Text(statusInfo.label!!, fontSize = 12.sp)
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
                Text(
                    s.batteryWhitelist,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
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
                        // 部分设备不支持，打开电池优化列表
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
    val context = LocalContext.current
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

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp)) {
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
                // 手动配置路径
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
                Text(
                    s.pythonTermuxHint,
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
