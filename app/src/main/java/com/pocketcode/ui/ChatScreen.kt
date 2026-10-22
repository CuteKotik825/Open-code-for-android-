package com.pocketcode.ui

import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.launch
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
    onPickFolder: () -> Unit,
) {
    var showNewChat by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var profileMenu by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var showChanges by remember { mutableStateOf(false) }
    var showTodos by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf(-1) }
    var editText by remember { mutableStateOf("") }
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) vm.attachImage(uri) }
    val listState = rememberLazyListState()

    // Автопрокрутка вниз только если пользователь и так внизу; читая историю выше, он остаётся на месте
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (inProgress, canForward) -> if (inProgress || !canForward) stick = !canForward }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(vm.items.size, vm.items.lastOrNull()) {
        if (vm.items.isNotEmpty() && stick) {
            listState.scrollToItem(vm.items.lastIndex)
            listState.scrollBy(100000f)
        }
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
                    IconButton(onClick = { showNewChat = true }) {
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
            if (vm.profiles.size > 1) {
                val active = vm.profiles.firstOrNull { it.id == vm.activeProfileId }
                Box(Modifier.padding(horizontal = 4.dp)) {
                    TextButton(onClick = { profileMenu = true }, enabled = !vm.running) {
                        Text("🔑 ${active?.label ?: "?"} · ${active?.model.orEmpty()} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                        vm.profiles.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.label + " · " + p.model) },
                                onClick = {
                                    vm.switchProfile(p.id)
                                    profileMenu = false
                                },
                            )
                        }
                    }
                }
            }
            if (vm.contextUsed > 0) {
                val limit = vm.settings.contextTokens.coerceAtLeast(1)
                fun k(n: Int) = if (n >= 1000) "${n / 1000} тыс." else "$n"
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    LinearProgressIndicator(
                        progress = { (vm.contextUsed.toFloat() / limit).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Контекст ≈ ${k(vm.contextUsed)} из ${k(limit)} токенов (оценка)",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
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
                    if (!stick) {
                        androidx.compose.material3.SmallFloatingActionButton(
                            onClick = {
                                stick = true
                                scope.launch {
                                    listState.scrollToItem(vm.items.lastIndex)
                                    listState.scrollBy(100000f)
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        ) { Text("↓") }
                    }
                }
            }
            if (vm.queue.isNotEmpty() || (vm.running && input.isNotBlank())) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (vm.queue.isNotEmpty()) "В очереди задач: ${vm.queue.size}" else "Отправка ставит в очередь. «Уточнить сейчас» передаст агенту на следующем шаге",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (vm.queue.isNotEmpty()) {
                        if (!vm.running) TextButton(onClick = { vm.runQueue() }) { Text("Запустить") }
                        TextButton(onClick = { showQueue = true }) { Text("Очередь") }
                    }
                    if (vm.running && input.isNotBlank()) {
                        TextButton(onClick = {
                            vm.sendWhileRunning(input)
                            input = ""
                        }) { Text("Уточнить сейчас") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.FilterChip(
                    selected = vm.planMode,
                    onClick = { vm.planMode = !vm.planMode },
                    label = { Text("План") },
                )
                if (vm.todos.isNotEmpty()) {
                    val done = vm.todos.count { it.startsWith("[x]", true) }
                    TextButton(onClick = { showTodos = !showTodos }) { Text("Задачи $done/${vm.todos.size}") }
                }
                vm.lastPreview?.let { TextButton(onClick = { vm.previewUrl = it }) { Text("Превью") } }
                if (vm.changes.isNotEmpty()) {
                    TextButton(onClick = { showChanges = true }) { Text("Правки (${vm.changes.size})") }
                }
            }
            if (showTodos && vm.todos.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    vm.todos.forEach { t ->
                        val mark = when {
                            t.startsWith("[x]", true) -> "☑ "
                            t.startsWith("[~]") -> "▶ "
                            else -> "☐ "
                        }
                        Text(mark + t.removePrefix("[x]").removePrefix("[X]").removePrefix("[~]").removePrefix("[ ]").trim(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (showChanges) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showChanges = false },
                    title = { Text("Правки файлов (${vm.changes.size})") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            vm.changes.withIndex().reversed().forEach { (i, ch) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(ch.path, style = MaterialTheme.typography.bodyMedium)
                                        Text(if (ch.before == null) "создан" else "изменён", style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick = { vm.revertChange(i) }) { Text("Откатить") }
                                }
                            }
                            Text("Откат возвращает файл к состоянию до этой правки. Правки через bash здесь не видны, их откатывает снимок задачи.", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = { TextButton(onClick = { showChanges = false }) { Text("Закрыть") } },
                )
            }
            if (vm.running) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Агент работает…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { confirmStop = true }) { Text("Остановить") }
                }
            }
            if (confirmStop) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmStop = false },
                    title = { Text("Остановить агента?") },
                    text = { Text("Текущая задача прервётся. Изменения, которые он уже внёс, останутся (откатить можно в «Проекты» → «Откат»). Очередь сохранится.") },
                    confirmButton = { TextButton(onClick = { confirmStop = false; vm.stop() }) { Text("Остановить") } },
                    dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Продолжить работу") } },
                )
            }
            if (showQueue) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showQueue = false; editIndex = -1 },
                    title = { Text("Очередь задач (${vm.queue.size})") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (vm.queue.isEmpty()) Text("Пусто.")
                            vm.queue.forEachIndexed { i, t ->
                                Column {
                                    if (editIndex == i) {
                                        OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth())
                                        Row {
                                            TextButton(onClick = { vm.editQueue(i, editText); editIndex = -1 }) { Text("Сохранить") }
                                            TextButton(onClick = { editIndex = -1 }) { Text("Отмена") }
                                        }
                                    } else {
                                        Text("${i + 1}. ${t.take(300)}", style = MaterialTheme.typography.bodyMedium)
                                        Row {
                                            TextButton(onClick = { vm.moveInQueue(i, -1) }, enabled = i > 0) { Text("↑") }
                                            TextButton(onClick = { vm.moveInQueue(i, 1) }, enabled = i < vm.queue.lastIndex) { Text("↓") }
                                            TextButton(onClick = { editIndex = i; editText = t }) { Text("Правка") }
                                            TextButton(onClick = { vm.removeFromQueue(i) }) { Text("Убрать") }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showQueue = false; editIndex = -1 }) { Text("Закрыть") } },
                    dismissButton = if (vm.queue.isNotEmpty()) {
                        { TextButton(onClick = { vm.clearQueue() }) { Text("Очистить всё") } }
                    } else null,
                )
            }
            if (vm.pendingImages.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Картинок приложено: ${vm.pendingImages.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { vm.removeImage(vm.pendingImages.lastIndex) }) { Text("Убрать последнюю") }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                TextButton(onClick = { pickImage.launch("image/*") }, enabled = !vm.running) { Text("🖼") }
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
                            vm.enqueue(input)
                            input = ""
                        },
                        enabled = input.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "В очередь")
                    }
                } else {
                    IconButton(
                        onClick = {
                            vm.send(input)
                            input = ""
                        },
                        enabled = input.isNotBlank() || vm.pendingImages.isNotEmpty(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                    }
                }
            }
        }
    }

    if (showNewChat) {
        AlertDialog(
            onDismissRequest = { showNewChat = false },
            title = { Text("Новый чат: где работать?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.projects.forEach { name ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (name == vm.currentProject) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            modifier = Modifier.fillMaxWidth().clickable {
                                vm.newChatIn(name)
                                showNewChat = false
                            },
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text("📁 " + name + if (name == vm.currentProject) "  (текущий)" else "")
                                Text(
                                    vm.projectPath(name),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        label = { Text("Или новая папка-проект") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    TextButton(
                        enabled = newProjectName.isNotBlank(),
                        onClick = {
                            vm.createProject(newProjectName)
                            if (vm.projectMessage == null) {
                                vm.newChat()
                                newProjectName = ""
                                showNewChat = false
                            }
                        },
                    ) { Text("Создать и начать") }
                    vm.projectMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    TextButton(
                        onClick = {
                            showNewChat = false
                            onPickFolder()
                        },
                    ) { Text("Выбрать папку на телефоне…") }
                    if (!vm.storageGranted) {
                        Text(
                            "Для папок телефона нужен доступ ко всем файлам: Проекты и файлы → «Выдать доступ к файлам».",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showNewChat = false }) { Text("Отмена") } },
        )
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
                    Text(
                        (if (item.images > 0) "🖼 ×${item.images}\n" else "") + item.text,
                        modifier = Modifier.padding(12.dp),
                    )
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
