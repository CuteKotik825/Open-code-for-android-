package com.pocketcode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketcode.session.SnapshotMeta

/** Действия, которым нужны системные окна Android (выбор файлов, права). */
class FileActions(
    val pickFiles: () -> Unit,
    val pickFolderToImport: () -> Unit,
    val exportZip: (String) -> Unit,
    val pickFolderAsProject: () -> Unit,
    val requestStorageAccess: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(vm: ChatController, actions: FileActions, onBack: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    var tpl by remember { mutableStateOf("empty") }
    var cloneUrl by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Проекты") },
                navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(com.pocketcode.ui.AppIcons.Back, contentDescription = "Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Проект — это папка, в которой работает агент. У каждого проекта своя история чатов.",
                style = MaterialTheme.typography.bodyMedium,
            )

            vm.projects.forEach { name ->
                val selected = name == vm.currentProject
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.selectProject(name)
                            if (!vm.running) onBack()
                        },
                ) {
                    Row(
                        Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                name + if (selected) "  (текущий)" else "",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                vm.projectPath(name),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        TextButton(onClick = { toDelete = name }) {
                            Text(if (vm.isExternal(name)) "Убрать" else "Удалить")
                        }
                    }
                }
            }

            vm.projectMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            // ----- файлы текущего проекта -----
            Text(
                "Файлы проекта «${vm.currentProject}»",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Добавь свои файлы в проект или забери результат работы агента.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = actions.pickFiles,
                enabled = !vm.projectBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Добавить файлы с телефона") }
            Button(
                onClick = actions.pickFolderToImport,
                enabled = !vm.projectBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Добавить папку целиком") }
            OutlinedButton(
                onClick = { actions.exportZip(vm.currentProject) },
                enabled = !vm.projectBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить проект в zip") }

            // ----- откат -----
            var showRollback by remember { mutableStateOf(false) }
            var toRestore by remember { mutableStateOf<SnapshotMeta?>(null) }
            OutlinedButton(
                onClick = {
                    vm.refreshSnapshots()
                    showRollback = !showRollback
                },
                enabled = !vm.projectBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (showRollback) "Скрыть откат" else "Откатить изменения агента") }
            if (showRollback) {
                if (vm.snapshotList.isEmpty()) {
                    Text("Снимков пока нет: они делаются автоматически перед каждой задачей агента.", style = MaterialTheme.typography.bodySmall)
                }
                vm.snapshotList.forEach { snap ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth().clickable { toRestore = snap },
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(snap.id)),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text("До задачи: " + snap.label.ifBlank { "(без названия)" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${snap.files} файлов", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            toRestore?.let { snap ->
                AlertDialog(
                    onDismissRequest = { toRestore = null },
                    title = { Text("Откатить проект?") },
                    text = {
                        Text(
                            "Файлы вернутся к состоянию до задачи «${snap.label.take(60)}». Всё, что изменено или создано после, будет удалено " +
                                "(папка .git не затрагивается). Перед откатом автоматически сделается ещё один снимок.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            vm.restoreSnapshot(snap.id)
                            toRestore = null
                        }) { Text("Откатить") }
                    },
                    dismissButton = { TextButton(onClick = { toRestore = null }) { Text("Отмена") } },
                )
            }

            // ----- где хранятся проекты -----
            Text(
                "Где хранятся проекты",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !vm.storageShared,
                    onClick = { vm.selectStorage(false) },
                    label = { Text("Внутри приложения") },
                )
                FilterChip(
                    selected = vm.storageShared,
                    onClick = { vm.selectStorage(true) },
                    label = { Text("Папка PocketCode") },
                )
            }
            Text(
                if (vm.storageShared) {
                    "Проекты лежат в памяти телефона, в папке PocketCode: их видно в любом файловом менеджере, " +
                        "туда можно просто копировать файлы."
                } else {
                    "Проекты скрыты в закрытой папке приложения, из других программ их не видно. " +
                        "Для свободного доступа переключись на папку PocketCode."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (!vm.storageGranted) {
                Text(
                    "Для папки PocketCode и подключения своих папок нужен доступ ко всем файлам.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = actions.requestStorageAccess,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Выдать доступ к файлам") }
            } else {
                Text("Доступ к файлам выдан.", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = actions.pickFolderAsProject,
                enabled = vm.storageGranted && !vm.projectBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Открыть папку телефона как проект") }

            // ----- справка -----
            var showHelp by remember { mutableStateOf(false) }
            TextButton(onClick = { showHelp = !showHelp }, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (showHelp) "Скрыть справку" else "Справка: что здесь что")
            }
            if (showHelp) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Проект", style = MaterialTheme.typography.titleSmall)
                        Text("Папка, в которой работает агент. Он видит и меняет только её. У каждого проекта своя история чатов.", style = MaterialTheme.typography.bodySmall)
                        Text("Где хранятся проекты", style = MaterialTheme.typography.titleSmall)
                        Text("«Внутри приложения» — закрытая папка, другие программы её не видят. «Папка PocketCode» — обычная папка в памяти телефона, её видно в файловом менеджере. Для неё нужен доступ ко всем файлам.", style = MaterialTheme.typography.bodySmall)
                        Text("Свои файлы", style = MaterialTheme.typography.titleSmall)
                        Text("«Добавить файлы» и «Добавить папку» копируют выбранное в текущий проект. «Открыть папку телефона как проект» подключает папку без копирования: агент работает прямо в ней. «Сохранить проект в zip» выгружает результат.", style = MaterialTheme.typography.bodySmall)
                        Text("Linux и bash", style = MaterialTheme.typography.titleSmall)
                        Text("bash — это инструмент, через который агент запускает команды. «bash Android» — простая оболочка телефона без python, git и node. «bash в Linux» — настоящий мини-Linux (Alpine) внутри приложения: там можно ставить любые программы командой apk add. Проект в нём лежит в папке /work. Телефон и система при этом не затрагиваются.", style = MaterialTheme.typography.bodySmall)
                        Text("Python", style = MaterialTheme.typography.titleSmall)
                        Text("Отдельный встроенный Python 3.11 только со стандартной библиотекой. Работает и без Linux. Если нужны pip-пакеты, используй Python внутри Linux.", style = MaterialTheme.typography.bodySmall)
                        Text("Файлы вручную", style = MaterialTheme.typography.titleSmall)
                        Text("Кнопка «Файлы» в чате открывает файлы проекта: можно читать, править, создавать и удалять.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ----- создать и клонировать -----
            Text("Новый проект", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Название") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Шаблон", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.pocketcode.PROJECT_TEMPLATES.forEach { t ->
                    androidx.compose.material3.FilterChip(selected = tpl == t.id, onClick = { tpl = t.id }, label = { Text(t.title) })
                }
            }
            Text(com.pocketcode.PROJECT_TEMPLATES.first { it.id == tpl }.about, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = {
                    vm.createProjectFromTemplate(newName, tpl)
                    if (vm.projectMessage == null) {
                        newName = ""
                        onBack()
                    }
                },
                enabled = newName.isNotBlank() && !vm.projectBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Создать") }

            Text("Клонировать из GitHub", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(
                value = cloneUrl,
                onValueChange = { cloneUrl = it },
                label = { Text("Адрес или владелец/репозиторий") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    vm.cloneProject(cloneUrl)
                    cloneUrl = ""
                },
                enabled = cloneUrl.isNotBlank() && !vm.projectBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (vm.projectBusy) "Подожди…" else "Клонировать") }
            Text(
                "Для приватных репозиториев добавь GitHub-токен в настройках.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    toDelete?.let { name ->
        val external = vm.isExternal(name)
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(if (external) "Убрать проект «$name» из списка?" else "Удалить проект «$name»?") },
            text = {
                Text(
                    if (external) "Папка и её файлы на телефоне останутся на месте. История чатов проекта будет удалена."
                    else "Файлы проекта и его история чатов будут удалены без возможности восстановления. Незапушенные коммиты пропадут.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(name)
                    toDelete = null
                }) { Text(if (external) "Убрать" else "Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}
