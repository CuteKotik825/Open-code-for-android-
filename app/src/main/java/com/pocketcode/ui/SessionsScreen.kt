package com.pocketcode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(vm: ChatController, onBack: () -> Unit) {
    val format = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(query, vm.sessionList) {
        hits = if (query.isBlank()) null else vm.searchSessions(query)
    }
    val visible = vm.sessionList.filter { hits == null || it.id in hits!! }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("История") },
                navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(com.pocketcode.ui.AppIcons.Back, contentDescription = "Назад") } },
            )
        },
    ) { padding ->
        if (vm.sessionList.isEmpty()) {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Сохранённых сессий пока нет.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Чат сохраняется автоматически после первого сообщения.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Поиск по чатам") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
            )
            if (visible.isEmpty()) {
                Text("Ничего не найдено.", modifier = Modifier.padding(16.dp))
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.id }) { meta ->
                    val selected = meta.id == vm.currentId
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.openSession(meta.id)
                                onBack()
                            },
                    ) {
                        Row(
                            Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (if (meta.pinned) "📌 " else "") + meta.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    meta.project.ifBlank { "main" } + " · " + format.format(Date(meta.updatedAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            TextButton(onClick = { vm.togglePin(meta.id) }) { Text(if (meta.pinned) "Открепить" else "Закрепить") }
                            TextButton(onClick = { vm.deleteSession(meta.id) }) { Text("Удалить") }
                        }
                    }
                }
            }
            }
        }
    }
}
