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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.unit.dp

/** Беседы, которые ведёт OpenCode внутри Linux: выбрать, продолжить, удалить. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcSessionsScreen(vm: ChatController, onBack: () -> Unit, onOpened: () -> Unit) {
    var confirmDelete by remember { mutableStateOf<ChatController.OcSession?>(null) }
    var provider by remember { mutableStateOf("opencode") }
    var key by remember { mutableStateOf("") }
    var keyMsg by remember { mutableStateOf<String?>(null) }
    var accName by remember { mutableStateOf("") }
    var accMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshOcSessions(); vm.refreshOcAccounts() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Диалоги OpenCode") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } },
            actions = { TextButton(onClick = { vm.refreshOcSessions() }, enabled = !vm.ocSessionsBusy) { Text("Обновить") } },
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Это беседы самого OpenCode (хранятся внутри Linux). Выбери беседу, и новые сообщения в режиме OpenCode продолжат её, а переписка покажется в чате.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (vm.ocSessionId != null) Text("Сейчас выбрана: ${vm.ocSessionTitle}", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { vm.newOcSession(); onOpened() }, modifier = Modifier.fillMaxWidth()) { Text("Начать новую беседу") }
            OutlinedButton(onClick = { vm.resetSessionAndChat(); onOpened() }, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) { Text("Сбросить id сессии и чат (анонимно)") }
            androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Аккаунты OpenCode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Беседы хранятся отдельно от входа, поэтому при смене или выходе из аккаунта они остаются. Чтобы добавить второй аккаунт: выйди, войди другим (поле ключа ниже или ☰ → Терминал → «Войти»), затем сохрани его здесь под именем.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(if (vm.ocActiveAccount.isNotEmpty()) "Сейчас: ${vm.ocActiveAccount}" else "Сейчас: без сохранённого аккаунта", style = MaterialTheme.typography.labelMedium)
                    vm.ocAccounts.forEach { a ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text((if (a == vm.ocActiveAccount) "● " else "○ ") + a, Modifier.weight(1f))
                            TextButton(onClick = { accMsg = vm.ocAccountSwitch(a) }, enabled = a != vm.ocActiveAccount) { Text("Выбрать") }
                            TextButton(onClick = { vm.ocAccountDelete(a) }) { Text("✕") }
                        }
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = accName, onValueChange = { accName = it }, singleLine = true,
                        label = { Text("Название (например, «Основной»)") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { accMsg = vm.ocAccountSave(accName); accName = "" }, enabled = accName.isNotBlank()) { Text("Сохранить текущий вход") }
                        OutlinedButton(onClick = { accMsg = vm.ocLogout() }) { Text("Выйти") }
                    }
                    accMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Ключ OpenCode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Ключ с opencode.ai (Zen или Go) или любого провайдера записывается в файл OpenCode внутри Linux. После этого модели этого провайдера работают в режиме OpenCode. Ключи не показываются.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (vm.ocProviders.isNotEmpty()) {
                        vm.ocProviders.forEach { p ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("✓ $p", Modifier.weight(1f))
                                TextButton(onClick = { vm.ocAuthRemove(p) }) { Text("Убрать") }
                            }
                        }
                    } else Text("Подключённых ключей нет.", style = MaterialTheme.typography.labelSmall)
                    androidx.compose.material3.OutlinedTextField(
                        value = provider, onValueChange = { provider = it }, singleLine = true,
                        label = { Text("Провайдер (opencode, deepseek, openai…)") }, modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = key, onValueChange = { key = it }, singleLine = true,
                        label = { Text("Ключ") }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                    Button(onClick = { keyMsg = vm.ocAuthSet(provider, key); key = "" }, enabled = key.isNotBlank()) { Text("Сохранить ключ") }
                    keyMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Карточки действий (экспериментально)", style = MaterialTheme.typography.titleSmall)
                            Text("Правки и команды OpenCode показываются карточками, как у обычного агента. Формат его вывода в документации не описан, если что-то выглядит странно, выключи.", style = MaterialTheme.typography.bodySmall)
                        }
                        androidx.compose.material3.Switch(checked = vm.ocCards, onCheckedChange = { vm.setOcCardsValue(it) })
                    }
                    Text("Статистика запросов", style = MaterialTheme.typography.titleSmall)
                    Text(vm.ocStatsText(), style = MaterialTheme.typography.bodySmall)
                }
            }
            vm.ocSessionsMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
          } }
          run {
                items(vm.ocSessions) { s ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(s.updated.ifEmpty { s.id }, style = MaterialTheme.typography.labelSmall)
                            Text(
                                vm.ocPreviews[s.id]?.let { "…$it" } ?: "загружаю последнее сообщение…",
                                style = MaterialTheme.typography.bodySmall, maxLines = 3,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.useOcSession(s); onOpened() }, enabled = !vm.running && !vm.ocSessionsBusy) {
                                    Text(if (vm.ocSessionId == s.id) "Выбрана" else "Открыть")
                                }
                                TextButton(onClick = { confirmDelete = s }, enabled = !vm.ocSessionsBusy) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { s ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить беседу?") },
            text = { Text("«${s.title}» будет удалена из OpenCode без возможности восстановления.") },
            confirmButton = { TextButton(onClick = { vm.deleteOcSession(s.id); confirmDelete = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } },
        )
    }
}
