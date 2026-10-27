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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ручной git: статус, изменения, история, ветки, коммит, push/pull. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(vm: ChatController, onBack: () -> Unit) {
    var message by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var prTitle by remember { mutableStateOf("") }
    var prBody by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.gitRun("status") }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Git · ${vm.currentProject}") }, navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } })
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.gitRun("status") }, enabled = !vm.gitBusy) { Text("Статус") }
                OutlinedButton(onClick = { vm.gitRun("diff") }, enabled = !vm.gitBusy) { Text("Изменения") }
                OutlinedButton(onClick = { vm.gitRun("log", "count" to "20") }, enabled = !vm.gitBusy) { Text("История") }
                OutlinedButton(onClick = { vm.gitRun("branch") }, enabled = !vm.gitBusy) { Text("Ветки") }
            }
            Text(
                if (vm.gitBusy) "Выполняется…" else vm.gitOut.ifEmpty { "—" },
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(),
            )
            Text("Коммит", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(value = message, onValueChange = { message = it }, label = { Text("Сообщение коммита") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.gitRun("add") }, enabled = !vm.gitBusy) { Text("В индекс всё") }
                Button(onClick = { vm.gitRun("commit", "message" to message); message = "" }, enabled = !vm.gitBusy && message.isNotBlank()) { Text("Закоммитить") }
            }
            Text("Сервер", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.gitRun("pull") }, enabled = !vm.gitBusy) { Text("Pull") }
                OutlinedButton(onClick = { vm.gitRun("push") }, enabled = !vm.gitBusy) { Text("Push") }
            }
            Text("Токен GitHub берётся из настроек.", style = MaterialTheme.typography.bodySmall)
            Text("GitHub", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.githubRun("pr_list") }, enabled = !vm.gitBusy) { Text("Pull Request") }
                OutlinedButton(onClick = { vm.githubRun("issue_list") }, enabled = !vm.gitBusy) { Text("Issues") }
                OutlinedButton(onClick = { vm.githubRun("checks") }, enabled = !vm.gitBusy) { Text("Проверки") }
            }
            OutlinedTextField(value = prTitle, onValueChange = { prTitle = it }, label = { Text("Заголовок нового Pull Request") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = prBody, onValueChange = { prBody = it }, label = { Text("Описание") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.githubRun("pr_create", "title" to prTitle, "body" to prBody); prTitle = ""; prBody = "" }, enabled = !vm.gitBusy && prTitle.isNotBlank()) {
                Text("Создать Pull Request из текущей ветки")
            }
            Text("Сначала отправь ветку кнопкой Push.", style = MaterialTheme.typography.labelSmall)
            Text("Ветка", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(value = branch, onValueChange = { branch = it }, label = { Text("Имя ветки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.gitRun("checkout", "branch" to branch) }, enabled = !vm.gitBusy && branch.isNotBlank()) { Text("Переключиться") }
                OutlinedButton(onClick = { vm.gitRun("checkout", "branch" to branch, "create" to "true") }, enabled = !vm.gitBusy && branch.isNotBlank()) { Text("Создать и перейти") }
            }
        }
    }
}
