package com.nebulaai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Lightweight Markdown renderer for AI chat output.
 * Handles: code blocks, inline code, bold, italic, headers, bullet/numbered lists, horizontal rules.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    if (markdown.isBlank()) return

    val blocks = parseMarkdownBlocks(markdown)
    val codeBlockColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)

    Column(modifier = modifier.fillMaxWidth()) {
        for (block in blocks) {
            when (block) {
                is MdBlock.CodeBlock -> {
                    CodeBlock(
                        code = block.content,
                        language = block.language,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.labelLarge
                    }
                    Text(
                        text = renderInlineMarkdown(block.text.trimStart('#').trim()).toString(),
                        style = style,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                is MdBlock.ListItem -> {
                    val prefix = if (block.ordered) "${block.index}. " else "• "
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(prefix)
                            }
                            append(renderSpans(block.text, codeBlockColor))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                    )
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = renderInlineMarkdown(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                is MdBlock.HorizontalRule -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    )
                }
            }
        }
    }
}

// ─── Block-level parsing ──────────────────────────────

private sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class CodeBlock(val language: String, val content: String) : MdBlock()
    data class ListItem(val ordered: Boolean, val index: Int, val text: String) : MdBlock()
    object HorizontalRule : MdBlock()
}

private fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Code block
        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            if (i < lines.size) i++ // skip closing ```
            continue
        }

        // Horizontal rule
        if (line.trim().matches(Regex("^[-*_]{3,}$"))) {
            blocks.add(MdBlock.HorizontalRule)
            i++
            continue
        }

        // Heading
        val headingMatch = Regex("^(#{1,6})\\s+(.*)").matchEntire(line)
        if (headingMatch != null) {
            blocks.add(MdBlock.Heading(
                headingMatch.groupValues[1].length,
                headingMatch.groupValues[2],
            ))
            i++
            continue
        }

        // Ordered list
        val orderedMatch = Regex("^(\\d+)\\.\\s+(.*)").matchEntire(line)
        if (orderedMatch != null) {
            val idx = orderedMatch.groupValues[1].toIntOrNull() ?: 1
            blocks.add(MdBlock.ListItem(true, idx, orderedMatch.groupValues[2]))
            i++
            continue
        }

        // Unordered list
        val unorderedMatch = Regex("^[-*+]\\s+(.*)").matchEntire(line)
        if (unorderedMatch != null) {
            blocks.add(MdBlock.ListItem(false, 0, unorderedMatch.groupValues[1]))
            i++
            continue
        }

        // Paragraph — accumulate lines until blank or special
        if (line.isNotBlank()) {
            val paraLines = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank() &&
                !lines[i].trimStart().startsWith("```") &&
                !lines[i].trimStart().startsWith("#") &&
                !Regex("^[-*+]\\s+").containsMatchIn(lines[i]) &&
                !Regex("^\\d+\\.\\s+").containsMatchIn(lines[i]) &&
                !lines[i].trim().matches(Regex("^[-*_]{3,}$"))
            ) {
                paraLines.add(lines[i])
                i++
            }
            if (paraLines.isNotEmpty()) {
                blocks.add(MdBlock.Paragraph(paraLines.joinToString("\n")))
            }
            continue
        }

        i++
    }
    return blocks
}

// ─── Inline markdown rendering ─────────────────────────

@Composable
private fun renderInlineMarkdown(text: String): AnnotatedString {
    val codeBlockColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    return renderSpans(text, codeBlockColor)
}

private fun renderSpans(text: String, codeBgColor: androidx.compose.ui.graphics.Color): AnnotatedString {
    val boldRe = Regex("""\*\*(.+?)\*\*""")
    val italicRe = Regex("""\*(.+?)\*""")
    val codeRe = Regex("""`([^`]+)`""")

    return buildAnnotatedString {
        var remaining = text
        while (remaining.isNotEmpty()) {
            val boldM = boldRe.find(remaining)
            val italicM = italicRe.find(remaining)
            val codeM = codeRe.find(remaining)

            val next = listOfNotNull(boldM, italicM, codeM).minByOrNull { it.range.first }

            if (next == null) {
                append(remaining)
                break
            }

            if (next.range.first > 0) {
                append(remaining.substring(0, next.range.first))
            }

            when (next) {
                boldM -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(next.groupValues[1])
                }
                italicM -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(next.groupValues[1])
                }
                codeM -> withStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBgColor,
                )) {
                    append(" ${next.groupValues[1]} ")
                }
            }

            remaining = remaining.substring(next.range.last + 1)
        }
    }
}
