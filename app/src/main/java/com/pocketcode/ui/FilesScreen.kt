package com.pocketcode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
fun FilesScreen(root: File, projectName: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dir by remember { mutableStateOf(root) }
    var tick by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    var editing by remember { mutableStateOf<File?>(null) }
    var text by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }

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
                        text = it.content
                        original = it.content
                        editing = f
                        message = null
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

    fun closeEditor() {
        editing = null
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
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    )
                }
            } else if (entries.isEmpty()) {
                Text("Папка пуста.", modifier = Modifier.padding(16.dp))
            } else {
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
