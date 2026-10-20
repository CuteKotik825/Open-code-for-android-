package com.pocketcode.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

private val KEYWORDS = setOf(
    "def", "class", "import", "from", "return", "if", "elif", "else", "for", "while", "in", "not", "and", "or", "is",
    "None", "True", "False", "try", "except", "finally", "with", "as", "lambda", "pass", "break", "continue", "yield",
    "raise", "async", "await", "global", "function", "const", "let", "var", "new", "this", "typeof", "export", "default",
    "switch", "case", "do", "throw", "catch", "null", "undefined", "true", "false", "extends", "implements", "interface",
    "public", "private", "protected", "static", "final", "void", "int", "long", "double", "float", "boolean", "string",
    "fun", "val", "object", "when", "package", "override", "data", "sealed", "suspend", "companion", "enum", "struct",
    "fn", "mut", "impl", "use", "pub", "match", "type", "func", "go", "defer", "select", "chan", "range", "include",
    "echo", "fi", "then", "done", "esac", "select",
)

private val CODE_EXT = setOf(
    "py", "js", "ts", "jsx", "tsx", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "go", "rs", "sh", "bash",
    "rb", "php", "swift", "json", "yml", "yaml", "toml", "html", "htm", "xml", "css", "gradle", "sql", "lua",
)
private val HASH_COMMENT_EXT = setOf("py", "sh", "bash", "rb", "yml", "yaml", "toml", "gradle", "lua")

class CodeTransformation(
    private val ext: String,
    private val dark: Boolean,
    private val match: IntRange?,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlight(text.text), OffsetMapping.Identity)

    private fun highlight(src: String): AnnotatedString = buildAnnotatedString {
        append(src)
        if (ext in CODE_EXT && src.length <= 150_000) {
            val cComment = Color(if (dark) 0xFF7C9A7C else 0xFF5E7E5E)
            val str = Color(if (dark) 0xFFE0A56B else 0xFFA05A00)
            val num = Color(if (dark) 0xFF7FB6FF else 0xFF1A5DB0)
            val kw = Color(if (dark) 0xFFC792EA else 0xFF8E3DB5)
            val hash = if (ext in HASH_COMMENT_EXT) "|#[^\\n]*" else ""
            val tag = if (ext == "html" || ext == "htm" || ext == "xml") "|<!--[\\s\\S]*?-->" else ""
            val re = Regex("(//[^\\n]*$hash|/\\*[\\s\\S]*?\\*/$tag)|(\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*')|(\\b\\d+(?:\\.\\d+)?\\b)|(\\b[A-Za-z_][A-Za-z_0-9]*\\b)")
            for (m in re.findAll(src)) {
                val color = when {
                    m.groups[1] != null -> cComment
                    m.groups[2] != null -> str
                    m.groups[3] != null -> num
                    m.groups[4] != null && m.value in KEYWORDS -> kw
                    else -> continue
                }
                addStyle(SpanStyle(color = color), m.range.first, m.range.last + 1)
            }
        }
        match?.let {
            if (it.first >= 0 && it.last < src.length) {
                addStyle(SpanStyle(background = Color(0xFFFFD54F), color = Color.Black), it.first, it.last + 1)
            }
        }
    }
}
