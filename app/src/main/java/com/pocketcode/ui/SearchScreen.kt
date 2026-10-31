package com.pocketcode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Поиск текста по всем файлам проекта. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: ChatController, onBack: () -> Unit, onPicked: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    var matchCase by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<ChatController.SearchHit>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Поиск · ${vm.currentProject}") }, navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Что искать") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = regex, onClick = { regex = !regex }, label = { Text("Регулярное выражение") })
                FilterChip(selected = matchCase, onClick = { matchCase = !matchCase }, label = { Text("Учитывать регистр") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.AssistChip(onClick = { query = "TODO|FIXME|XXX"; regex = true; matchCase = true }, label = { Text("TODO / FIXME") })
            }
            Button(onClick = {
                busy = true; msg = "Ищу…"
                scope.launch {
                    val r = vm.searchProject(query, regex, matchCase)
                    results = r.hits
                    msg = r.message
                    busy = false
                }
            }, enabled = query.isNotBlank() && !busy) { Text("Найти") }
            if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.labelMedium)
            Text("Нажми на результат: ссылка на файл попадёт в поле ввода чата.", style = MaterialTheme.typography.labelSmall)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(results) { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.incomingText = "@${h.path} (строка ${h.line})"; onPicked() }, modifier = Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("${h.path}:${h.line}", style = MaterialTheme.typography.labelMedium)
                                Text(h.text.trim().take(140), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2)
                            }
                        }
                        TextButton(onClick = { vm.incomingText = "Исправь в @${h.path} (строка ${h.line}): ${h.text.trim().take(200)}"; onPicked() }) { Text("Агенту") }
                    }
                }
            }
        }
    }
}
