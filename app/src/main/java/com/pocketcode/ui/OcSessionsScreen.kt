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
    LaunchedEffect(Unit) { vm.refreshOcSessions() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Диалоги OpenCode") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } },
            actions = { TextButton(onClick = { vm.refreshOcSessions() }, enabled = !vm.ocSessionsBusy) { Text("Обновить") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Это беседы самого OpenCode (хранятся внутри Linux). Выбери беседу, и новые сообщения в режиме OpenCode продолжат её, а переписка покажется в чате.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (vm.ocSessionId != null) Text("Сейчас выбрана: ${vm.ocSessionTitle}", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { vm.newOcSession(); onOpened() }, modifier = Modifier.fillMaxWidth()) { Text("Начать новую беседу") }
            vm.ocSessionsMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(vm.ocSessions) { s ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(s.updated.ifEmpty { s.id }, style = MaterialTheme.typography.labelSmall)
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
