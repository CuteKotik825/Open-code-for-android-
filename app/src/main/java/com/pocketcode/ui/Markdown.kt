package com.pocketcode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Блоки Markdown, которые умеем показывать. */
private sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Code(val code: String) : MdBlock()
    data class Item(val indent: Int, val marker: String, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Table(val lines: List<String>) : MdBlock()
    object Rule : MdBlock()
}

private val headingRe = Regex("^(#{1,6})\\s+(.*)$")
private val itemRe = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
private val ruleRe = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

private fun parseBlocks(text: String): List<MdBlock> {
    val lines = text.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MdBlock>()
    val para = mutableListOf<String>()

    fun flushParagraph() {
        if (para.isNotEmpty()) {
            blocks += MdBlock.Paragraph(para.joinToString("\n"))
            para.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code += lines[i]
                    i++
                }
                blocks += MdBlock.Code(code.joinToString("\n"))
            }
            line.isBlank() -> flushParagraph()
            ruleRe.matches(line) -> {
                flushParagraph()
                blocks += MdBlock.Rule
            }
            headingRe.matches(line) -> {
                flushParagraph()
                val m = headingRe.find(line)!!
                blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim())
            }
            trimmed.startsWith(">") -> {
                flushParagraph()
                val quote = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quote += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                    i++
                }
                i--
                blocks += MdBlock.Quote(quote.joinToString("\n"))
            }
            trimmed.startsWith("|") -> {
                flushParagraph()
                val table = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    table += lines[i]
                    i++
                }
                i--
                blocks += MdBlock.Table(table)
            }
            itemRe.matches(line) -> {
                flushParagraph()
                val m = itemRe.find(line)!!
                val marker = m.groupValues[2].let { if (it[0].isDigit()) it else "•" }
                blocks += MdBlock.Item(m.groupValues[1].length / 2, marker, m.groupValues[3])
            }
            else -> para += line
        }
        i++
    }
    flushParagraph()
    return blocks
}

/** Разбор строчной разметки: `код`, **жирный**, *курсив*, ~~зачёркнутый~~, [текст](ссылка). */
private fun AnnotatedString.Builder.appendInline(s: String, codeBg: Color, linkColor: Color) {
    var i = 0
    val n = s.length
    while (i < n) {
        val c = s[i]
        when {
            c == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(s.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(c); i++
                }
            }
            s.startsWith("**", i) || s.startsWith("__", i) -> {
                val mark = s.substring(i, i + 2)
                val end = s.indexOf(mark, i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInline(s.substring(i + 2, end), codeBg, linkColor)
                    }
                    i = end + 2
                } else {
                    append(mark); i += 2
                }
            }
            s.startsWith("~~", i) -> {
                val end = s.indexOf("~~", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        appendInline(s.substring(i + 2, end), codeBg, linkColor)
                    }
                    i = end + 2
                } else {
                    append("~~"); i += 2
                }
            }
            (c == '*' || c == '_') && i + 1 < n && !s[i + 1].isWhitespace() &&
                (c == '*' || i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                var end = s.indexOf(c, i + 1)
                // для '_' закрывающий символ не должен быть внутри слова (snake_case)
                while (end > 0 && c == '_' && end + 1 < n && s[end + 1].isLetterOrDigit()) {
                    end = s.indexOf(c, end + 1)
                }
                if (end > i + 1 && !s[end - 1].isWhitespace()) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInline(s.substring(i + 1, end), codeBg, linkColor)
                    }
                    i = end + 1
                } else {
                    append(c); i++
                }
            }
            c == '[' -> {
                val close = s.indexOf("](", i + 1)
                val paren = if (close > 0) s.indexOf(')', close + 2) else -1
                if (close > i && paren > close) {
                    val label = s.substring(i + 1, close)
                    val url = s.substring(close + 2, paren).trim()
                    val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    withLink(LinkAnnotation.Url(url, style)) { append(label) }
                    i = paren + 1
                } else {
                    append(c); i++
                }
            }
            else -> {
                append(c); i++
            }
        }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(text) { parseBlocks(text) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> Text(
                    remember(block.text, codeBg, linkColor) {
                        buildAnnotatedString { appendInline(block.text, codeBg, linkColor) }
                    },
                )
                is MdBlock.Heading -> Text(
                    remember(block.text, codeBg, linkColor) {
                        buildAnnotatedString { appendInline(block.text, codeBg, linkColor) }
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = when (block.level) {
                        1 -> 22.sp
                        2 -> 19.sp
                        3 -> 17.sp
                        else -> 16.sp
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .background(codeBg, MaterialTheme.shapes.small)
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(block.code, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                is MdBlock.Item -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
                    Text(block.marker, modifier = Modifier.width(22.dp))
                    Text(
                        remember(block.text, codeBg, linkColor) {
                            buildAnnotatedString { appendInline(block.text, codeBg, linkColor) }
                        },
                    )
                }
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline),
                    )
                    Text(
                        remember(block.text, codeBg, linkColor) {
                            buildAnnotatedString { appendInline(block.text, codeBg, linkColor) }
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                is MdBlock.Table -> Box(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Text(block.lines.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
}
