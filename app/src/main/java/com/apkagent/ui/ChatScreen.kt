package com.apkagent.ui

import com.apkagent.i18n.LocalAppStrings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apkagent.ApkAgentApp
import com.apkagent.store.ExecutionMode
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenProjects: () -> Unit,
    vm: ChatViewModel = viewModel()
) {
    val s = LocalAppStrings.current
    val context = LocalContext.current
    val app = remember { context.applicationContext as ApkAgentApp }
    val focusManager = LocalFocusManager.current
    val activeProject by app.currentProject.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val isRunning by vm.isRunning.collectAsStateWithLifecycle()
    val isExporting by vm.isExporting.collectAsStateWithLifecycle()
    val openApkName by vm.openApkName.collectAsStateWithLifecycle()
    val historyList by vm.historyList.collectAsStateWithLifecycle()
    val pendingConfirmation by vm.pendingConfirmation.collectAsStateWithLifecycle()
    val executionMode by vm.executionMode.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(activeProject?.id) {
        vm.selectProject(activeProject)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val apkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".apk") ?: "imported-apk"
                    val tmp = java.io.File(context.cacheDir, "temp_import_${System.currentTimeMillis()}.apk")
                    context.contentResolver.openInputStream(uri)?.use { src ->
                        tmp.outputStream().use { dst -> src.copyTo(dst) }
                    }
                    app.importApk(tmp, name)
                    tmp.delete()
                    vm.selectProject(app.currentProject.value)
                    snackbarHostState.showSnackbar(s.fmt(s.importedAnalyzed, "name" to name))
                } catch (e: Throwable) {
                    snackbarHostState.showSnackbar(s.fmt(s.importFailedShort, "msg" to e.message?.take(80)))
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                activeProject?.name ?: "APKAgent Console",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isRunning) MaterialTheme.colorScheme.primaryContainer else Color(0xFF10B981).copy(alpha = 0.15f)
                            ) {
                                Text(
                                    if (isRunning) "BUSY" else "READY",
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRunning) MaterialTheme.colorScheme.primary else Color(0xFF10B981)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (executionMode == ExecutionMode.FAST) Color(0xFFF59E0B).copy(alpha = 0.18f) else Color(0xFF3B82F6).copy(alpha = 0.18f),
                                modifier = Modifier.clickable { vm.toggleExecutionMode() }
                            ) {
                                Text(
                                    if (executionMode == ExecutionMode.FAST) "⚡ 快速" else "🛡️ 安全",
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (executionMode == ExecutionMode.FAST) Color(0xFFF59E0B) else Color(0xFF60A5FA)
                                )
                            }
                        }
                        Text(
                            openApkName?.let { s.fmt(s.importedPrefix, "name" to it) } ?: s.androidReverseAnalysis,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenProjects) {
                        Icon(Icons.Default.Apps, s.projects, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenEditor) {
                        Icon(Icons.Default.Code, s.projectFiles, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(
                        onClick = {
                            vm.exportPatchedApk { ok, msg ->
                                coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        },
                        enabled = !isExporting
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(Icons.Default.FileDownload, s.export, tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, s.more, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(s.history) },
                            onClick = { showMenu = false; showHistory = true; vm.loadHistoryList() },
                            leadingIcon = { Icon(Icons.Default.History, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(s.settings) },
                            onClick = { showMenu = false; onOpenSettings() },
                            leadingIcon = { Icon(Icons.Default.Settings, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(if (executionMode == ExecutionMode.FAST) "切换为安全模式 (每次确认)" else "切换为快速模式 (自动放行)") },
                            onClick = { showMenu = false; vm.toggleExecutionMode() },
                            leadingIcon = { Icon(if (executionMode == ExecutionMode.FAST) Icons.Default.Security else Icons.Default.Bolt, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(s.clearChat) },
                            onClick = { showMenu = false; vm.clearChat() },
                            leadingIcon = { Icon(Icons.Default.CleaningServices, null) }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            WorkbenchCockpitInput(
                text = input,
                running = isRunning,
                onTextChange = { input = it },
                onSend = {
                    if (input.isNotBlank()) {
                        vm.send(input)
                        input = ""
                        focusManager.clearFocus()
                    }
                },
                onStop = { vm.stop() },
                onQuickPrompt = { prompt ->
                    input = prompt
                }
            )
        }
    ) { padding ->
        if (messages.isEmpty()) {
            StudioDashboard(
                padding = padding,
                onQuickPrompt = { prompt ->
                    vm.send(prompt)
                }
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { item ->
                    EngineeringMessageRow(item)
                }
            }
        }
    }

    pendingConfirmation?.let { req ->
        PermissionDialog(
            call = req.call,
            onResult = { allow ->
                if (allow) vm.approvePendingToolCall() else vm.denyPendingToolCall()
            },
            onAllowAndSwitchFast = {
                vm.approveAndSwitchToFastMode()
            }
        )
    }

    if (showHistory) {
        HistorySheet(
            historyList = historyList,
            onDismiss = { showHistory = false },
            onSelect = { item ->
                showHistory = false
                vm.loadHistory(item.id)
            }
        )
    }
}

/** 逆向控制台单条消息渲染 */
@Composable
private fun EngineeringMessageRow(item: ChatItem) {
    val timeStr = remember(item.id) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    when (item.role) {
        Role.USER -> DirectiveBlock(item, timeStr)
        Role.ASSISTANT -> AgentExecutionBlock(item, timeStr)
        Role.TOOL -> ToolCard(item)
        Role.ERROR -> ErrorBanner(item)
        Role.SYSTEM -> SystemLogPill(item)
        Role.DEBUG -> DebugTraceLine(item)
    }
}

/** 用户指令块 (User Directive) */
@Composable
private fun DirectiveBlock(item: ChatItem, time: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "❯ DIRECTIVE",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    time,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                item.content,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** AI 逆向响应输出块 (Agent Output) */
@Composable
private fun AgentExecutionBlock(item: ChatItem, time: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    modifier = Modifier.size(20.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.SmartToy,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "APKAGENT CORE",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    time,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(8.dp))

            if (!item.reasoningContent.isNullOrBlank() || item.isThinking) {
                com.apkagent.ui.components.NeuralTrace(
                    reasoningText = item.reasoningContent.orEmpty(),
                    isThinking = item.isThinking,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (item.content.isNotBlank()) {
                MarkdownText(markdown = item.content)
            }

            if (item.streaming && !item.isThinking) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        modifier = Modifier.width(60.dp).height(2.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "ANALYZING...",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(item: ChatItem) {
    Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                item.content,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SystemLogPill(item: ChatItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        TechBadge(
            text = item.content,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DebugTraceLine(item: ChatItem) {
    Text(
        "> ${item.content}",
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

/** 逆向控制台驾驶舱输入栏 */
@Composable
private fun WorkbenchCockpitInput(
    text: String,
    running: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onQuickPrompt: (String) -> Unit
) {
    val s = LocalAppStrings.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // 快速建议指令轮播条
            val quickChips = listOf(
                "🔍 风险扫描" to "扫描该 APK 的安全风险并输出检测报告",
                "⚡ 反编译 DEX" to "反编译 classes.dex 并分析核心业务入口",
                "🛡️ 绕过签名校验" to "分析该 APK 的签名自校验机制并提供 patch 方案",
                "🪝 生成 Frida Hook" to "针对该应用的敏感 API 生成通用的 Frida Hook 脚本",
                "📦 导出重打包" to "检查当前工作区的补丁并打包签名输出 APK"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                quickChips.forEach { (label, prompt) ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                        modifier = Modifier.clickable { onQuickPrompt(prompt) }
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 输入区域
            Row(verticalAlignment = Alignment.Bottom) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "❯",
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.width(6.dp))
                        TextField(
                            value = text,
                            onValueChange = onTextChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 42.dp, max = 120.dp),
                            placeholder = {
                                Text(s.inputPlaceholder, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.primary
                            ),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend() }),
                            enabled = !running
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                if (running) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFEF4444))
                    ) {
                        Icon(Icons.Default.Stop, s.stop, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                } else {
                    IconButton(
                        onClick = onSend,
                        enabled = text.isNotBlank(),
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            s.send,
                            tint = if (text.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 逆向控制台欢迎仪表盘 */
@Composable
private fun StudioDashboard(padding: PaddingValues, onQuickPrompt: (String) -> Unit) {
    val s = LocalAppStrings.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(68.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Terminal,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "APKAgent Studio",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                s.welcomeSubtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            val missionCards = listOf(
                Triple("🛡️ 静态安全扫描", "提取危险权限、组件导出风险与敏感字符串", "扫描该 APK 的完整静态安全风险"),
                Triple("⚡ DEX / Smali 逆向", "反编译核心业务类，分析逻辑与数据流", "反编译 DEX 并列出主要业务包结构"),
                Triple("🪝 自动化 Hook 方案", "快速生成针对证书、Root 与调用的 Frida 脚本", "为该应用生成 Frida Hook 脚本与抓包方案"),
                Triple("🛠️ 智能 Patch 与重打包", "自动绕过自校验并重签名导出 APK", "分析该 APK 的签名保护并生成免校验补丁")
            )

            missionCards.forEach { (title, desc, prompt) ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onQuickPrompt(prompt) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(
                            Icons.Default.ArrowForward,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 历史记录弹窗 */
@Composable
private fun HistorySheet(
    historyList: List<HistoryItem>,
    onDismiss: () -> Unit,
    onSelect: (HistoryItem) -> Unit
) {
    val s = LocalAppStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(s.history, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        text = {
            if (historyList.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(s.noHistoryYet, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(historyList, key = { it.id }) { item ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(item) }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.userInput.ifBlank { item.apkName },
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                    Text(
                                        "${item.apkName} · ${item.messageCount} msgs",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Default.ArrowForward,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(s.close) }
        }
    )
}
