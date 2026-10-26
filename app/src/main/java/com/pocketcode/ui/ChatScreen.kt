package com.pocketcode.ui

import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketcode.session.UiItem

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    vm: ChatController,
    onOpenSettings: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenContext: () -> Unit = {},
    onExport: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onMenu: () -> Unit = {},
    onOpenOcSessions: () -> Unit = {},
) {
    var showNewChat by remember { mutableStateOf(false) }
    val imeOpen = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    var moreMenu by remember { mutableStateOf(false) }
    var dlg by remember { mutableStateOf("") }
    var notesDraft by remember { mutableStateOf("") }
    var searchQ by remember { mutableStateOf("") }
    var newTpl by remember { mutableStateOf("") }
    var modelDraft by remember { mutableStateOf("") }
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        val spoken = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) input = if (input.isBlank()) spoken else input.trimEnd() + " " + spoken
    }
    LaunchedEffect(vm.incomingText) {
        vm.incomingText?.let {
            input = if (input.isBlank()) it else input.trimEnd() + "\n" + it
            vm.incomingText = null
        }
    }

    // Автопрокрутка вниз только если пользователь и так внизу; читая историю выше, он остаётся на месте
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (inProgress, canForward) -> if (inProgress || !canForward) stick = !canForward }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Очень длинный последний элемент выше одного прыжка: докручиваем кусками, пока низ не достигнут
    suspend fun toBottom() {
        if (vm.items.isEmpty()) return
        listState.scrollToItem(vm.items.lastIndex)
        var n = 0
        while (listState.canScrollForward && n < 200) {
            listState.scrollBy(20000f)
            n++
        }
    }
    LaunchedEffect(vm.items.size, vm.items.lastOrNull()) {
        if (vm.items.isNotEmpty() && stick) {
            toBottom()
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
                navigationIcon = { IconButton(onClick = onMenu) { Text("☰", style = MaterialTheme.typography.titleLarge) } },
                actions = {
                    IconButton(onClick = { showNewChat = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Новый чат")
                    }
                    Box {
                        TextButton(onClick = { moreMenu = true }) { Text("⋮") }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(text = { Text("Терминал") }, onClick = { moreMenu = false; onOpenTerminal() })
                            DropdownMenuItem(text = { Text("Git") }, onClick = { moreMenu = false; onOpenGit() })
                            DropdownMenuItem(text = { Text("Что в контексте") }, onClick = { moreMenu = false; onOpenContext() })
                            DropdownMenuItem(text = { Text("Экспорт чата в Markdown") }, onClick = { moreMenu = false; onExport() })
                            DropdownMenuItem(text = { Text("Поиск по чату") }, onClick = { moreMenu = false; searchQ = ""; dlg = "search" })
                            DropdownMenuItem(text = { Text("Заметки проекта") }, onClick = { moreMenu = false; notesDraft = vm.notesText(); dlg = "notes" })
                            DropdownMenuItem(text = { Text("Мои шаблоны") }, onClick = { moreMenu = false; newTpl = ""; dlg = "tpl" })
                            DropdownMenuItem(text = { Text("Сменить модель") }, onClick = { moreMenu = false; modelDraft = vm.settings.model; if (vm.openCodeMode) vm.loadOcModels(); dlg = "model" })
                        }
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
                val limit = vm.effectiveContext.coerceAtLeast(1)
                val frac = (vm.contextUsed.toFloat() / limit).coerceIn(0f, 1f)
                fun k(n: Int) = if (n >= 1000) "${n / 1000}к" else "$n"
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenContext() }.padding(horizontal = 16.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = if (frac > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "  ${k(vm.contextUsed)}/${k(limit)}" + (if (vm.settings.ecoMode) " · эко" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (vm.items.isEmpty()) {
                    EmptyHint(vm.workspacePath) { input = it }
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
                                scope.launch { toBottom() }
                            },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        ) { Text("↓") }
                    }
                }
            }
            if (vm.queue.isNotEmpty() || (vm.running && input.isNotBlank() && !imeOpen)) {
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
                androidx.compose.material3.FilterChip(
                    selected = vm.openCodeMode,
                    onClick = { vm.openCodeMode = !vm.openCodeMode },
                    label = { Text("OpenCode") },
                    modifier = Modifier.padding(start = 6.dp),
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
            if (vm.openCodeMode) {
                var ocOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { ocOpen = !ocOpen }) { Text(if (ocOpen) "OpenCode ▾" else "OpenCode ▸") }
                    if (ocOpen) {
                        TextButton(onClick = { modelDraft = vm.ocModel; vm.loadOcModels(); dlg = "ocmodel" }) {
                            Text("🧠 " + vm.ocModel.ifBlank { "по умолчанию" }.take(22), maxLines = 1)
                        }
                        TextButton(onClick = onOpenOcSessions) { Text("📂 Диалоги", maxLines = 1) }
                    } else {
                        Text(vm.ocModel.ifBlank { "по умолчанию" }.take(26), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
                if (ocOpen) Text(
                    (if (vm.ocSessionId != null) vm.ocSessionTitle.take(40) else "новая беседа") + (if (vm.ocActiveAccount.isNotEmpty()) " · " + vm.ocActiveAccount else ""),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
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
            androidx.compose.material3.Surface(
                tonalElevation = 3.dp,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
                    Column {
                        IconButton(onClick = { pickImage.launch("image/*") }, enabled = !vm.running) { Text("🖼") }
                    }
                    androidx.compose.material3.TextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Опиши задачу…") },
                        maxLines = 6,
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                    if (input.isBlank() && vm.pendingImages.isEmpty()) {
                        IconButton(onClick = {
                            val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Говори задачу")
                            }
                            try { voice.launch(i) } catch (e: Exception) {
                                android.widget.Toast.makeText(ctx, "На телефоне нет сервиса распознавания речи", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }) { Text("🎤") }
                    } else {
                        androidx.compose.material3.FilledIconButton(
                            onClick = {
                                if (vm.running) vm.enqueue(input) else vm.send(input)
                                input = ""
                            },
                            enabled = input.isNotBlank() || (!vm.running && vm.pendingImages.isNotEmpty()),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = if (vm.running) "В очередь" else "Отправить")
                        }
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

    if (dlg == "search") {
        val q = searchQ.trim()
        val hits = if (q.length < 2) emptyList() else vm.items.mapIndexedNotNull { i, it ->
            val t = when (it) {
                is UiItem.User -> it.text
                is UiItem.Assistant -> it.text
                is UiItem.Tool -> it.description
                is UiItem.Error -> it.text
                is UiItem.Notice -> it.text
            }
            val p = t.indexOf(q, ignoreCase = true)
            if (p < 0) null else i to t.substring(maxOf(0, p - 30), minOf(t.length, p + 70)).replace("\n", " ")
        }
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Поиск по чату") },
            text = {
                Column {
                    OutlinedTextField(value = searchQ, onValueChange = { searchQ = it }, singleLine = true, label = { Text("Что искать") }, modifier = Modifier.fillMaxWidth())
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        if (q.length >= 2 && hits.isEmpty()) Text("Ничего не найдено", style = MaterialTheme.typography.bodySmall)
                        hits.take(50).forEach { (i, s) ->
                            TextButton(onClick = { dlg = ""; scope.launch { listState.scrollToItem(i) } }, modifier = Modifier.fillMaxWidth()) {
                                Text("${i + 1}. $s", maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = "" }) { Text("Закрыть") } },
        )
    }
    if (dlg == "notes") {
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Заметки проекта") },
            text = {
                Column {
                    Text("Файл .pocketcode/NOTES.md. Агент видит эти заметки в каждом чате этого проекта.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = notesDraft, onValueChange = { notesDraft = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp))
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveNotes(notesDraft); dlg = "" }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { dlg = "" }) { Text("Отмена") } },
        )
    }
    if (dlg == "tpl") {
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Мои шаблоны") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (vm.promptTemplates.isEmpty()) Text("Пока пусто. Добавь фразу, которую часто пишешь.", style = MaterialTheme.typography.bodySmall)
                    vm.promptTemplates.forEachIndexed { i, t ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            TextButton(onClick = { input = if (input.isBlank()) t else input.trimEnd() + "\n" + t; dlg = "" }, modifier = Modifier.weight(1f)) {
                                Text(t, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                            }
                            TextButton(onClick = { vm.removeTemplate(i) }) { Text("✕") }
                        }
                    }
                    OutlinedTextField(value = newTpl, onValueChange = { newTpl = it }, label = { Text("Новый шаблон") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { vm.addTemplate(newTpl); newTpl = "" }, enabled = newTpl.isNotBlank()) { Text("Добавить") }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = "" }) { Text("Закрыть") } },
        )
    }
    if (dlg == "ocmodel") {
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Модель OpenCode") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = modelDraft, onValueChange = { modelDraft = it }, singleLine = true, label = { Text("провайдер/модель, пусто — по умолчанию") }, modifier = Modifier.fillMaxWidth())
                    if (vm.ocModelsBusy) Text("Загружаю список…", style = MaterialTheme.typography.labelSmall)
                    vm.ocModelList.take(80).forEach { m ->
                        TextButton(onClick = { vm.setOcModelValue(m); dlg = "" }, modifier = Modifier.fillMaxWidth()) { Text(m, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.setOcModelValue(modelDraft.trim()); dlg = "" }) { Text("Применить") } },
            dismissButton = { TextButton(onClick = { dlg = "" }) { Text("Отмена") } },
        )
    }
    if (dlg == "model") {
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Сменить модель") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Меняется у активного профиля, сразу для следующего запроса.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = modelDraft, onValueChange = { modelDraft = it }, singleLine = true, label = { Text("Модель") }, modifier = Modifier.fillMaxWidth())
                    if (vm.openCodeMode) {
                        Text("Модели OpenCode (нажми, чтобы выбрать):", style = MaterialTheme.typography.labelSmall)
                        vm.ocModelList.take(60).forEach { m ->
                            TextButton(onClick = { vm.setOcModelValue(m); dlg = "" }, modifier = Modifier.fillMaxWidth()) { Text(m, modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.setModelNow(modelDraft); dlg = "" }) { Text("Применить") } },
            dismissButton = { TextButton(onClick = { dlg = "" }) { Text("Отмена") } },
        )
    }

    vm.approval?.let { request ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Разрешить: ${request.tool}?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val red = androidx.compose.ui.graphics.Color(0xFFE57373)
                    val green = androidx.compose.ui.graphics.Color(0xFF81C784)
                    dangerNote(request.description)?.let {
                        Text("⚠ $it", color = red, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            request.description.lines().forEachIndexed { i, line ->
                                if (i > 0) append("\n")
                                val color = when {
                                    line.startsWith("- ") -> red
                                    line.startsWith("+ ") -> green
                                    else -> null
                                }
                                if (color != null) withStyle(androidx.compose.ui.text.SpanStyle(color = color)) { append(line) } else append(line)
                            }
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    if (request.tool == "write_file" || request.tool == "edit_file") {
                        TextButton(onClick = { vm.allowEditsThisRun() }) { Text("Принимать все правки до конца задачи") }
                    }
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EmptyHint(workspacePath: String, onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Что будем делать?", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Агент читает и правит файлы, запускает команды и работает с git прямо на телефоне.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "🔍 Разберись в проекте" to "Изучи проект и коротко опиши его структуру и как его запускать.",
                "🐞 Найди ошибки" to "Проверь проект на ошибки и исправь найденное. Перед правками перечисли, что собираешься менять.",
                "🧪 Напиши тесты" to "Напиши тесты для основных функций проекта и запусти их.",
                "🤖 Telegram-бот" to "Создай простого Telegram-бота на aiogram 3 с командами /start и /help. Токен читай из переменной окружения BOT_TOKEN.",
                "🌐 Сайт" to "Сделай простую адаптивную страницу index.html и открой превью.",
                "📝 README" to "Напиши README.md для этого проекта.",
            ).forEach { (title, text) ->
                androidx.compose.material3.SuggestionChip(onClick = { onPick(text) }, label = { Text(title) })
            }
        }
        Text(
            "Рабочая папка: $workspacePath",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

@Composable
private fun ItemView(item: UiItem) {
    when (item) {
        is UiItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp),
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
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        (if (expanded) "▾ " else "▸ ") + toolIcon(item.name) + " " + item.name + "  " + item.description.trim(),
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
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 12.dp),
        )
        is UiItem.Error -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(item.text, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

private fun toolIcon(name: String): String = when (name) {
    "read_file", "list_dir" -> "📄"
    "write_file", "edit_file" -> "✏️"
    "bash", "bg" -> "⌨️"
    "python" -> "🐍"
    "git", "github" -> "🌿"
    "grep", "code_search" -> "🔍"
    "web_search", "web_fetch" -> "🌐"
    "run_tests" -> "🧪"
    "task", "spawn", "worker_result" -> "🧩"
    "todo", "present_plan" -> "📋"
    else -> "🔧"
}


/** Предупреждение об опасной команде в окне подтверждения. */
private fun dangerNote(desc: String): String? {
    val d = desc.lowercase()
    val rules = listOf(
        Regex("""rm\s+(-\w*r\w*f|-\w*f\w*r|-r\s+-f|-f\s+-r)""") to "Удаление файлов без возврата (rm -rf).",
        Regex("""\bdd\s+if=""") to "Команда dd может затереть диск или раздел.",
        Regex("""\bmkfs""") to "Форматирование раздела.",
        Regex("""chmod\s+-r\s+777""") to "Открытие прав на всё для всех (chmod -R 777).",
        Regex("""git\s+push\s+.*(--force|-f\b)""") to "Принудительная отправка в git затирает чужую историю.",
        Regex("""git\s+reset\s+--hard""") to "Сброс git --hard теряет несохранённые правки.",
        Regex("""git\s+clean\s+-\w*f""") to "git clean удаляет неотслеживаемые файлы.",
        Regex("""(curl|wget)[^|\n]*\|\s*(sudo\s+)?(sh|bash)""") to "Скачивание и запуск скрипта из сети без просмотра.",
        Regex("""drop\s+(table|database)""") to "Удаление таблицы или базы данных.",
        Regex(""">\s*/dev/sd|:\(\)\s*\{""") to "Опасная системная команда.",
    )
    return rules.firstOrNull { it.first.containsMatchIn(d) }?.second
}
