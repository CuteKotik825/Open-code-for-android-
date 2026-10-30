package com.pocketcode.ui

import androidx.compose.foundation.clickable
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
    var tty by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val list = rememberLazyListState()
    LaunchedEffect(vm.term.size, vm.term.lastOrNull()?.out) { if (vm.term.isNotEmpty()) list.scrollToItem(vm.term.lastIndex) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Терминал", maxLines = 1) },
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
                            AssistChip(onClick = { vm.termRun(
                                "(command -v npm >/dev/null 2>&1 || (echo 'Ставлю nodejs и npm…'; apk add --no-cache nodejs npm git libstdc++ libgcc 2>&1 | tail -n 3)); " +
                                "case \"\$(uname -m)\" in aarch64|arm64) P=opencode-linux-arm64-musl;; *) P=opencode-linux-x64-musl;; esac; " +
                                    "npm install -g --force opencode-ai \$P > /tmp/oc-install.log 2>&1; echo \"код установки: \$?\"; tail -n 15 /tmp/oc-install.log; opencode --version",
                                900,
                            ) }, label = { Text("Установить OpenCode") })
                            AssistChip(onClick = { vm.termRun("opencode --version") }, label = { Text("Версия") })
                            AssistChip(onClick = { vm.termRun("opencode models 2>&1 | head -n 40", 300) }, label = { Text("Список моделей") })
                            AssistChip(onClick = { cmd = "opencode run \"привет\"" }, label = { Text("Пример запуска") })
                            AssistChip(onClick = { tty = true; vm.termRun("opencode auth login", 600, true) }, label = { Text("Войти (auth login)") })
                            AssistChip(onClick = { vm.termRun("opencode stats", 120) }, label = { Text("Статистика") })
                        }
                    }
                    Text("Команды идут в ту же среду, что и у агента (Linux, если включён, иначе Android-оболочка), в папке проекта. Лимит одной команды 10 минут (кнопка «Стоп» прервёт раньше).", style = MaterialTheme.typography.bodySmall)
                }
                items(vm.term.toList()) { e ->
                    Column {
                        Text(
                            "$ ${e.cmd}",
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(e.out)) },
                        )
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                if (e.out.isEmpty()) (if (e.done) "(нет вывода)" else "выполняется…") else e.out + (if (e.done) "" else "\n▍"),
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, softWrap = false,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
            if (!vm.termBusy) {
                val runs = remember(vm.currentProject) { vm.detectRunCommands() }
                if (runs.isNotEmpty()) {
                    Text("Запуск проекта:", style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        runs.forEach { (label, command) -> AssistChip(onClick = { vm.termRun(command, 900, false) }, label = { Text("▶ $label") }) }
                    }
                }
            }
            if (vm.termBusy) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val enter = if (tty) "\r" else "\n"
                    AssistChip(onClick = { vm.termSend(enter) }, label = { Text("Enter") })
                    AssistChip(onClick = { vm.termSend("\u001B[A") }, label = { Text("↑") })
                    AssistChip(onClick = { vm.termSend("\u001B[B") }, label = { Text("↓") })
                    AssistChip(onClick = { vm.termSend(" ") }, label = { Text("Пробел") })
                    AssistChip(onClick = { vm.termSend("\t") }, label = { Text("Tab") })
                    AssistChip(onClick = { vm.termSend("\u001B") }, label = { Text("Esc") })
                    AssistChip(onClick = { vm.termSend("\u0003") }, label = { Text("Ctrl+C") })
                    AssistChip(onClick = { vm.termSend("y$enter") }, label = { Text("y") })
                    AssistChip(onClick = { vm.termSend("n$enter") }, label = { Text("n") })
                }
            } else {
                val recent = vm.term.map { it.cmd }.distinct().takeLast(6).reversed()
                if (recent.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.FilterChip(selected = tty, onClick = { tty = !tty }, label = { Text("TTY") })
                    recent.forEach { AssistChip(onClick = { cmd = it }, label = { Text(it.take(28)) }) }
                } else Row {
                    androidx.compose.material3.FilterChip(selected = tty, onClick = { tty = !tty }, label = { Text("TTY (для интерактивных программ)") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cmd, onValueChange = { cmd = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text(if (vm.termBusy) "ввод для программы" else "ls -la") }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                )
                if (vm.termBusy) {
                    Button(onClick = { vm.termSend(cmd + (if (tty) "\r" else "\n")); cmd = "" }) { Text("↵") }
                    OutlinedButton(onClick = { vm.termStop() }) { Text("Стоп") }
                } else Button(onClick = { vm.termRun(cmd, 600, tty); cmd = "" }, enabled = cmd.isNotBlank()) { Text("▶") }
            }
            BgPanel(vm)
        }
    }
}

@Composable
private fun BgPanel(vm: ChatController) {
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); tick++ } }
    tick
    val all = vm.bgProcs.all()
    if (all.isEmpty()) return
    Text("Фоновые процессы (запущены агентом)", style = MaterialTheme.typography.titleSmall)
    all.forEach { e ->
        val alive = e.process.isAlive
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (alive) "● " else "○ ") + e.name + " · " + e.command.take(40), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { open = if (open == e.name) null else e.name }) { Text("Лог") }
                if (alive) TextButton(onClick = { runCatching { e.process.destroy() } }) { Text("Стоп") }
            }
            if (open == e.name) {
                val tail = synchronized(e.buffer) { e.buffer.toString().takeLast(3000) }
                Text(tail.ifEmpty { "(пока пусто)" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}
