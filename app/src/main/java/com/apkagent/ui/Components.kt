package com.apkagent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkagent.agent.PendingToolCall

/** 工具调用卡片：展示工具名、参数、结果（可折叠） */
@Composable
fun ToolCard(item: ChatItem) {
    var expanded by remember { mutableStateOf(false) }
    val accent = if (item.toolSuccess) MaterialTheme.colorScheme.secondary
    else MaterialTheme.colorScheme.error
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Build, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    item.toolName ?: "tool",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = accent
                )
                Spacer(Modifier.width(8.dp))
                if (item.streaming) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        if (item.toolSuccess) "完成" else "失败",
                        fontSize = 11.sp,
                        color = accent
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!item.streaming) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "展开",
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { expanded = !expanded },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = expanded || item.streaming) {
                Column {
                    item.toolArgs?.takeIf { it.isNotBlank() }?.let { args ->
                        Text("参数：", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            args,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                                .heightIn(max = 200.dp)
                        )
                    }
                    item.toolResult?.let { res ->
                        Spacer(Modifier.padding(2.dp))
                        Text("结果：", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            res,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                                .heightIn(max = 320.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 敏感操作权限确认弹窗 */
@Composable
fun PermissionDialog(call: PendingToolCall, onResult: (Boolean) -> Unit) {
    var handled by remember { mutableStateOf(false) }
    val onAction = { allow: Boolean ->
        if (!handled) {
            handled = true
            onResult(allow)
        }
    }
    AlertDialog(
        onDismissRequest = { onAction(false) },
        title = { Text("工具调用授权") },
        text = {
            Column {
                Text("AI 请求执行一个敏感操作，需要你确认：")
                Spacer(Modifier.padding(4.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp)
                ) {
                    Text(
                        "${call.name}\n${call.arguments}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.padding(6.dp))
                Text(
                    "⚠ 该操作可能修改/删除文件或改动 APK。拒绝则 AI 会收到拒绝提示并继续推理。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAction(true) }) { Text("允许执行") } },
        dismissButton = { TextButton(onClick = { onAction(false) }) { Text("拒绝") } }
    )
}
