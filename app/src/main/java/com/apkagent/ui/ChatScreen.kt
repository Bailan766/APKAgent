package com.apkagent.ui

import com.apkagent.i18n.LocalAppStrings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ═══════════════════════════════════════
//  Telegram 风格配色
// ═══════════════════════════════════════
private object TgColors {
    val bubbleUser = Color(0xFFEFFDDE)       // Telegram 绿色气泡
    val bubbleUserDark = Color(0xFF2B5234)   // 暗色模式
    val bubbleOther = Color(0xFFFFFFFF)      // 白色气泡
    val bubbleOtherDark = Color(0xFF1E2C3A)  // 暗色模式
    val bg = Color(0xFFE6EBEE)              // 聊天背景
    val bgDark = Color(0xFF0E1621)           // 暗色背景
    val toolBg = Color(0xFFFFF8E1)           // 工具卡片背景
    val toolBgDark = Color(0xFF1E2A3A)
    val accent = Color(0xFF3390EC)           // Telegram 蓝色
    val timeText = Color(0xFF8EA2B2)         // 时间文字
    val inputBg = Color(0xFFFFFFFF)
    val inputBgDark = Color(0xFF17212B)
    val headerBg = Color(0xFF517DA2)
    val headerBgDark = Color(0xFF1F2936)
}

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

    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(activeProject?.id) {
        vm.selectProject(activeProject)
    }

    // 平滑滚动到底部
    LaunchedEffect(messages.size, messages.lastOrNull()?.streaming) {
        if (messages.isNotEmpty()) {
            delay(50) // 短暂延迟让布局稳定
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val apkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val dest = File(context.cacheDir, "import_${System.currentTimeMillis()}.apk")
                    val ins = context.contentResolver.openInputStream(uri)
                    if (ins == null) {
                        withContext(Dispatchers.Main) {
                            snackbarHostState.showSnackbar(s.cannotReadFile)
                        }
                        return@launch
                    }
                    ins.use { src -> dest.outputStream().use { dst -> src.copyTo(dst) } }
                    vm.setOpenApk(dest)
                    dest.delete()
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(s.projectCreated)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(s.fmt(s.importFailed, "msg" to e.message?.take(50)))
                    }
                }
            }
        }
    }

    val isDark = isSystemInDarkTheme()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = if (isDark) TgColors.bgDark else TgColors.bg,
        topBar = {
            // Telegram 风格顶栏
            Surface(
                color = if (isDark) TgColors.headerBgDark else TgColors.headerBg,
                shadowElevation = 2.dp
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "APKAgent",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp
                            )
                            Text(
                                openApkName?.let { s.fmt(s.importedPrefix, "name" to it) } ?: s.androidReverseAnalysis,
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenProjects) {
                            Icon(Icons.Default.Apps, s.projects, tint = Color.White)
                        }
                        // 导入 APK（备用入口）
                        IconButton(onClick = {
                            apkLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                        }) {
                            Icon(Icons.Default.FolderOpen, s.importApk, tint = Color.White)
                        }
                        // 导出
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
                                    Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White
                                )
                            } else {
                                Icon(Icons.Default.FileDownload, s.export, tint = Color.White)
                            }
                        }
                        // 更多菜单
                        var showMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, s.more, tint = Color.White)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(s.reverseProjects) },
                                onClick = { showMenu = false; onOpenProjects() },
                                leadingIcon = { Icon(Icons.Default.Apps, null) }
                            )
                            DropdownMenuItem(
                                text = { Text(s.projectFiles) },
                                onClick = { showMenu = false; onOpenEditor() },
                                leadingIcon = { Icon(Icons.Default.Edit, null) }
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
                                text = { Text(s.clearChat) },
                                onClick = { showMenu = false; vm.clearChat() },
                                leadingIcon = { Icon(Icons.Default.CleaningServices, null) }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        },
        bottomBar = {
            // Telegram 风格输入栏
            TgInputBar(
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
                isDark = isDark
            )
        }
    ) { padding ->
        if (messages.isEmpty()) {
            TgWelcomeScreen(padding, isDark)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(messages, key = { it.id }) { item ->
                    MessageRow(item, isDark)
                }
                // 底部留白
                item { Spacer(Modifier.height(4.dp)) }
            }
        }
    }

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text(s.projectAiHistory) },
            text = {
                if (historyList.isEmpty()) {
                    Text(s.noHistoryYet)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(historyList, key = { it.id }) { item ->
                            ListItem(
                                headlineContent = { Text(item.userInput.ifBlank { s.unnamedChat }, maxLines = 1) },
                                supportingContent = { Text("${item.apkName.ifBlank { openApkName ?: s.currentProject }} · ${s.fmt(s.messageCount, "n" to item.messageCount)}") },
                                leadingContent = { Icon(Icons.Default.History, null) },
                                modifier = Modifier.clickable {
                                    vm.loadHistory(item.id)
                                    showHistory = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text(s.close) } }
        )
    }

    pendingConfirmation?.let { request ->
        ToolConfirmationDialog(
            request = request,
            onApprove = { vm.approvePendingToolCall() },
            onDeny = { vm.denyPendingToolCall() }
        )
    }
}

