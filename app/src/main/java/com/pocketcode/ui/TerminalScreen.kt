package com.pocketcode.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ручной терминал: команды выполняются в папке проекта, без участия модели. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: ChatController, onBack: () -> Unit) {
    var cmd by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(vm.term.size, vm.term.lastOrNull()?.out) { if (vm.term.isNotEmpty()) list.scrollToItem(vm.term.lastIndex) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Терминал · ${vm.currentProject}") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } },
            actions = { TextButton(onClick = { vm.term.clear() }) { Text("Очистить") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.term.isEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Официальный OpenCode можно запустить внутри Linux (нужны: Linux включён и «Установить python, git, nodejs» в разделе «Проекты»). Это его собственный клиент, поэтому его бесплатные модели работают так же, как на ПК, но скорость в proot ниже и на телефоне это не проверено.", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(onClick = { vm.termRun("npm install -g opencode-ai 2>&1 | tail -n 20", 900) }, label = { Text("Установить OpenCode") })
                            AssistChip(onClick = { vm.termRun("opencode --version") }, label = { Text("Версия") })
                            AssistChip(onClick = { vm.termRun("opencode models 2>&1 | head -n 40", 300) }, label = { Text("Список моделей") })
                            AssistChip(onClick = { cmd = "opencode run \"привет\"" }, label = { Text("Пример запуска") })
                        }
                    }
                    Text("Команды идут в ту же среду, что и у агента (Linux, если включён, иначе Android-оболочка), в папке проекта. Лимит одной команды 2 минуты.", style = MaterialTheme.typography.bodySmall)
                }
                items(vm.term.toList()) { e ->
                    Column {
                        Text("$ ${e.cmd}", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        Text(if (e.done) e.out.ifEmpty { "(нет вывода)" } else "выполняется…", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
            val recent = vm.term.map { it.cmd }.distinct().takeLast(6).reversed()
            if (recent.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recent.forEach { AssistChip(onClick = { cmd = it }, label = { Text(it.take(28)) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cmd, onValueChange = { cmd = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("ls -la") }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                )
                if (vm.termBusy) OutlinedButton(onClick = { vm.termStop() }) { Text("Стоп") }
                else Button(onClick = { vm.termRun(cmd); cmd = "" }, enabled = cmd.isNotBlank()) { Text("▶") }
            }
        }
    }
}
