package com.apkagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * 简易 Markdown 渲染器：支持代码块、行内代码、粗体、斜体、标题、无序列表、引用、分隔线。
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeText = MaterialTheme.colorScheme.onSurface
    Column(modifier = modifier.fillMaxWidth()) {
        val blocks = splitBlocks(markdown)
        blocks.forEach { block ->
            when (block) {
                is Block.Code -> {
                    Text(
                        text = block.code,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(codeBg)
                            .padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = codeText
                    )
                }
                is Block.Line -> {
                    Text(
                        text = renderInline(block.text),
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
                is Block.Blank -> { /* skip */ }
            }
        }
    }
}

private sealed class Block {
    data class Code(val code: String) : Block()
    data class Line(val text: String) : Block()
    object Blank : Block()
}

private fun splitBlocks(md: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = md.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.startsWith("```")) {
            val fence = line.substring(3).trim()
            val sb = StringBuilder()
            i++
            while (i < lines.size && !lines[i].startsWith("```")) {
                sb.appendLine(lines[i])
                i++
            }
            i++ // skip closing fence
            out.add(Block.Code(sb.toString().trimEnd('\n')))
        } else if (line.isBlank()) {
            out.add(Block.Blank)
            i++
        } else {
            out.add(Block.Line(line))
            i++
        }
    }
    return out
}

private fun renderInline(text: String): AnnotatedString = buildAnnotatedString {
    val heading = when {
        text.startsWith("### ") -> 3
        text.startsWith("## ") -> 2
        text.startsWith("# ") -> 1
        else -> 0
    }
    var s = text
    if (heading > 0) s = s.substring(heading + 1)
    val weight = if (heading > 0) FontWeight.Bold else null
    val size = when (heading) {
        1 -> 20f; 2 -> 17f; 3 -> 15f; else -> 0f
    }

    // 处理列表标记 / 引用 / 分隔线
    val bullet = s.startsWith("- ") || s.startsWith("* ") || s.startsWith("• ")
    val quote = s.startsWith("> ")
    val hr = s == "---" || s == "***"
    if (hr) { append("────────────"); return@buildAnnotatedString }
    if (bullet) s = "•  " + s.substring(2)
    if (quote) s = s.substring(2)

    // 行内：**bold** *italic* `code`
    var idx = 0
    val baseStyle = SpanStyle(
        fontWeight = weight,
        fontSize = if (size > 0) size.sp else SpanStyle().fontSize
    )
    withStyle(baseStyle) {
        while (idx < s.length) {
            when {
                s.startsWith("**", idx) -> {
                    val end = s.indexOf("**", idx + 2)
                    if (end < 0) { append(s[idx]); idx++; continue }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(idx + 2, end)) }
                    idx = end + 2
                }
                s.startsWith("`", idx) -> {
                    val end = s.indexOf("`", idx + 1)
                    if (end < 0) { append(s[idx]); idx++; continue }
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33FFFFFF))) {
                        append(s.substring(idx + 1, end))
                    }
                    idx = end + 1
                }
                s.startsWith("*", idx) && idx + 1 < s.length && !s.startsWith("**", idx) -> {
                    val end = s.indexOf("*", idx + 1)
                    if (end < 0) { append(s[idx]); idx++; continue }
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(idx + 1, end)) }
                    idx = end + 1
                }
                else -> { append(s[idx]); idx++ }
            }
        }
    }
}
