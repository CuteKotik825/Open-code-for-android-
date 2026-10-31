package com.pocketcode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Разбор правок агента по блокам: каждый блок можно откатить отдельно. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffScreen(vm: ChatController, onBack: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    val red = Color(0xFFE57373)
    val green = Color(0xFF81C784)
    Scaffold(topBar = {
        TopAppBar(title = { Text("Правки по блокам") }, navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(com.pocketcode.ui.AppIcons.Back, contentDescription = "Назад") } })
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            tick
            var cmpPath by remember { mutableStateOf("") }
            var cmpSnap by remember { mutableStateOf<Long?>(null) }
            var cmpText by remember { mutableStateOf<String?>(null) }
            var cmpMsg by remember { mutableStateOf("") }
            androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshSnapshots() }
            androidx.compose.runtime.LaunchedEffect(cmpPath, cmpSnap, tick) {
                val id = cmpSnap
                cmpText = null
                if (id != null && cmpPath.isNotBlank()) {
                    val t = vm.snapshotFileText(id, cmpPath.trim())
                    cmpText = t
                    cmpMsg = if (t == null) "В этом снимке такого файла нет." else ""
                } else cmpMsg = ""
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Сравнить файл со снимком", style = MaterialTheme.typography.titleSmall)
                    androidx.compose.material3.OutlinedTextField(value = cmpPath, onValueChange = { cmpPath = it }, singleLine = true, label = { Text("Путь к файлу, например src/main.py") }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        vm.snapshotList.take(15).forEach { s ->
                            androidx.compose.material3.FilterChip(selected = cmpSnap == s.id, onClick = { cmpSnap = s.id }, label = { Text(s.label.take(24), maxLines = 1) })
                        }
                    }
                    if (vm.snapshotList.isEmpty()) Text("Снимков пока нет, они создаются перед задачами агента.", style = MaterialTheme.typography.bodySmall)
                    if (cmpMsg.isNotBlank()) Text(cmpMsg, style = MaterialTheme.typography.bodySmall)
                    val old = cmpText
                    val cur = if (cmpPath.isBlank()) null else vm.readProjectFile(cmpPath.trim())
                    if (old != null && cur != null) {
                        val ops = diffLines(old.split("\n"), cur.split("\n"))
                        if (ops == null) Text("Файл слишком большой для сравнения.", style = MaterialTheme.typography.bodySmall)
                        else {
                            val hunks = hunkRanges(ops)
                            if (hunks.isEmpty()) Text("Файл совпадает со снимком.", style = MaterialTheme.typography.bodySmall)
                            hunks.forEachIndexed { hi, r ->
                                val from = maxOf(0, r.first - 2)
                                val to = minOf(ops.size - 1, r.last + 2)
                                Text("Блок ${hi + 1} из ${hunks.size}", style = MaterialTheme.typography.labelSmall)
                                ops.subList(from, to + 1).forEach { op ->
                                    val (prefix, color, text) = when (op) {
                                        is DOp.Del -> Triple("- ", red, op.line)
                                        is DOp.Ins -> Triple("+ ", green, op.line)
                                        is DOp.Eq -> Triple("  ", Color.Unspecified, op.line)
                                    }
                                    Text(prefix + text.take(160), color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                                TextButton(onClick = { vm.writeProjectFile(cmpPath.trim(), revertHunk(ops, r)); tick++ }) { Text("Вернуть версию из снимка (этот блок)") }
                            }
                        }
                    }
                }
            }
            if (vm.changes.isEmpty()) Text("Правок пока нет. После работы агента здесь появятся его изменения файлов.", style = MaterialTheme.typography.bodyMedium)
            // по одному разу на файл: сравниваем самое раннее «до» с текущим содержимым
            val firstByPath = LinkedHashMap<String, ChatController.FileChange>()
            vm.changes.forEach { if (it.path !in firstByPath) firstByPath[it.path] = it }
            firstByPath.forEach { (path, ch) ->
                val current = vm.readProjectFile(path)
                val before = ch.before ?: ""
                val ops = if (current == null) null else diffLines(before.split("\n"), current.split("\n"))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text((if (ch.before == null) "➕ " else "✎ ") + path, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            OutlinedButton(onClick = {
                                val i = vm.changes.indexOfFirst { it.path == path }
                                if (i >= 0) vm.revertChange(i)
                                tick++
                            }) { Text("Откатить файл") }
                        }
                        if (current == null) Text("Файл удалён или недоступен.", style = MaterialTheme.typography.bodySmall)
                        else if (ops == null) Text("Файл слишком большой для разбора по блокам, откат только целиком.", style = MaterialTheme.typography.bodySmall)
                        else {
                            val hunks = hunkRanges(ops)
                            if (hunks.isEmpty()) Text("Изменений относительно исходного нет.", style = MaterialTheme.typography.bodySmall)
                            hunks.forEachIndexed { hi, r ->
                                val from = maxOf(0, r.first - 2)
                                val to = minOf(ops.size - 1, r.last + 2)
                                Column(Modifier.fillMaxWidth()) {
                                    Text("Блок ${hi + 1} из ${hunks.size}", style = MaterialTheme.typography.labelSmall)
                                    ops.subList(from, to + 1).forEach { op ->
                                        val (prefix, color, text) = when (op) {
                                            is DOp.Del -> Triple("- ", red, op.line)
                                            is DOp.Ins -> Triple("+ ", green, op.line)
                                            is DOp.Eq -> Triple("  ", Color.Unspecified, op.line)
                                        }
                                        Text(prefix + text.take(160), color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                    }
                                    TextButton(onClick = { vm.writeProjectFile(path, revertHunk(ops, r)); tick++ }) { Text("Откатить этот блок") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
