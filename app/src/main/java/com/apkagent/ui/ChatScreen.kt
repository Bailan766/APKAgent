package com.apkagent.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apkagent.ApkAgentApp
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
    val pendingConfirm by vm.pendingConfirm.collectAsStateWithLifecycle()
    val openApkName by vm.openApkName.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val apkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val app = context.applicationContext as ApkAgentApp
            val dest = File(app.workspace, "imported.apk")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            vm.setOpenApk(dest)
        }
    }

    pendingConfirm?.let { call ->
        PermissionDialog(call = call, onResult = { vm.confirmToolCall(it) })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("APKAgent", fontFamily = FontFamily.Monospace)
                        Text(
                            openApkName?.let { "已导入：$it" } ?: "未导入 APK",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        apkLauncher.launch(arrayOf("application/vnd.android.package-archive"))
                    }) { Icon(Icons.Default.FolderOpen, "导入 APK") }
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
                placeholder = { Text("描述你想分析的 APK…", fontSize = 14.sp) },
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
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.SmartToy, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(8.dp)
            )
            Text("APKAgent — AI 驱动的 APK 逆向助手", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.padding(6.dp))
            Text(
                "1. 顶部 📁 导入 APK\n" +
                    "2. 设置页填入 API Base / Key / 模型\n" +
                    "3. 用自然语言下指令，例如：\n" +
                    "   “分析这个 APK 的签名信息”\n" +
                    "   “列出 AndroidManifest 里的权限和组件”\n" +
                    "   “把 classes.dex 提取出来并统计类数量”",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }
}
