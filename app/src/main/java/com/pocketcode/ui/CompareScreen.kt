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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CompareScreen(vm: ChatController, onBack: () -> Unit) {
    var prompt by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(setOf(vm.activeProfileId)) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Сравнить модели") }, navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } })
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Один и тот же вопрос уходит выбранным профилям одновременно. Это быстрый способ понять, какая модель лучше справляется с твоими задачами и не подменили ли её дешёвой.", style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.profiles.forEach { p ->
                    FilterChip(
                        selected = p.id in chosen,
                        onClick = { chosen = if (p.id in chosen) chosen - p.id else chosen + p.id },
                        label = { Text(p.label + " · " + p.model.takeLast(24)) },
                    )
                }
            }
            OutlinedTextField(
                value = prompt, onValueChange = { prompt = it },
                label = { Text("Вопрос или задача") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.runCompare(prompt, chosen.toList()) },
                enabled = prompt.isNotBlank() && chosen.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Отправить выбранным") }
            vm.compare.forEach { e ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(e.profile.label + " · " + e.profile.model, style = MaterialTheme.typography.titleSmall)
                        val meta = buildString {
                            append(if (e.done) "${e.ms / 1000.0} с" else "идёт…")
                            if (e.inTok > 0) append(" · токены ${e.inTok}/${e.outTok}")
                            if (e.served.isNotBlank()) append(" · ответила: ${e.served}")
                        }
                        Text(meta, style = MaterialTheme.typography.bodySmall)
                        e.error?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error) }
                        Text(e.text)
                    }
                }
            }
        }
    }
}