@Composable
private fun ToolConfirmationDialog(
    request: com.apkagent.agent.ToolConfirmationManager.ConfirmationRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    val s = LocalAppStrings.current
    val riskColor = when (request.riskLevel) {
        com.apkagent.agent.ToolRiskLevel.DANGER -> Color(0xFFE53935)
        com.apkagent.agent.ToolRiskLevel.CAUTION -> Color(0xFFFF9800)
        com.apkagent.agent.ToolRiskLevel.SAFE -> Color(0xFF4CAF50)
    }
    AlertDialog(
        onDismissRequest = onDeny,
        title = {
            Text(s.confirmSensitive)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.aiRequestsSensitive)
                Text(s.fmt(s.toolLabel, "name" to request.call.name), fontWeight = FontWeight.SemiBold)
                Text(s.fmt(s.riskLevelLabel, "level" to request.riskLevel.name), color = riskColor)
                Text(
                    text = request.summary.ifBlank { s.noParams },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onApprove) { Text(s.allow, color = riskColor) }
        },
        dismissButton = {
            TextButton(onClick = onDeny) { Text(s.deny) }
        }
    )
}

// ═══════════════════════════════════════
//  Telegram 风格消息气泡
// ═══════════════════════════════════════

@Composable
private fun MessageRow(item: ChatItem, isDark: Boolean) {
    val timeStr = remember(item.id) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    when (item.role) {
        Role.USER -> UserBubble(item, timeStr, isDark)
        Role.ASSISTANT -> AssistantBubble(item, timeStr, isDark)
        Role.TOOL -> ToolBubble(item, isDark)
        Role.ERROR -> ErrorBubble(item, isDark)
        Role.SYSTEM -> SystemChip(item)
        Role.DEBUG -> DebugBubble(item, isDark)
    }
}

