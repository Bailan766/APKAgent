package com.apkagent.ui

import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.apkagent.i18n.LocalAppStrings
import com.apkagent.terminal.TerminalSession

/**
 * 内置终端界面（统一 MD3 暖灰无边框风格 + 胶囊快捷命令栏）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val s = LocalAppStrings.current
    val session = remember { TerminalSession() }
    val output by session.output.collectAsState()
    val isRunning by session.isRunning.collectAsState()
    var command by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(listOf<String>()) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    LaunchedEffect(Unit) {
        session.start()
        session.exec("cd /data/data/com.apkagent/files 2>/dev/null || cd /data/data/com.apkagent")
        session.exec("export PATH=/data/data/com.termux/files/usr/bin:\$PATH")
        session.exec("echo '=== APKAgent Terminal ==='")
        session.exec("echo '输入命令开始操作，如: pkg install python'")
        session.exec("echo \"\"")
    }

    DisposableEffect(Unit) {
        onDispose { session.stop() }
    }

    val quickCommands = listOf(
        "python3 --version" to "Python",
        "node --version" to "Node",
        "pip3 list" to "pip列表",
        "ls -la" to "当前目录",
        "whoami" to "用户",
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.builtinTerminal, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        TechBadge(
                            text = if (isRunning) "运行中" else "已停止",
                            containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, s.back)
                    }
                },
                actions = {
                    IconButton(onClick = { session.clearOutput() }) {
                        Icon(Icons.Default.DeleteSweep, "清屏")
                    }
                    IconButton(onClick = { session.stop(); session.start() }) {
                        Icon(Icons.Default.Refresh, "重启")
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
                .background(MaterialTheme.colorScheme.background)
                .imePadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickCommands.forEach { (cmd, label) ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                session.exec(cmd)
                                history = history + cmd
                            }
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                AndroidView(
                    factory = { context ->
                        ScrollView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            addView(TextView(context).apply {
                                typeface = Typeface.MONOSPACE
                                textSize = 12f
                                setTextColor(onSurfaceColor.toArgb())
                                setPadding(28, 20, 28, 20)
                                setTextIsSelectable(true)
                                text = output
                                tag = "terminal_output"
                            })
                        }
                    },
                    update = { scrollView ->
                        val tv = scrollView.findViewWithTag<TextView>("terminal_output")
                        if (tv != null && tv.text.toString() != output) {
                            tv.text = output
                            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "$",
                            color = primaryColor,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        TextField(
                            value = command,
                            onValueChange = { command = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(s.commandPlaceholder, fontSize = 13.sp) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (command.isNotBlank()) {
                            session.exec(command)
                            history = history + command
                            command = ""
                        }
                    },
                    enabled = command.isNotBlank() && isRunning,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (command.isNotBlank() && isRunning) primaryColor
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        "执行",
                        tint = if (command.isNotBlank() && isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}
