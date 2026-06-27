package com.apkagent.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apkagent.ApkAgentApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    onOpenEditor: () -> Unit,
    vm: ChatViewModel = viewModel()
) {
    val context = LocalContext.current
    val messages by vm.messages.collectAsStateWithLifecycle()
    val isRunning by vm.isRunning.collectAsStateWithLifecycle()
    val isExporting by vm.isExporting.collectAsStateWithLifecycle()
    val openApkName by vm.openApkName.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    val apkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val app = context.applicationContext as ApkAgentApp
                    val dest = File(app.workspace, "imported.apk")
                    val input = context.contentResolver.openInputStream(uri)
                    if (input == null) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "❌ 无法读取文件，请检查存储权限", android.widget.Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    input.use { src -> dest.outputStream().use { dst -> src.copyTo(dst) } }
                    if (dest.length() < 64) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "❌ APK 文件为空或损坏，请重新选择", android.widget.Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    vm.setOpenApk(dest)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "✅ 已导入：${dest.name} (${dest.length() / 1024}KB)", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "❌ 导入失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
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
                        Text("APKAgent", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text(
                            openApkName?.let { "已导入：$it" } ?: "Android APK 逆向分析工具",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        apkLauncher.launch(arrayOf("application/vnd.android.package-archive"))
                    }) { Icon(Icons.Default.FolderOpen, "导入 APK") }
                    IconButton(
                        onClick = {
                            vm.exportPatchedApk { ok, msg ->
                                coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        },
                        enabled = !isExporting
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.FileDownload, "导出破解APK")
                        }
                    }
                    IconButton(onClick = onOpenEditor) { Icon(Icons.Default.Edit, "编辑器") }
                    IconButton(onClick = { vm.clearChat() }) { Icon(Icons.Default.CleaningServices, "清空") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "设置") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            ChatInputBar(
                text = input,
                running = isRunning,
                onTextChange = { input = it },
                onSend = { if (input.isNotBlank()) { vm.send(input); input = "" } },
                onStop = { vm.stop() }
            )
        }
    ) { padding ->
        if (messages.isEmpty()) {
            EmptyHint(padding)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
            ) {
                items(messages, key = { it.id }) { item -> MessageBubble(item) }
            }
        }
    }
}

@Composable
private fun MessageBubble(item: ChatItem) {
    when (item.role) {
        Role.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Text(
                    item.content,
                    modifier = Modifier.padding(10.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Role.ASSISTANT -> Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.SmartToy, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp),
                modifier = Modifier.widthIn(max = 340.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    MarkdownText(markdown = item.content)
                    if (item.streaming) {
                        Spacer(Modifier.padding(2.dp))
                        CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
        Role.TOOL -> ToolCard(item)
        Role.DEBUG -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                item.content,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Role.ERROR -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                item.content,
                modifier = Modifier.padding(10.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInputBar(
    text: String,
    running: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 140.dp),
                placeholder = { Text("输入分析指令（如：分析签名 / 反编译 dex）…", fontSize = 14.sp) },
                shape = RoundedCornerShape(20.dp),
                enabled = !running
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, "停止", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                IconButton(onClick = onSend, enabled = text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(padding: androidx.compose.foundation.layout.PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(Modifier.height(32.dp))

            // ── 标题 ──
            Icon(
                Icons.Default.Security, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "APKAgent",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "Android APK 逆向分析工具",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "专业 APK 分析 · 反编译 · 修改 · 重签名",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(Modifier.height(24.dp))

            // ── 快速开始卡片 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("快速开始", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))

                    StepRow("1", "导入 APK", "点击顶部 📁 选择要分析的 APK 文件")
                    StepRow("2", "配置 AI", "设置页 ⚙ 配置 AI 接口（DeepSeek、Qwen 等）")
                    StepRow("3", "开始分析", "在下方输入框描述你的需求")
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 示例指令卡片 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("示例指令", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))

                    val examples = listOf(
                        "分析这个 APK 的签名信息" to Icons.Default.Fingerprint,
                        "反编译 classes.dex 并列出所有类" to Icons.Default.Code,
                        "扫描并移除签名校验" to Icons.Default.Shield,
                        "生成安全风险报告" to Icons.Default.Assessment,
                        "对比两个 APK 的差异" to Icons.Default.Compare,
                    )
                    examples.forEach { (text, icon) ->
                        Row(
                            modifier = Modifier.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(icon, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 功能亮点 ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FeatureChip("smali 编辑", Modifier.weight(1f))
                FeatureChip("自动签名", Modifier.weight(1f))
                FeatureChip("沙箱保护", Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FeatureChip("v1/v2 签名", Modifier.weight(1f))
                FeatureChip("Frida Hook", Modifier.weight(1f))
                FeatureChip("Patch 库", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StepRow(number: String, title: String, desc: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(number, fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FeatureChip(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    ) {
        Text(
            label,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.primary
        )
    }
}
