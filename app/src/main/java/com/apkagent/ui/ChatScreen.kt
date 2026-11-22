package com.apkagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
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
import androidx.compose.ui.platform.LocalDensity
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

    // 仅在本来处于底部时跟随流式增量滚动，上滑查看历史不抢回焦点
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
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = activeProject?.name ?: "APKAgent",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(8.dp))
                            TechBadge(
                                text = if (isRunning) "分析中" else "就绪",
                                containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (executionMode == ExecutionMode.FAST) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { vm.toggleExecutionMode() }
                            ) {
                                Text(
                                    text = if (executionMode == ExecutionMode.FAST) "⚡ 快速" else "🛡️ 安全",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (executionMode == ExecutionMode.FAST) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                        Text(
                            text = openApkName?.let { s.fmt(s.importedPrefix, "name" to it) } ?: s.androidReverseAnalysis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(onClick = doExport, enabled = !isExporting) {
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
                            text = { Text(s.projects) },
                            onClick = { showMenu = false; onOpenProjects() },
                            leadingIcon = { Icon(Icons.Default.Dashboard, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(s.projectFiles) },
                            onClick = { showMenu = false; onOpenEditor() },
                            leadingIcon = { Icon(Icons.Default.Code, null) }
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
                            text = { Text(if (executionMode == ExecutionMode.FAST) "切换为安全模式" else "切换为快速模式") },
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
                    containerColor = MaterialTheme.colorScheme.background
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
                openApkName = openApkName ?: activeProject?.name,
                executionMode = executionMode,
                onQuickPrompt = { prompt -> vm.send(prompt) },
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
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { item ->
                        EngineeringMessageRow(
                            item = item,
                            onQuickExport = doExport
                        )
                    }
                }

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
                        shape = RoundedCornerShape(50),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "回到底部", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("回到底部", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

/** 用户指令气泡（M3 无边框暖酒红容器分层 + 大圆角） */
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
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 6.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "我的指令",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = time,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = "复制指令",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
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
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/** AI 逆向响应卡片（M3 暖灰容器 + 柔和图标底衬 + 胶囊徽章） */
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
        shape = RoundedCornerShape(topStart = 6.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "APKAgent",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(6.dp))
                        TechBadge(
                            text = if (item.streaming) "分析中" else "M3 Core",
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = time,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (item.content.isNotBlank() && !item.streaming) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                clipboard.setText(AnnotatedString(item.content))
                                copied = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "复制结论",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (!item.reasoningContent.isNullOrBlank() || item.isThinking) {
                com.apkagent.ui.components.NeuralTrace(
                    reasoningText = item.reasoningContent.orEmpty(),
                    isThinking = item.isThinking,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            if (item.content.isNotBlank()) {
                MarkdownText(markdown = item.content)
            }

            if (item.streaming && !item.isThinking) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        modifier = Modifier.width(64.dp).height(3.dp).clip(CircleShape),
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
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                item.content,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onErrorContainer
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
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.padding(horizontal = 6.dp)
    )
}

/** Material 3 底部胶囊输入栏（自动避让全局悬浮底栏 & 键盘） */
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
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val bottomClearance = if (imeVisible) 6.dp else 78.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = bottomClearance)
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onOpenToolbox() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "工具箱",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            quickChips.forEach { (label, prompt) ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = !running) { onQuickPrompt(prompt) }
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 12.sp,
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier.weight(1f)
            ) {
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp, max = 120.dp),
                    placeholder = {
                        Text(
                            s.inputPlaceholder,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank() && !running) onSend() }),
                    enabled = !running
                )
            }

            Spacer(Modifier.width(8.dp))

            if (running) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                ) {
                    Icon(Icons.Default.Stop, s.stop, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(22.dp))
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = text.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        s.send,
                        tint = if (text.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

private data class ToolboxAction(
    val title: String,
    val subtitle: String,
    val badge: String,
    val prompt: String
)

private data class ToolboxSection(
    val title: String,
    val icon: ImageVector,
    val actions: List<ToolboxAction>
)

/** Material 3 底部逆向一键工具箱抽屉（无边框柔和色块底衬 + 胶囊徽章） */
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
                actions = listOf(
                    ToolboxAction("全面静态风险扫描", "扫描危险权限、敏感组件导出与明文隐患", "默认", "对当前 APK 进行全面静态安全风险扫描并输出总结报告"),
                    ToolboxAction("Manifest 组件深度审计", "提取四大组件、Intent-Filter 与 DeepLink 入口", "只读", "解析 AndroidManifest.xml 并列出导出组件、DeepLink 入口与风险点"),
                    ToolboxAction("敏感硬编码密钥与 URL 提取", "扫描 APK/DEX 中的 Key、Token、内网接口与域名", "审计", "使用脚本扫描提取当前 APK 中的硬编码密钥、API 域名与敏感字符串")
                )
            ),
            ToolboxSection(
                title = "DEX / Smali 逆向分析",
                icon = Icons.Default.Code,
                actions = listOf(
                    ToolboxAction("核心包结构与入口分析", "反编译 DEX 主入口、Application 与首页流程", "默认", "分析 classes.dex 包结构、Application 入口以及主界面逻辑"),
                    ToolboxAction("加壳 / 混淆与架构识别", "检测加固厂商、Native SO 保护及开发框架", "识别", "检测当前 APK 是否加壳混淆、包含的 Native 库与开发技术栈"),
                    ToolboxAction("会员 / 付费授权逻辑定位", "检索 VIP、isPro、License、Verify 相关关键类与方法", "分析", "在 DEX 中检索定位 VIP/会员/付费授权校验核心类与方法并反编译分析")
                )
            ),
            ToolboxSection(
                title = "一键补丁与防护绕过",
                icon = Icons.Default.AutoFixHigh,
                actions = listOf(
                    ToolboxAction("绕过签名自校验", "定位 PackageInfo 签名校验代码并应用智能补丁", "快速模式", "分析该 APK 的签名自校验位置并自动应用绕过补丁"),
                    ToolboxAction("去除开屏广告 / 强制更新弹窗", "定位 Splash 倒计时、广告 SDK 与升级 Dialog 调用链", "Beta版本", "分析开屏广告和强制更新弹窗触发逻辑，并提供去除或跳过方案"),
                    ToolboxAction("解除 Root / Frida / 调试检测", "查找反调试、su 文件与端口检测逻辑", "Beta版本", "检索当前应用中的 Root、Xposed、Frida 和 Ptrace 反调试检测逻辑并给出绕过方案")
                )
            ),
            ToolboxSection(
                title = "脚本实战与打包输出",
                icon = Icons.Default.Build,
                actions = listOf(
                    ToolboxAction("生成 Frida SSL 绕过抓包脚本", "生成 TrustManager / OkHttp / BoringSSL Hook 脚本", "Frida", "针对当前 APK 生成通用且可直接运行的 Frida SSL Pinning 绕过抓包脚本"),
                    ToolboxAction("内置 Python 批量分析 DEX", "调用内置 Kotlin Python 3.12 引擎扫描类与符号", "内置引擎", "使用 Python 分析脚本统计当前 APK 所有 DEX 类名、权限和可疑 API"),
                    ToolboxAction("工作区补丁打包与签名", "检验已修改 Smali/资源并重打包生成签名 APK", "默认", "检查工作区当前修改并执行重打包、ZipAlign 对齐与 V1/V2 签名")
                )
            )
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Build, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("逆向一键工具箱", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text("点击任意项目直接派发给 Agent 全自动执行", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(
                    onClick = onExportApk,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导出 APK", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            sections.forEach { sec ->
                Text(
                    text = sec.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                )
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        sec.actions.forEach { act ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable(enabled = !running) { onSelectPrompt(act.prompt) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(13.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(sec.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                act.title,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            TechBadge(
                                                text = act.badge,
                                                containerColor = MaterialTheme.colorScheme.surface,
                                                contentColor = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(act.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(Modifier.width(6.dp))
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
    }
}

/** Material 3 玫瑰主卡 + 双列功能块 + 分组列表欢迎仪表盘 */
@Composable
private fun StudioDashboard(
    padding: PaddingValues,
    openApkName: String?,
    executionMode: ExecutionMode,
    onQuickPrompt: (String) -> Unit,
    onOpenToolbox: () -> Unit
) {
    val s = LocalAppStrings.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 柔和粉红/玫瑰渐变主卡片
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(M3RoseGradient)
                .clickable { onOpenToolbox() }
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TechBadge(
                        text = "默认",
                        containerColor = M3RoseHeroTitleColor.copy(alpha = 0.12f),
                        contentColor = M3RoseHeroTitleColor
                    )
                    TechBadge(
                        text = if (executionMode == ExecutionMode.FAST) "⚡ 快速模式" else "🛡️ 安全模式",
                        containerColor = M3RoseHeroTitleColor.copy(alpha = 0.12f),
                        contentColor = M3RoseHeroTitleColor
                    )
                    TechBadge(
                        text = "Beta版本",
                        containerColor = M3RoseHeroTitleColor.copy(alpha = 0.12f),
                        contentColor = M3RoseHeroTitleColor
                    )
                }

                Text(
                    text = openApkName?.let { "$it · 逆向就绪" } ?: "APKAgent 逆向助手",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = M3RoseHeroTitleColor
                )

                Text(
                    text = s.welcomeSubtitle,
                    fontSize = 13.sp,
                    color = M3RoseHeroBodyColor,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(2.dp))

                Button(
                    onClick = onOpenToolbox,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = M3RoseHeroTitleColor,
                        contentColor = Color(0xFFFFD9DF)
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("打开 12 项逆向一键工具箱", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        // 2. 双列大圆角功能卡片（柔和色块底衬图标）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DashboardDualCard(
                title = "静态安全扫描",
                subtitle = "权限与敏感风险",
                badge = "默认",
                icon = Icons.Default.Security,
                onClick = { onQuickPrompt("扫描该 APK 的完整静态安全风险") },
                modifier = Modifier.weight(1f)
            )

            DashboardDualCard(
                title = "绕过签名校验",
                subtitle = "自动定位并修补",
                badge = "⚡ 快速",
                icon = Icons.Default.VerifiedUser,
                onClick = { onQuickPrompt("请分析该 APK 的签名自校验逻辑并自动提供并执行可靠的绕过方案") },
                modifier = Modifier.weight(1f)
            )
        }

        // 3. 大圆角分组列表容器
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DashboardListItem(
                    title = "DEX / Smali 深度反编译",
                    subtitle = "解析核心业务类、Application 与入口调用链",
                    badge = "默认",
                    icon = Icons.Default.Code,
                    onClick = { onQuickPrompt("反编译 DEX 并列出主要业务包结构") }
                )
                DashboardListItem(
                    title = "生成 Frida Hook 脚本",
                    subtitle = "一键生成 HTTPS 抓包与反调试绕过脚本",
                    badge = "Beta版本",
                    icon = Icons.Default.AutoFixHigh,
                    onClick = { onQuickPrompt("为该应用生成 Frida Hook 脚本与抓包方案") }
                )
                DashboardListItem(
                    title = "全部 12 项一键逆向工具箱",
                    subtitle = "去广告弹窗、Root 检测解除、Python 批量分析",
                    badge = "12 项",
                    icon = Icons.Default.Build,
                    onClick = onOpenToolbox
                )
            }
        }
    }
}

@Composable
private fun DashboardDualCard(
    title: String,
    subtitle: String,
    badge: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.weight(1f))
                TechBadge(
                    text = badge,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun DashboardListItem(
    title: String,
    subtitle: String,
    badge: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    TechBadge(
                        text = badge,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
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
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(s.history, fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(historyList, key = { it.id }) { item ->
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { onSelect(item) }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.userInput.ifBlank { item.apkName },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
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
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(50)) { Text(s.close) }
        }
    )
}
