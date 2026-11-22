package com.apkagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apkagent.ApkAgentApp
import com.apkagent.i18n.LocalAppStrings
import com.apkagent.store.ExecutionMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
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
    var input by rememberSaveable { mutableStateOf("") }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showToolbox by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(activeProject?.id) {
        vm.selectProject(activeProject)
    }

    // 仅当用户本来就处于底部时才跟随流式增量滚动，上滑阅读历史时不强行抢回底部；
    // 流式增量采用无动画 scrollToItem + 大偏移量，防止与 animateScrollToItem 冲突导致跳顶。
    val isAtBottom by remember {
        derivedStateOf { !listState.canScrollForward }
    }
    var lastMsgCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (messages.size > lastMsgCount) {
                listState.animateScrollToItem(messages.size - 1, scrollOffset = 100_000)
            }
            lastMsgCount = messages.size
        } else {
            lastMsgCount = 0
        }
    }

    val lastContentLen = messages.lastOrNull()?.let {
        it.content.length + (it.reasoningContent?.length ?: 0)
    } ?: 0
    LaunchedEffect(lastContentLen) {
        if (messages.isNotEmpty() && isAtBottom) {
            listState.scrollToItem(messages.size - 1, scrollOffset = 100_000)
        }
    }

    val doExport: () -> Unit = {
        vm.exportPatchedApk { _, msg ->
            coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
        }
    }

    val apkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".apk") ?: "imported-apk"
                    val tmp = File(context.cacheDir, "temp_import_${System.currentTimeMillis()}.apk")
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
                                text = activeProject?.name ?: "APKAgent Studio",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isRunning) MaterialTheme.colorScheme.primaryContainer else Color(0xFF10B981).copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = if (isRunning) "BUSY" else "READY",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRunning) MaterialTheme.colorScheme.primary else Color(0xFF10B981)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (executionMode == ExecutionMode.FAST) {
                                    Color(0xFFF59E0B).copy(alpha = 0.18f)
                                } else {
                                    Color(0xFF3B82F6).copy(alpha = 0.18f)
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { vm.toggleExecutionMode() }
                            ) {
                                Text(
                                    text = if (executionMode == ExecutionMode.FAST) "⚡ 快速" else "🛡️ 安全",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (executionMode == ExecutionMode.FAST) Color(0xFFF59E0B) else Color(0xFF60A5FA)
                                )
                            }
                        }
                        Text(
                            text = openApkName?.let { s.fmt(s.importedPrefix, "name" to it) } ?: s.androidReverseAnalysis,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
                        onClick = doExport,
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
                            text = { Text("🧰 逆向一键工具箱") },
                            onClick = { showMenu = false; showToolbox = true },
                            leadingIcon = { Icon(Icons.Default.Build, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(s.importApk) },
                            onClick = {
                                showMenu = false
                                apkLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                            },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) }
                        )
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
                onOpenToolbox = { showToolbox = true },
                onQuickPrompt = { prompt ->
                    if (!isRunning) {
                        vm.send(prompt)
                        focusManager.clearFocus()
                    }
                }
            )
        }
    ) { padding ->
        if (messages.isEmpty()) {
            StudioDashboard(
                padding = padding,
                onQuickPrompt = { prompt ->
                    vm.send(prompt)
                },
                onOpenToolbox = { showToolbox = true }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages, key = { it.id }) { item ->
                        EngineeringMessageRow(
                            item = item,
                            onQuickExport = doExport
                        )
                    }
                }

                // 上滑浏览历史时提供 Material 3 回到底部悬浮按钮
                AnimatedVisibility(
                    visible = listState.canScrollForward && messages.isNotEmpty(),
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                if (messages.isNotEmpty()) {
                                    listState.animateScrollToItem(messages.size - 1, scrollOffset = 100_000)
                                }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "回到底部", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("回到底部", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    if (showToolbox) {
        ReverseToolboxSheet(
            running = isRunning,
            onDismiss = { showToolbox = false },
            onSelectPrompt = { prompt ->
                showToolbox = false
                if (!isRunning) {
                    vm.send(prompt)
                    focusManager.clearFocus()
                }
            },
            onExportApk = {
                showToolbox = false
                doExport()
            }
        )
    }

    pendingConfirmation?.let { req ->
        key(req.call.id) {
            PermissionDialog(
                req = req,
                onResult = { allow ->
                    if (allow) vm.approvePendingToolCall() else vm.denyPendingToolCall()
                },
                onAllowAndSwitchFast = {
                    vm.approveAndSwitchToFastMode()
                }
            )
        }
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
private fun EngineeringMessageRow(
    item: ChatItem,
    onQuickExport: () -> Unit
) {
    val timeStr = remember(item.id) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    when (item.role) {
        Role.USER -> DirectiveBlock(item, timeStr)
        Role.ASSISTANT -> AgentExecutionBlock(item, timeStr)
        Role.TOOL -> ToolCard(item, onQuickExport = onQuickExport)
        Role.ERROR -> ErrorBanner(item)
        Role.SYSTEM -> SystemLogPill(item)
        Role.DEBUG -> DebugTraceLine(item)
    }
}

/** 用户指令卡片（Material 3 对话气泡层次 + 复制按钮） */
@Composable
private fun DirectiveBlock(item: ChatItem, time: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "我的指令",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = time,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = "复制指令",
                    tint = if (copied) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(15.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(item.content))
                            copied = true
                        }
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.content,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** AI 逆向响应输出卡片（Material 3 + 一键复制全文） */
@Composable
private fun AgentExecutionBlock(item: ChatItem, time: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    modifier = Modifier.size(22.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.SmartToy,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "APKAgent",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = time,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                if (item.content.isNotBlank() && !item.streaming) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "复制结论",
                        tint = if (copied) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .size(15.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                clipboard.setText(AnnotatedString(item.content))
                                copied = true
                            }
                    )
                }
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
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        modifier = Modifier.width(64.dp).height(2.5.dp).clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "正在分析生成结论...",
                        fontSize = 11.sp,
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
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                item.content,
                fontSize = 12.sp,
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
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

/** 逆向控制台底部驾驶舱输入栏（快捷指令一键直发 + 逆向工具箱入口） */
@Composable
private fun WorkbenchCockpitInput(
    text: String,
    running: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenToolbox: () -> Unit,
    onQuickPrompt: (String) -> Unit
) {
    val s = LocalAppStrings.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            val quickChips = listOf(
                "🔍 风险扫描" to "扫描该 APK 的完整安全风险并输出中文检测报告",
                "⚡ 反编译 DEX" to "反编译 classes.dex 并分析核心业务入口与主要包结构",
                "🛡️ 去签名校验" to "分析该 APK 的签名自校验机制并自动应用 bypass patch",
                "🪝 Frida Hook" to "针对该应用的 SSL Pinning 与敏感 API 生成通用 Frida Hook 脚本",
                "📦 重打包签名" to "检查当前工作区的修改补丁并重新打包与签名输出 APK"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 突出显示的「一键工具箱」触发胶囊
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenToolbox() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "工具箱",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                quickChips.forEach { (label, prompt) ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !running) { onQuickPrompt(prompt) }
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (running) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
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
                                .heightIn(min = 44.dp, max = 120.dp),
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
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank() && !running) onSend() }),
                            enabled = !running
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                if (running) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Stop, s.stop, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                } else {
                    IconButton(
                        onClick = onSend,
                        enabled = text.isNotBlank(),
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
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

private data class ToolboxAction(
    val title: String,
    val subtitle: String,
    val prompt: String
)

private data class ToolboxSection(
    val title: String,
    val icon: ImageVector,
    val accent: Color,
    val actions: List<ToolboxAction>
)

/** Material 3 底部逆向一键工具箱抽屉 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReverseToolboxSheet(
    running: Boolean,
    onDismiss: () -> Unit,
    onSelectPrompt: (String) -> Unit,
    onExportApk: () -> Unit
) {
    val sections = remember {
        listOf(
            ToolboxSection(
                title = "静态安全与情报审计",
                icon = Icons.Default.Security,
                accent = Color(0xFF10B981),
                actions = listOf(
                    ToolboxAction("全面静态风险扫描", "扫描危险权限、敏感组件导出与明文隐患", "对当前 APK 进行全面静态安全风险扫描并输出总结报告"),
                    ToolboxAction("Manifest 组件深度审计", "提取四大组件、Intent-Filter 与 DeepLink 入口", "解析 AndroidManifest.xml 并列出导出组件、DeepLink 入口与风险点"),
                    ToolboxAction("敏感硬编码密钥与 URL 提取", "扫描 APK/DEX 中的 Key、Token、内网接口与域名", "使用脚本扫描提取当前 APK 中的硬编码密钥、API 域名与敏感字符串")
                )
            ),
            ToolboxSection(
                title = "DEX / Smali 逆向分析",
                icon = Icons.Default.Code,
                accent = Color(0xFF38BDF8),
                actions = listOf(
                    ToolboxAction("核心包结构与入口分析", "反编译 DEX 主入口、Application 与首页流程", "分析 classes.dex 包结构、Application 入口以及主界面逻辑"),
                    ToolboxAction("加壳 / 混淆与架构识别", "检测加固厂商、Native SO 保护及开发框架", "检测当前 APK 是否加壳混淆、包含的 Native 库与开发技术栈"),
                    ToolboxAction("会员/付费授权逻辑定位", "检索 VIP、isPro、License、Verify 相关关键类与方法", "在 DEX 中检索定位 VIP/会员/付费授权校验核心类与方法并反编译分析")
                )
            ),
            ToolboxSection(
                title = "一键补丁与防护绕过",
                icon = Icons.Default.AutoFixHigh,
                accent = Color(0xFFF59E0B),
                actions = listOf(
                    ToolboxAction("绕过签名自校验", "定位 PackageInfo 签名校验代码并应用智能补丁", "分析该 APK 的签名自校验位置并自动应用绕过补丁"),
                    ToolboxAction("去除开屏广告 / 强制更新弹窗", "定位 Splash 倒计时、广告 SDK 与升级 Dialog 调用链", "分析开屏广告和强制更新弹窗触发逻辑，并提供去除或跳过方案"),
                    ToolboxAction("解除 Root / Frida / 调试检测", "查找反调试、su 文件与端口检测逻辑", "检索当前应用中的 Root、Xposed、Frida 和 Ptrace 反调试检测逻辑并给出绕过方案")
                )
            ),
            ToolboxSection(
                title = "脚本实战与打包输出",
                icon = Icons.Default.Build,
                accent = Color(0xFFA855F7),
                actions = listOf(
                    ToolboxAction("生成 Frida SSL 绕过抓包脚本", "生成 TrustManager / OkHttp / BoringSSL Hook 脚本", "针对当前 APK 生成通用且可直接运行的 Frida SSL Pinning 绕过抓包脚本"),
                    ToolboxAction("内置 Python 批量分析 DEX", "调用内置 Kotlin Python 3.12 引擎扫描类与符号", "使用 Python 分析脚本统计当前 APK 所有 DEX 类名、权限和可疑 API"),
                    ToolboxAction("工作区补丁打包与签名", "检验已修改 Smali/资源并重打包生成签名 APK", "检查工作区当前修改并执行重打包、ZipAlign 对齐与 V1/V2 签名")
                )
            )
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Build, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("逆向一键工具箱", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("点击任意项目直接派发给 Agent 全自动执行", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(
                    onClick = onExportApk,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导出 APK", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            sections.forEach { sec ->
                Text(
                    text = sec.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = sec.accent,
                    modifier = Modifier.padding(top = 4.dp)
                )
                sec.actions.forEach { act ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !running) { onSelectPrompt(act.prompt) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(sec.icon, null, tint = sec.accent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(act.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                Text(act.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
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
}

/** 逆向控制台欢迎仪表盘 */
@Composable
private fun StudioDashboard(
    padding: PaddingValues,
    onQuickPrompt: (String) -> Unit,
    onOpenToolbox: () -> Unit
) {
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
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
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

            Spacer(Modifier.height(20.dp))

            val missionCards = listOf(
                Triple("🛡️ 静态安全扫描", "提取危险权限、组件导出风险与敏感字符串", "扫描该 APK 的完整静态安全风险"),
                Triple("⚡ DEX / Smali 逆向", "反编译核心业务类，分析逻辑与数据流", "反编译 DEX 并列出主要业务包结构"),
                Triple("🪝 自动化 Hook 方案", "快速生成针对证书、Root 与调用的 Frida 脚本", "为该应用生成 Frida Hook 脚本与抓包方案"),
                Triple("🛠️ 签名保护分析与绕过", "分析签名自校验逻辑并提供一键 patch 方案", "请分析该 APK 的签名自校验逻辑并自动提供并执行可靠的绕过方案")
            )

            missionCards.forEach { (title, desc, prompt) ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onQuickPrompt(prompt) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = onOpenToolbox,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("打开完整逆向一键工具箱 (12 项快捷操作)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
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
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${item.apkName} · ${item.messageCount} msgs",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
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