@Composable
private fun UserBubble(item: ChatItem, time: String, isDark: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 60.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            color = if (isDark) TgColors.bubbleUserDark else TgColors.bubbleUser,
            shape = RoundedCornerShape(12.dp, 12.dp, 2.dp, 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    item.content,
                    color = if (isDark) Color.White else Color(0xFF1B1B1B),
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    time,
                    fontSize = 11.sp,
                    color = if (isDark) Color.White.copy(alpha = 0.5f) else Color(0xFF6EB875),
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun AssistantBubble(item: ChatItem, time: String, isDark: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 48.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        // 头像
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(TgColors.accent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.SmartToy, null,
                tint = Color.White, modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Surface(
            color = if (isDark) TgColors.bubbleOtherDark else TgColors.bubbleOther,
            shape = RoundedCornerShape(2.dp, 12.dp, 12.dp, 12.dp),
            shadowElevation = if (isDark) 0.dp else 0.5.dp
        ) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp)) {
                MarkdownText(markdown = item.content)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.streaming) {
                        TypingDots()
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        time,
                        fontSize = 11.sp,
                        color = TgColors.timeText
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolBubble(item: ChatItem, isDark: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val bg = if (isDark) TgColors.toolBgDark else TgColors.toolBg
    val accent = if (item.toolSuccess) Color(0xFF4CAF50) else Color(0xFFE53935)

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Spacer(Modifier.width(38.dp))
        Surface(
            color = bg,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { expanded = !expanded }
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (item.toolSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                        null, tint = accent, modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        item.toolName ?: "tool",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = accent
                    )
                    if (item.streaming) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, modifier = Modifier.size(16.dp),
                        tint = TgColors.timeText
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column(Modifier.padding(top = 6.dp)) {
                        item.toolArgs?.takeIf { it.isNotBlank() }?.let { args ->
                            Text(LocalAppStrings.current.params, fontSize = 10.sp, color = TgColors.timeText)
                            Text(
                                args.take(500),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        item.toolResult?.takeIf { it.isNotBlank() }?.let { res ->
                            Spacer(Modifier.height(4.dp))
                            Text(LocalAppStrings.current.result, fontSize = 10.sp, color = TgColors.timeText)
                            Text(
                                res.take(1000),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBubble(item: ChatItem, isDark: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = if (isDark) Color(0xFF3A1C1C) else Color(0xFFFFEBEE),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning, null,
                    tint = Color(0xFFE53935), modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    item.content,
                    fontSize = 13.sp,
                    color = if (isDark) Color(0xFFEF9A9A) else Color(0xFFC62828)
                )
            }
        }
    }
}

@Composable
private fun SystemChip(item: ChatItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = Color(0xFF000000).copy(alpha = 0.2f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                item.content,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun DebugBubble(item: ChatItem, isDark: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            item.content,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = TgColors.timeText.copy(alpha = 0.6f)
        )
    }
}

// ═══════════════════════════════════════
//  Telegram 风格输入栏
// ═══════════════════════════════════════

@Composable
private fun TgInputBar(
    text: String,
    running: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isDark: Boolean
) {
    Surface(
        color = if (isDark) TgColors.inputBgDark else TgColors.inputBg,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // 输入框
            Surface(
                color = if (isDark) Color(0xFF242F3D) else Color(0xFFF0F2F5),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f)
            ) {
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp, max = 120.dp),
                    placeholder = {
                        Text(LocalAppStrings.current.inputPlaceholder, fontSize = 15.sp, color = TgColors.timeText)
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = TgColors.accent
                    ),
                    textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend() }),
                    enabled = !running
                )
            }

            Spacer(Modifier.width(6.dp))

            // 发送/停止按钮
            if (running) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE53935))
                ) {
                    Icon(Icons.Default.Stop, LocalAppStrings.current.stop, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = text.isNotBlank(),
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (text.isNotBlank()) TgColors.accent else TgColors.accent.copy(alpha = 0.5f))
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send, LocalAppStrings.current.send,
                        tint = Color.White, modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════
//  Telegram 风格打字动画（三个跳动点）
// ═══════════════════════════════════════

@Composable
private fun TypingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse
                ), label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(TgColors.accent.copy(alpha = alpha))
            )
        }
    }
}

// ═══════════════════════════════════════
//  Telegram 风格欢迎页面
// ═══════════════════════════════════════

@Composable
private fun TgWelcomeScreen(padding: PaddingValues, isDark: Boolean) {
    val s = LocalAppStrings.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            // Logo
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(TgColors.accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Security, null,
                    tint = Color.White, modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "APKAgent",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDark) Color.White else Color(0xFF1B1B1B)
            )
            Text(
                s.welcomeSubtitle,
                fontSize = 14.sp,
                color = TgColors.timeText
            )

            Spacer(Modifier.height(28.dp))

            // 快捷按钮
            val suggestions = listOf(
                s.suggestSignLabel to s.suggestSignPrompt,
                s.suggestDexLabel to s.suggestDexPrompt,
                s.suggestRiskLabel to s.suggestRiskPrompt,
                s.suggestPatchLabel to s.suggestPatchPrompt,
                s.suggestFridaLabel to s.suggestFridaPrompt
            )
            suggestions.forEach { (label, _) ->
                Surface(
                    color = if (isDark) TgColors.bubbleOtherDark else TgColors.bubbleOther,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        fontSize = 14.sp,
                        color = if (isDark) Color.White else Color(0xFF1B1B1B)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                s.welcomeHint,
                fontSize = 12.sp,
                color = TgColors.timeText,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun isSystemInDarkTheme(): Boolean {
    return androidx.compose.foundation.isSystemInDarkTheme()
}
