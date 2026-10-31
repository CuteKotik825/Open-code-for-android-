package com.pocketcode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RemoteScreen(vm: ChatController, onBack: () -> Unit) {
    var repoName by remember { mutableStateOf("pocketcode-sync") }
    var repo by remember { mutableStateOf(vm.syncRepo) }
    var task by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    val fmt = remember { java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()) }
    LaunchedEffect(Unit) { vm.refreshRemote() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Синхронизация и пульт") },
            navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } },
            actions = { TextButton(onClick = { vm.refreshRemote() }, enabled = !vm.syncBusy) { Text("Обновить") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Проекты и задачи передаются через твой приватный репозиторий на GitHub, поэтому телефоны могут быть в разных сетях и даже в разных странах. Своего сервера не нужно. На втором телефоне поставь то же приложение, тот же GitHub-токен (☰ → Настройки) и тот же адрес репозитория.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(vm.syncStatus.ifBlank { "Ещё не синхронизировалось." }, style = MaterialTheme.typography.labelMedium)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("1. Репозиторий", style = MaterialTheme.typography.titleSmall)
                    Text("Нужен только приватный (private). Токену GitHub нужно право repo. Не клади сюда проекты с чужими секретами: файлы .env, ключи (*.pem, *.key) и папки node_modules, .git не передаются.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = repo, onValueChange = { repo = it; vm.setSyncRepoValue(it) }, singleLine = true,
                        label = { Text("Адрес (https://github.com/ты/репозиторий.git)") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = repoName, onValueChange = { repoName = it }, singleLine = true, label = { Text("Имя нового") }, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { vm.createSyncRepo(repoName); }) { Text("Создать") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("2. Какие проекты синхронизировать", style = MaterialTheme.typography.titleSmall)
                    val names = (vm.projects + vm.remoteProjectNames).distinct().sorted()
                    if (names.isEmpty()) Text("Проектов пока нет.", style = MaterialTheme.typography.bodySmall)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        names.forEach { n ->
                            FilterChip(selected = n in vm.syncProjects, onClick = { vm.toggleSyncProject(n) }, label = { Text(n) })
                        }
                    }
                    Text("Автоматически (в фоне, Android не позволяет чаще 15 минут):", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "вручную", 15 to "15 мин", 30 to "30 мин", 60 to "1 час").forEach { (m, l) ->
                            FilterChip(selected = vm.syncInterval == m, onClick = { vm.setSyncIntervalValue(m) }, label = { Text(l) })
                        }
                    }
                    Button(onClick = { vm.syncNow() }, enabled = !vm.syncBusy && vm.syncRepo.isNotBlank()) { Text("Синхронизировать сейчас") }
                    Text("Конфликт (один файл правили на двух телефонах): берётся версия с другого телефона, а твоя сохраняется рядом как файл.conflict-local.", style = MaterialTheme.typography.labelSmall)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("3. Этот телефон выполняет задачи (исполнитель)", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Выполнять задачи, присланные с другого телефона", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Switch(checked = vm.syncHost, onCheckedChange = { vm.setSyncHostValue(it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Проверять чаще (раз в 2 минуты). Приложение будет висеть в фоне с уведомлением, батарея расходуется заметно.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Switch(checked = vm.syncLive, onCheckedChange = { vm.setSyncLiveValue(it) }, enabled = vm.syncHost)
                    }
                    Text("Задачи идут без подтверждений: опасные действия агент не выполнит, а отклонит. Выполнение идёт в отдельном чате в нужном проекте. Если ты в это время пишешь агенту сам, проверка пропускается.", style = MaterialTheme.typography.labelSmall)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("4. Пульт: отправить задачу на другой телефон", style = MaterialTheme.typography.titleSmall)
                    val names = (vm.remoteProjectNames + vm.projects).distinct().sorted()
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        names.forEach { n -> FilterChip(selected = target == n, onClick = { target = n }, label = { Text(n) }) }
                    }
                    OutlinedTextField(value = task, onValueChange = { task = it }, label = { Text("Что сделать") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.sendRemoteTask(target, task); task = "" }, enabled = task.isNotBlank() && target.isNotBlank() && vm.syncRepo.isNotBlank() && !vm.syncBusy) {
                        Text("Отправить")
                    }
                    Text("Телефон-исполнитель заберёт задачу при следующей проверке (не мгновенно) и вернёт ответ и изменённые файлы.", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (vm.remoteRows.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Задачи", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { vm.clearRemoteFinished() }) { Text("Убрать завершённые") }
                }
                vm.remoteRows.take(30).forEach { r ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            val st = r.state?.status
                            Text(
                                (when (st) { "done" -> "✓ готово"; "running" -> "⏳ выполняется"; "error" -> "✗ ошибка"; else -> "… ждёт исполнителя" }) +
                                    " · ${r.task.project} · ${fmt.format(java.util.Date(r.task.created))} · с ${r.task.from}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(r.task.prompt, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                            if (!r.state?.answer.isNullOrBlank()) Text(r.state!!.answer, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
