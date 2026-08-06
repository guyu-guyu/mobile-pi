package dev.mobilepi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun BasicMarkdown(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Code -> {
                    if (block.language.isNotBlank()) {
                        Text(
                            block.language,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, bottom = 3.dp),
                        )
                    }
                    Text(
                        text = block.content,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                RoundedCornerShape(6.dp),
                            )
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }
                is MarkdownBlock.Text -> block.lines.forEach { line ->
                    val (content, kind) = classifyLine(line)
                    Text(
                        text = inlineMarkdown(content),
                        style = when (kind) {
                            LineKind.HEADING_ONE -> MaterialTheme.typography.titleLarge
                            LineKind.HEADING_TWO -> MaterialTheme.typography.titleMedium
                            LineKind.HEADING_THREE -> MaterialTheme.typography.titleSmall
                            LineKind.BODY -> MaterialTheme.typography.bodyMedium
                        },
                        fontWeight = if (kind == LineKind.BODY) null else FontWeight.SemiBold,
                        modifier = Modifier.padding(top = if (kind == LineKind.BODY) 1.dp else 7.dp),
                    )
                }
            }
        }
    }
}

private sealed interface MarkdownBlock {
    data class Text(val lines: List<String>) : MarkdownBlock
    data class Code(val language: String, val content: String) : MarkdownBlock
}

private enum class LineKind {
    HEADING_ONE,
    HEADING_TWO,
    HEADING_THREE,
    BODY,
}

private fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val result = mutableListOf<MarkdownBlock>()
    val textLines = mutableListOf<String>()
    val codeLines = mutableListOf<String>()
    var language = ""
    var inCode = false

    fun flushText() {
        if (textLines.isNotEmpty()) {
            result += MarkdownBlock.Text(textLines.toList())
            textLines.clear()
        }
    }

    fun flushCode() {
        result += MarkdownBlock.Code(language, codeLines.joinToString("\n"))
        codeLines.clear()
        language = ""
    }

    text.lines().forEach { line ->
        if (line.startsWith("```")) {
            if (inCode) {
                flushCode()
                inCode = false
            } else {
                flushText()
                language = line.removePrefix("```").trim()
                inCode = true
            }
        } else if (inCode) {
            codeLines += line
        } else {
            textLines += line
        }
    }
    if (inCode) flushCode() else flushText()
    return result
}

private fun classifyLine(line: String): Pair<String, LineKind> = when {
    line.startsWith("### ") -> line.removePrefix("### ") to LineKind.HEADING_THREE
    line.startsWith("## ") -> line.removePrefix("## ") to LineKind.HEADING_TWO
    line.startsWith("# ") -> line.removePrefix("# ") to LineKind.HEADING_ONE
    line.startsWith("- ") -> "- ${line.removePrefix("- ")}" to LineKind.BODY
    line.startsWith("* ") -> "- ${line.removePrefix("* ")}" to LineKind.BODY
    else -> line to LineKind.BODY
}

private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", index + 2)
                if (end > index + 2) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(index + 2, end))
                    pop()
                    index = end + 2
                } else {
                    append(text[index++])
                }
            }
            text[index] == '`' -> {
                val end = text.indexOf('`', index + 1)
                if (end > index + 1) {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = androidx.compose.ui.graphics.Color(0x16000000),
                        ),
                    )
                    append(text.substring(index + 1, end))
                    pop()
                    index = end + 1
                } else {
                    append(text[index++])
                }
            }
            text[index] == '*' -> {
                val end = text.indexOf('*', index + 1)
                if (end > index + 1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(index + 1, end))
                    pop()
                    index = end + 1
                } else {
                    append(text[index++])
                }
            }
            else -> append(text[index++])
        }
    }
}
