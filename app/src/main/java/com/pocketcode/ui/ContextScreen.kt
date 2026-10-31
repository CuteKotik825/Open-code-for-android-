package com.pocketcode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketcode.agent.Compactor

/** Что именно занимает контекст: можно сократить тяжёлые результаты или забыть всё до выбранного сообщения. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextScreen(vm: ChatController, onBack: () -> Unit) {
    val rows = vm.contextRows()
    val total = rows.sumOf { it.tokens }
    val limit = vm.effectiveContext.coerceAtLeast(1)
    val heavy = rows.filter { it.canTrim }.sumOf { it.tokens }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Что в контексте") }, navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("История ≈ $total токенов, плюс системная часть и инструменты ≈ 3000. Предел: $limit.", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { ((total + 3000).toFloat() / limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("Каждый шаг агента отправляет всю эту историю заново, поэтому её размер напрямую влияет на цену.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.trimAllHeavy() }, enabled = heavy > 0 && !vm.running) { Text("Сократить тяжёлые результаты") }
            }
            if (vm.running) Text("Пока агент работает, менять контекст нельзя.", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(rows) { _, r ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text("#${r.index + 1} · ${r.role} · ≈${r.tokens} ток.", style = MaterialTheme.typography.labelMedium)
                            Text(r.label, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (r.canTrim) TextButton(onClick = { vm.trimContext(r.index) }, enabled = !vm.running) { Text("Сократить") }
                                if (r.canCut) TextButton(onClick = { vm.forgetBefore(r.index) }, enabled = !vm.running) { Text("Забыть всё до этого") }
                            }
                        }
                    }
                }
            }
        }
    }
}
