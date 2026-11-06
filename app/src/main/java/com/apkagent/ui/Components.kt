package com.apkagent.ui

import com.apkagent.i18n.LocalAppStrings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkagent.agent.ToolConfirmationManager
import com.apkagent.agent.ToolRiskLevel

/** 科技风微徽章 */
@Composable
fun TechBadge(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    icon: @Composable (() -> Unit)? = null
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, contentColor.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            icon?.invoke()
            Text(
                text = text,
                color = contentColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 命令行式工具调用卡片：展示工具名、调用参数与标准输出 */
@Composable
fun ToolCard(item: ChatItem) {
    var expanded by remember { mutableStateOf(false) }
    val isSuccess = item.toolSuccess
    val accent = if (isSuccess) Color(0xFF10B981) else Color(0xFFEF4444)
    val cardBg = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (item.streaming) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                      else if (isSuccess) Color(0xFF10B981).copy(alpha = 0.25f)
                      else Color(0xFFEF4444).copy(alpha = 0.35f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded },
        color = cardBg
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 命令行终端前缀
                Text(
                    "❯",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    item.toolName ?: "command",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))

                if (item.streaming) {
                    val pulse = rememberInfiniteTransition(label = "pulse")
                    val alpha by pulse.animateFloat(
                        initialValue = 0.4f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                        label = "pulseAlpha"
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "RUNNING",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                        )
                    }
                } else {
                    Surface(
                        color = accent.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            if (isSuccess) "EXIT 0" else "FAILED",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = accent
                        )
                    }
                }

                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded || item.streaming) {
                Column(Modifier.padding(top = 8.dp)) {
                    item.toolArgs?.takeIf { it.isNotBlank() }?.let { args ->
                        Text(
                            "ARGS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.background,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                args,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .heightIn(max = 160.dp)
                            )
                        }
                    }

                    item.toolResult?.takeIf { it.isNotBlank() }?.let { res ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "STDOUT",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = accent.copy(alpha = 0.9f)
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.background,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                res,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .heightIn(max = 240.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 敏感操作权限确认弹窗（Cyber Security 质询卡片） */
@Composable
fun PermissionDialog(
    req: ToolConfirmationManager.ConfirmationRequest,
    onResult: (Boolean) -> Unit
) {
    val s = LocalAppStrings.current
    val call = req.call
    // 按 call.id 重置一次性处理标记，连续多个确认弹窗时按钮不会静默失效
    var handled by remember(req.call.id) { mutableStateOf(false) }
    var showFullArgs by remember(req.call.id) { mutableStateOf(false) }

    val onAction = { allow: Boolean ->
        if (!handled) {
            handled = true
            onResult(allow)
        }
    }

    val (badgeText, badgeColor) = when (req.riskLevel) {
        ToolRiskLevel.DANGER -> "高危 DANGER" to Color(0xFFEF4444)
        ToolRiskLevel.CAUTION -> "谨慎 CAUTION" to Color(0xFFF59E0B)
        ToolRiskLevel.SAFE -> "常规 SAFE" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    AlertDialog(
        onDismissRequest = { onAction(false) }, // dismiss 等同拒绝
        icon = {
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                tint = badgeColor,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                s.toolAuthTitle,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = badgeColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.5f))
                    ) {
                        Text(
                            badgeText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        call.name,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    s.toolAuthBody,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 人类可读的操作摘要，替代仅展示截断的原始 JSON
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "操作摘要",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            req.summary.ifBlank { "无附加参数" },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // 完整参数可展开查看
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "PARAMS · ${call.name}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (showFullArgs) call.arguments else call.arguments.take(300),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (showFullArgs) Int.MAX_VALUE else 6
                        )
                        if (call.arguments.length > 300 || call.arguments.count { it == '\n' } > 6) {
                            TextButton(
                                onClick = { showFullArgs = !showFullArgs },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(if (showFullArgs) "收起参数" else "展开完整参数", fontSize = 11.sp)
                            }
                        }
                    }
                }

                Text(
                    s.toolAuthWarn,
                    fontSize = 11.sp,
                    color = Color(0xFFF59E0B)
                )
            }
        },
        confirmButton = {
            // 视觉焦点给「拒绝」：filled 主按钮
            Button(
                onClick = { onAction(false) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(s.reject, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            // 「允许」改为中性描边按钮，弱化一键放行的视觉引导
            OutlinedButton(onClick = { onAction(true) }) {
                Text(s.allowExecute)
            }
        }
    )
}
