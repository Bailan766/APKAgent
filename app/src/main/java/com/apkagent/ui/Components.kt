package com.apkagent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkagent.agent.ToolConfirmationManager
import com.apkagent.agent.ToolRiskLevel
import com.apkagent.i18n.LocalAppStrings
import com.apkagent.service.KeepAliveService
import kotlinx.coroutines.delay

private val EXPORT_SUGGEST_TOOLS = setOf(
    "apk_sign", "apk_repack", "smart_patch_apply",
    "auto_patch", "bypass_signature", "apk_patch", "apk_auto_patch"
)

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
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.25f))
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

/**
 * Material 3 紧凑型工具调用胶囊卡片：
 * - 执行中与成功后默认保持单行折叠，消除列表频繁抖动和 JSON 刷屏；
 * - 失败时自动展开便于排查报错；
 * - 对重打包/签名/补丁类工具成功完成后内联提供「立即导出 APK」快捷动作。
 */
@Composable
fun ToolCard(
    item: ChatItem,
    onQuickExport: (() -> Unit)? = null
) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    var copied by remember(item.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    // 仅在执行失败时自动展开报错，执行中及成功完成时保持单行紧凑展示
    LaunchedEffect(item.streaming, item.toolSuccess) {
        if (!item.streaming && !item.toolSuccess) {
            expanded = true
        }
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    val toolName = item.toolName ?: "tool"
    val humanTitle = remember(toolName) { KeepAliveService.humanReadableTool(toolName) }
    val isSuccess = item.toolSuccess
    val accent = when {
        item.streaming -> MaterialTheme.colorScheme.primary
        isSuccess -> Color(0xFF10B981)
        else -> MaterialTheme.colorScheme.error
    }
    val cardBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val borderColor = if (item.streaming) accent.copy(alpha = 0.45f) else accent.copy(alpha = 0.25f)

    val previewLine = remember(item.toolResult) {
        item.toolResult?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded },
        color = cardBg,
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.streaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = accent
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = humanTitle,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(Modifier.width(6.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = toolName,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(6.dp))

                Surface(
                    color = accent.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = when {
                            item.streaming -> "执行中"
                            isSuccess -> "成功"
                            else -> "失败"
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                }

                Spacer(Modifier.weight(0.01f).widthIn(min = 4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 折叠态单行结果摘要，无需展开即可看到核心输出
            if (!expanded && !item.streaming && previewLine.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = previewLine,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    item.toolArgs?.takeIf { it.isNotBlank() }?.let { args ->
                        Text(
                            text = "调用参数 · ARGS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = args,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .heightIn(max = 150.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }

                    item.toolResult?.takeIf { it.isNotBlank() }?.let { res ->
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "输出结果 · STDOUT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = accent.copy(alpha = 0.9f)
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = if (copied) "✓ 已复制" else "复制结果",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        clipboard.setText(AnnotatedString(res))
                                        copied = true
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = res,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }

            // 当补丁/签名/重打包类工具成功执行后，提供内联「立即导出 APK」闭环按钮
            if (!item.streaming && isSuccess && onQuickExport != null && toolName in EXPORT_SUGGEST_TOOLS) {
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(
                    onClick = onQuickExport,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("立即导出重打包 APK", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** 敏感操作权限确认弹窗（正向主按钮引导，支持一键切为快速模式） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PermissionDialog(
    req: ToolConfirmationManager.ConfirmationRequest,
    onResult: (Boolean) -> Unit,
    onAllowAndSwitchFast: (() -> Unit)? = null
) {
    val s = LocalAppStrings.current
    val call = req.call
    val humanTitle = remember(call.name) { KeepAliveService.humanReadableTool(call.name) }
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
        onDismissRequest = { onAction(false) },
        icon = {
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                tint = badgeColor,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    s.toolAuthTitle,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    humanTitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = badgeColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.5f))
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

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.3f)),
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (onAllowAndSwitchFast != null) {
                    FilledTonalButton(
                        onClick = {
                            if (!handled) {
                                handled = true
                                onAllowAndSwitchFast()
                            }
                        }
                    ) {
                        Text(s.allowAndSwitchFast, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Button(
                    onClick = { onAction(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(s.allowExecute, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { onAction(false) }) {
                Text(s.reject)
            }
        }
    )
}
