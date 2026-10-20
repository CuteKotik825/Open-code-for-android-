package com.pocketcode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketcode.session.UiItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatController,
    onOpenSettings: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(vm.items.size, vm.items.lastOrNull()) {
        if (vm.items.isNotEmpty()) listState.scrollToItem(vm.items.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextButton(onClick = onOpenProjects) {
                        Text("📁 " + vm.currentProject, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    TextButton(onClick = onOpenFiles) { Text("Файлы") }
                    IconButton(onClick = { vm.newChat() }) {
                        Icon(Icons.Filled.Add, contentDescription = "Новый чат")
                    }
                    IconButton(onClick = onOpenSessions) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "История")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (vm.items.isEmpty()) {
                    EmptyHint(vm.workspacePath)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(vm.items, key = { i, _ -> i }) { _, item -> ItemView(item) }
                    }
                }
            }
            if (vm.queue.isNotEmpty() || (vm.running && input.isNotBlank())) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (vm.queue.isNotEmpty()) "В очереди задач: ${vm.queue.size}" else "Сообщение уйдёт агенту на следующем шаге",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (vm.queue.isNotEmpty()) {
                        TextButton(onClick = { vm.clearQueue() }) { Text("Очистить") }
                    }
                    if (vm.running && input.isNotBlank()) {
                        TextButton(onClick = {
                            vm.enqueue(input)
                            input = ""
                        }) { Text("В очередь") }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Опиши задачу…") },
                    maxLines = 6,
                )
                Spacer(Modifier.width(8.dp))
                if (vm.running) {
                    IconButton(
                        onClick = {
                            vm.sendWhileRunning(input)
                            input = ""
                        },
                        enabled = input.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить агенту сейчас")
                    }
                    Button(onClick = { vm.stop() }) { Text("Стоп") }
                } else {
                    IconButton(
                        onClick = {
                            vm.send(input)
                            input = ""
                        },
                        enabled = input.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                    }
                }
            }
        }
    }

    vm.approval?.let { request ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Разрешить: ${request.tool}?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        request.description,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.answerApproval(true) }) { Text("Разрешить") }
            },
            dismissButton = {
                TextButton(onClick = { vm.answerApproval(false) }) { Text("Отклонить") }
            },
        )
    }
}

@Composable
private fun EmptyHint(workspacePath: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Опиши задачу, и агент возьмётся за работу.", style = MaterialTheme.typography.titleMedium)
        Text(
            "Он читает и правит файлы, запускает команды и работает с git в рабочей папке. " +
                "Например: «склонируй github.com/user/repo и исправь ошибку в README».",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Рабочая папка:\n$workspacePath",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun ItemView(item: UiItem) {
    when (item) {
        is UiItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                SelectionContainer {
                    Text(item.text, modifier = Modifier.padding(12.dp))
                }
            }
        }
        is UiItem.Assistant -> SelectionContainer {
            MarkdownText(item.text, modifier = Modifier.fillMaxWidth())
        }
        is UiItem.Tool -> {
            var expanded by remember { mutableStateOf(false) }
            val output = item.output
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (item.isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(
                        (if (expanded) "▾ " else "▸ ") + item.name + "  " + item.description.trim(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        output == null -> Text(
                            "выполняется…",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        output.isBlank() -> {}
                        expanded -> SelectionContainer {
                            Text(
                                if (output.length > 6000) output.take(6000) + "\n… (показано 6000 из ${output.length} символов)" else output,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        else -> Text(
                            output.trim().lines().filter { it.isNotBlank() }.take(2).joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        is UiItem.Notice -> Text(
            item.text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
        is UiItem.Error -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(item.text, modifier = Modifier.padding(12.dp))
            }
        }
    }
}
