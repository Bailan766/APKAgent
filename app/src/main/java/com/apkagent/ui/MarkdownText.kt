package com.apkagent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 高性能 Markdown 渲染引擎：
 * 1. 段落合并渲染为单 AnnotatedString，杜绝逐行创建 Text 组件导致的 Compose 重组风暴与掉帧；
 * 2. 代码块支持横向滚动、语言标签与一键复制；
 * 3. 引用块、等宽标记、加粗、斜体与标题极速单 pass 编译。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    if (markdown.isBlank()) return

    val context = LocalContext.current
    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    val codeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
    val inlineCodeColor = MaterialTheme.colorScheme.primary
    val primaryColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurface
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant

    // 内存记忆解析结构，避免重组时反复计算
    val blocks = remember(markdown) {
        parseMarkdownBlocks(markdown)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> {
                    val annotated = remember(block.rawText) {
                        compileInlineStyles(
                            text = block.rawText,
                            textColor = textColor,
                            primaryColor = primaryColor,
                            mutedColor = mutedColor,
                            inlineCodeBg = inlineCodeBg,
                            inlineCodeColor = inlineCodeColor
                        )
                    }
                    Text(
                        text = annotated,
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        color = textColor
                    )
                }

                is MdBlock.CodeBlock -> {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = codeBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, outlineColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            // 代码块顶栏（语言 + 复制按钮）
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = block.language.ifBlank { "TERMINAL" }.uppercase(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("code", block.code))
                                            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy",
                                        tint = mutedColor,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("COPY", fontSize = 10.sp, color = mutedColor, fontFamily = FontFamily.Monospace)
                                }
                            }

                            // 代码内容
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = block.code,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = textColor
                                )
                            }
                        }
                    }
                }

                is MdBlock.Quote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(IntrinsicSize.Min)
                                .clip(RoundedCornerShape(2.dp))
                                .background(primaryColor)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = block.text,
                            fontSize = 12.5.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp,
                            color = mutedColor
                        )
                    }
                }

                is MdBlock.Divider -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(outlineColor)
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

private sealed class MdBlock {
    data class Paragraph(val rawText: String) : MdBlock()
    data class CodeBlock(val language: String, val code: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    object Divider : MdBlock()
}

/**
 * 极速两阶段 Markdown 分块：将连续普通文本行合并为一个 Paragraph，大幅降低 Composable 节点量
 */
private fun parseMarkdownBlocks(md: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = md.lines()
    var i = 0
    val paragraphBuffer = StringBuilder()

    fun flushParagraph() {
        if (paragraphBuffer.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paragraphBuffer.toString().trimEnd('\n')))
            paragraphBuffer.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]

        // 识别代码块
        if (line.startsWith("```")) {
            flushParagraph()
            val language = line.removePrefix("```").trim()
            val codeSb = StringBuilder()
            i++
            while (i < lines.size && !lines[i].startsWith("```")) {
                codeSb.appendLine(lines[i])
                i++
            }
            if (i < lines.size && lines[i].startsWith("```")) {
                i++ // 跳过闭合代码块
            }
            // 未闭合代码块容错：流式输出时 ``` 未出现，也按 CodeBlock 渲染已收集内容
            blocks.add(MdBlock.CodeBlock(language, codeSb.toString().trimEnd('\n')))
            continue
        }

        // 识别引用块
        if (line.startsWith("> ") || line == ">") {
            flushParagraph()
            val quoteSb = StringBuilder()
            while (i < lines.size && (lines[i].startsWith("> ") || lines[i] == ">")) {
                quoteSb.appendLine(lines[i].removePrefix("> ").removePrefix(">"))
                i++
            }
            blocks.add(MdBlock.Quote(quoteSb.toString().trimEnd('\n')))
            continue
        }

        // 识别分割线
        val trimmed = line.trim()
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            flushParagraph()
            blocks.add(MdBlock.Divider)
            i++
            continue
        }

        // 空行作为段落分界
        if (trimmed.isEmpty()) {
            flushParagraph()
            i++
            continue
        }

        // 普通行追加到当前段落
        paragraphBuffer.appendLine(line)
        i++
    }

    flushParagraph()
    return blocks
}

/**
 * 单 Pass 编译行内样式：标题、列表、加粗、行内代码、斜体
 */
private fun compileInlineStyles(
    text: String,
    textColor: Color,
    primaryColor: Color,
    mutedColor: Color,
    inlineCodeBg: Color,
    inlineCodeColor: Color
): AnnotatedString = buildAnnotatedString {
    val lines = text.lines()

    lines.forEachIndexed { lineIdx, line ->
        if (lineIdx > 0) append("\n")

        var remaining = line
        var isHeader = false

        // 1. 标题识别
        if (remaining.startsWith("#### ")) {
            isHeader = true
            remaining = remaining.removePrefix("#### ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = primaryColor)) {
                renderFormattedInline(remaining, inlineCodeBg, inlineCodeColor)
            }
            return@forEachIndexed
        } else if (remaining.startsWith("### ")) {
            isHeader = true
            remaining = remaining.removePrefix("### ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = primaryColor)) {
                renderFormattedInline(remaining, inlineCodeBg, inlineCodeColor)
            }
            return@forEachIndexed
        } else if (remaining.startsWith("## ")) {
            isHeader = true
            remaining = remaining.removePrefix("## ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.5.sp, color = textColor)) {
                renderFormattedInline(remaining, inlineCodeBg, inlineCodeColor)
            }
            return@forEachIndexed
        } else if (remaining.startsWith("# ")) {
            isHeader = true
            remaining = remaining.removePrefix("# ")
            withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = textColor)) {
                renderFormattedInline(remaining, inlineCodeBg, inlineCodeColor)
            }
            return@forEachIndexed
        }

        // 2. 列表符美化
        if (remaining.startsWith("- ") || remaining.startsWith("* ")) {
            withStyle(SpanStyle(color = primaryColor, fontWeight = FontWeight.Bold)) {
                append("• ")
            }
            remaining = remaining.substring(2)
        }

        // 3. 行内富文本
        renderFormattedInline(remaining, inlineCodeBg, inlineCodeColor)
    }
}

private fun AnnotatedString.Builder.renderFormattedInline(
    text: String,
    codeBg: Color,
    codeColor: Color
) {
    var idx = 0
    while (idx < text.length) {
        when {
            // 加粗 **bold**
            text.startsWith("**", idx) -> {
                val end = text.indexOf("**", idx + 2)
                if (end < 0) {
                    append(text[idx])
                    idx++
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(idx + 2, end))
                    }
                    idx = end + 2
                }
            }

            // 行内代码 `code`
            text.startsWith("`", idx) -> {
                val end = text.indexOf("`", idx + 1)
                if (end < 0) {
                    append(text[idx])
                    idx++
                } else {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBg,
                            color = codeColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(" ${text.substring(idx + 1, end)} ")
                    }
                    idx = end + 1
                }
            }

            // 斜体 *italic*
            text.startsWith("*", idx) && idx + 1 < text.length && !text.startsWith("**", idx) -> {
                val end = text.indexOf("*", idx + 1)
                if (end < 0) {
                    append(text[idx])
                    idx++
                } else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(idx + 1, end))
                    }
                    idx = end + 1
                }
            }

            else -> {
                append(text[idx])
                idx++
            }
        }
    }
}
