package com.apkagent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

/** M3 无边框药丸胶囊微徽章 */
@Composable
fun TechBadge(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    icon: @Composable (() -> Unit)? = null
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            icon?.invoke()
            Text(
                text = text,
                color = contentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Material 3 紧凑型大圆角无边框工具卡片：
 * - 柔和色块底衬图标 + 粗体动作标题 + 常规副标题/输出摘要；
 * - 无传统细边框，使用暖灰与主题色块区分层级；
 * - 执行中及完成后默认折叠，失败自动展开，支持重打包后内联快捷导出。
 */
@Composable
fun ToolCard(
    item: ChatItem,
    onQuickExport: (() -> Unit)? = null
) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    var copied by remember(item.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

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

    val statusContainerColor: Color
    val statusContentColor: Color
    val statusText: String
    when {
        item.streaming -> {
            statusContainerColor = MaterialTheme.colorScheme.primaryContainer
            statusContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            statusText = "执行中"
        }
        isSuccess -> {
            statusContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            statusContentColor = MaterialTheme.colorScheme.primary
            statusText = "已完成"
        }
        else -> {
            statusContainerColor = MaterialTheme.colorScheme.errorContainer
            statusContentColor = MaterialTheme.colorScheme.onErrorContainer
            statusText = "失败"
        }
    }

    val previewLine = remember(item.toolResult) {
        item.toolResult?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // M3 左侧柔和色块底衬图标容器
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(statusContainerColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.streaming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = statusContentColor
                        )
                    } else {
                        Icon(
                            imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = statusContentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = humanTitle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (!expanded && !item.streaming && previewLine.isNotEmpty()) previewLine else toolName,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(8.dp))

                TechBadge(
                    text = statusText,
                    containerColor = statusContainerColor,
                    contentColor = statusContentColor
                )

                Spacer(Modifier.width(4.dp))

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item.toolArgs?.takeIf { it.isNotBlank() }?.let { args ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "调用参数 · $toolName",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = args,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(10.dp)
                                        .heightIn(max = 150.dp)
                                        .verticalScroll(rememberScrollState())
                                )
                            }
                        }
                    }

                    item.toolResult?.takeIf { it.isNotBlank() }?.let { res ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "输出结果",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = statusContentColor
                                )
                                Spacer(Modifier.weight(1f))
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable {
                                            clipboard.setText(AnnotatedString(res))
                                            copied = true
                                        }
                                ) {
                                    Text(
                                        text = if (copied) "✓ 已复制" else "复制结果",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = res,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .padding(10.dp)
                                        .heightIn(max = 220.dp)
                                        .verticalScroll(rememberScrollState())
                                )
                            }
                        }
                    }
                }
            }

            if (!item.streaming && isSuccess && onQuickExport != null && toolName in EXPORT_SUGGEST_TOOLS) {
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = onQuickExport,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("立即导出重打包 APK", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Material 3 敏感操作权限确认弹窗（无边框 tonal 层级，正向确认按钮） */
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
    var handled by remember(req.call.id) { mutableStateOf(false) }
    var showFullArgs by remember(req.call.id) { mutableStateOf(false) }

    val onAction = { allow: Boolean ->
        if (!handled) {
            handled = true
            onResult(allow)
        }
    }

    val (badgeText, badgeBg, badgeFg) = when (req.riskLevel) {
        ToolRiskLevel.DANGER -> Triple("高危 DANGER", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        ToolRiskLevel.CAUTION -> Triple("谨慎 CAUTION", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        ToolRiskLevel.SAFE -> Triple("常规 SAFE", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    AlertDialog(
        onDismissRequest = { onAction(false) },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(badgeBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = badgeFg,
                    modifier = Modifier.size(26.dp)
                )
            }
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TechBadge(text = badgeText, containerColor = badgeBg, contentColor = badgeFg)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        call.name,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    s.toolAuthBody,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "操作摘要",
                            fontSize = 11.sp,
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
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "调用参数 · ${call.name}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    color = MaterialTheme.colorScheme.primary
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
                        },
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(s.allowAndSwitchFast, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Button(
                    onClick = { onAction(true) },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(s.allowExecute, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(false) },
                shape = RoundedCornerShape(50)
            ) {
                Text(s.reject)
            }
        }
    )
}
