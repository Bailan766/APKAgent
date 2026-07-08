package com.apkagent.ui

import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.apkagent.terminal.TerminalSession
import com.apkagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 内置终端界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session = remember { TerminalSession() }
    val output by session.output.collectAsState()
    val isRunning by session.isRunning.collectAsState()
    var command by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(listOf<String>()) }
    var historyIndex by remember { mutableIntStateOf(-1) }

    // 启动终端
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

    // 快捷命令
    val quickCommands = listOf(
        "python3 --version" to "Python",
        "node --version" to "Node",
        "pip3 list" to "pip列表",
        "ls -la" to "当前目录",
        "whoami" to "用户",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("内置终端", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isRunning) Color(0xFF4CAF50) else Color.Gray)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // 清屏
                    IconButton(onClick = { session.clearOutput() }) {
                        Icon(Icons.Default.DeleteSweep, "清屏")
                    }
                    // 终止
                    IconButton(onClick = { session.stop(); session.start() }) {
                        Icon(Icons.Default.Refresh, "重启")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A1A),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0D0D0D))
        ) {
            // 快捷按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                quickCommands.forEach { (cmd, label) ->
                    AssistChip(
                        onClick = {
                            session.exec(cmd)
                            history = history + cmd
                        },
                        label = { Text(label, fontSize = 10.sp) },
                        modifier = Modifier.height(28.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = Color(0xFF2A2A2A),
                            labelColor = Color(0xFFCCCCCC)
                        )
                    )
                }
            }

            // 终端输出
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
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
                                setTextColor(Color(0xFF00FF00).toArgb())
                                setPadding(16, 12, 16, 12)
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

            // 输入栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$",
                    color = Color(0xFF4CAF50),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp)
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入命令...", color = Color.Gray) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 14.sp
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF4CAF50),
                        cursorColor = Color(0xFF4CAF50),
                        unfocusedBorderColor = Color(0xFF333333)
                    )
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (command.isNotBlank()) {
                            session.exec(command)
                            history = history + command
                            historyIndex = -1
                            command = ""
                        }
                    },
                    enabled = command.isNotBlank() && isRunning
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        "执行",
                        tint = if (command.isNotBlank()) Color(0xFF4CAF50) else Color.Gray
                    )
                }
            }
        }
    }
}
