package com.pocketcode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_EDIT_BYTES = 1_000_000L

private sealed class Loaded {
    data class Text(val content: String) : Loaded()
    data class Refused(val reason: String) : Loaded()
}

private fun loadFile(f: File): Loaded {
    if (f.length() > MAX_EDIT_BYTES) return Loaded.Refused("Файл слишком большой для редактора (больше 1 МБ).")
    val bytes = f.readBytes()
    if (bytes.any { it == 0.toByte() }) return Loaded.Refused("Это не текстовый файл.")
    return Loaded.Text(String(bytes, Charsets.UTF_8))
}

private fun humanSize(n: Long): String = when {
    n < 1024 -> "$n Б"
    n < 1024 * 1024 -> "${n / 1024} КБ"
    else -> "${n / (1024 * 1024)} МБ"
}

/** Просмотр и правка файлов текущего проекта. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(root: File, projectName: String, onBack: () -> Unit, onStartHere: (File) -> Unit) {
    val scope = rememberCoroutineScope()
    var dir by remember { mutableStateOf(root) }
    var tick by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    var editing by remember { mutableStateOf<File?>(null) }
    var editorValue by remember { mutableStateOf(TextFieldValue("")) }
    var original by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var toRename by remember { mutableStateOf<File?>(null) }
    var renameTo by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var matchRange by remember { mutableStateOf<IntRange?>(null) }
    val vScroll = rememberScrollState()
    val density = LocalDensity.current
    val dark = isSystemInDarkTheme()
    val text = editorValue.text

    var toDelete by remember { mutableStateOf<File?>(null) }
    var newKind by remember { mutableStateOf<String?>(null) } // "file" | "dir"
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(dir, tick) {
        entries = withContext(Dispatchers.IO) {
            (dir.listFiles() ?: emptyArray())
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }

    fun rel(f: File): String = f.relativeTo(root).path.ifEmpty { "." }

    fun open(f: File) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { loadFile(f) } }
            r.onSuccess {
                when (it) {
                    is Loaded.Text -> {
                        editorValue = TextFieldValue(it.content)
                        original = it.content
                        editing = f
                        message = null
                        showSearch = false
                        matchRange = null
                    }
                    is Loaded.Refused -> message = "${f.name}: ${it.reason}"
                }
            }.onFailure { message = "Не удалось открыть: ${it.message}" }
        }
    }

    fun save() {
        val f = editing ?: return
        val content = text
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { f.writeText(content, Charsets.UTF_8) } }
            r.onSuccess {
                original = content
                message = "Сохранено"
            }.onFailure { message = "Не удалось сохранить: ${it.message}" }
        }
    }

    fun findNext() {
        val q = findText
        if (q.isEmpty()) return
        val from = (matchRange?.last?.plus(1)) ?: 0
        var idx = text.indexOf(q, from, ignoreCase = true)
        if (idx < 0) idx = text.indexOf(q, 0, ignoreCase = true)
        if (idx < 0) {
            matchRange = null
            message = "Не найдено: $q"
            return
        }
        matchRange = idx until idx + q.length
        message = null
        val line = text.substring(0, idx).count { it == '\n' }
        val px = with(density) { 18.sp.toPx() }
        scope.launch { vScroll.animateScrollTo((line * px - 120).coerceAtLeast(0f).toInt()) }
    }

    fun closeEditor() {
        editing = null
        showSearch = false
        matchRange = null
        confirmLeave = false
        tick++
    }

    fun goUp() {
        if (editing != null) {
            if (text != original) confirmLeave = true else closeEditor()
        } else if (dir.canonicalPath != root.canonicalPath) {
            dir.parentFile?.let { dir = it }
            message = null
        } else {
            onBack()
        }
    }

    if (previewFile != null) {
        PreviewScreen(previewFile!!, onBack = { previewFile = null })
        return
    }
    androidx.activity.compose.BackHandler { goUp() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        editing?.name ?: ("📁 $projectName/" + rel(dir).removePrefix(".")),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { TextButton(onClick = { goUp() }) { Text("Назад") } },
                actions = {
                    if (editing != null) {
                        val ext = editing!!.extension.lowercase()
                        TextButton(onClick = { showSearch = !showSearch }) { Text("Поиск") }
                        if (ext == "html" || ext == "htm" || ext == "svg") {
                            TextButton(onClick = {
                                save()
                                previewFile = editing
                            }) { Text("Просмотр") }
                        }
                        TextButton(onClick = { save() }, enabled = text != original) { Text("Сохранить") }
                    } else {
                        TextButton(onClick = { newKind = "file"; newName = "" }) { Text("+ Файл") }
                        TextButton(onClick = { newKind = "dir"; newName = "" }) { Text("+ Папка") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (editing != null) {
                if (showSearch) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = findText,
                                onValueChange = { findText = it; matchRange = null },
                                label = { Text("Найти") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { findNext() }, enabled = findText.isNotEmpty()) { Text("Дальше") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = replaceText,
                                onValueChange = { replaceText = it },
                                label = { Text("Заменить на") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    val n = Regex(Regex.escape(findText), RegexOption.IGNORE_CASE).findAll(text).count()
                                    if (n > 0) {
                                        editorValue = TextFieldValue(
                                            text.replace(Regex(Regex.escape(findText), RegexOption.IGNORE_CASE), Regex.escapeReplacement(replaceText)),
                                        )
                                        matchRange = null
                                    }
                                    message = "Заменено: $n"
                                },
                                enabled = findText.isNotEmpty(),
                            ) { Text("Все") }
                        }
                    }
                }
                val lines = remember(text) { text.count { it == '\n' } + 1 }
                val numbers = remember(lines) { (1..lines).joinToString("\n") }
                val ext = editing!!.extension.lowercase()
                val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface)
                Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                    Text(
                        numbers,
                        style = style.copy(color = MaterialTheme.colorScheme.outline),
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        BasicTextField(
                            value = editorValue,
                            onValueChange = { editorValue = it; matchRange = null },
                            textStyle = style,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            visualTransformation = remember(ext, dark, matchRange) { CodeTransformation(ext, dark, matchRange) },
                            modifier = Modifier.defaultMinSize(minWidth = 240.dp, minHeight = 300.dp).padding(end = 16.dp),
                        )
                    }
                }
            } else if (entries.isEmpty()) {
                if (dir.canonicalPath != root.canonicalPath) {
                    TextButton(onClick = { onStartHere(dir) }) { Text("Начать работу в этой папке") }
                }
                Text("Папка пуста.", modifier = Modifier.padding(16.dp))
            } else {
                if (dir.canonicalPath != root.canonicalPath) {
                    TextButton(onClick = { onStartHere(dir) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Начать работу в этой папке")
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.absolutePath }) { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { if (f.isDirectory) { dir = f; message = null } else open(f) }
                                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (if (f.isDirectory) "📁 " else "📄 ") + f.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (f.isFile) {
                                    Text(humanSize(f.length()), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            TextButton(onClick = { toRename = f; renameTo = f.name }) { Text("Имя") }
                            TextButton(onClick = { toDelete = f }) { Text("Удалить") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Выйти без сохранения?") },
            text = { Text("В файле есть несохранённые изменения.") },
            confirmButton = { TextButton(onClick = { closeEditor() }) { Text("Выйти") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Остаться") } },
        )
    }

    toRename?.let { f ->
        AlertDialog(
            onDismissRequest = { toRename = null },
            title = { Text("Переименовать") },
            text = {
                OutlinedTextField(value = renameTo, onValueChange = { renameTo = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    enabled = renameTo.isNotBlank() && renameTo != f.name,
                    onClick = {
                        val name = renameTo.trim()
                        toRename = null
                        if (name.contains('/') || name.contains('\\')) {
                            message = "Имя не должно содержать / или \\"
                        } else {
                            val target = File(f.parentFile, name)
                            message = when {
                                target.exists() -> "«$name» уже существует"
                                f.renameTo(target) -> null
                                else -> "Не удалось переименовать"
                            }
                            tick++
                        }
                    },
                ) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { toRename = null }) { Text("Отмена") } },
        )
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить «${f.name}»?") },
            text = { Text(if (f.isDirectory) "Папка будет удалена вместе со всем содержимым." else "Файл будет удалён без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }.getOrDefault(false)
                        }
                        message = if (ok) null else "Не удалось удалить ${f.name}"
                        tick++
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }

    newKind?.let { kind ->
        AlertDialog(
            onDismissRequest = { newKind = null },
            title = { Text(if (kind == "dir") "Новая папка" else "Новый файл") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Имя") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val name = newName.trim()
                        newKind = null
                        if (name.contains('/') || name.contains('\\') || name == "." || name == "..") {
                            message = "Имя не должно содержать / или \\"
                            return@TextButton
                        }
                        val target = File(dir, name)
                        scope.launch {
                            val err = withContext(Dispatchers.IO) {
                                runCatching {
                                    when {
                                        target.exists() -> "«$name» уже существует"
                                        kind == "dir" -> if (target.mkdirs()) null else "Не удалось создать папку"
                                        else -> { target.createNewFile(); null }
                                    }
                                }.getOrElse { it.message }
                            }
                            message = err
                            tick++
                            if (err == null && kind == "file") open(target)
                        }
                    },
                ) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { newKind = null }) { Text("Отмена") } },
        )
    }
}
