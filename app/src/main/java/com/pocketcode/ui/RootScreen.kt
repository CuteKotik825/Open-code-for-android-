package com.pocketcode.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val BRIDGE_WARNING =
    "Агент сможет присылать команды, которые выполнятся на твоём телефоне от root. Неверная команда может стереть данные, сломать систему " +
        "или открыть доступ к аккаунтам. Каждую команду ты подтверждаешь вручную: читай её целиком и, если не уверен, проверь у нейросети."

/** Обратный отсчёт: пока не дошёл до нуля, подтверждение недоступно. */
@Composable
private fun rememberCountdown(key: Any, seconds: Int = 15): Int {
    var left by remember(key) { mutableIntStateOf(seconds) }
    LaunchedEffect(key) { while (left > 0) { delay(1000); left-- } }
    return left
}

@Composable
private fun Option(title: String, about: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** Root и доступ агента к Android: четыре способа, от самого безопасного к самому свободному. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(vm: ChatController, onBack: () -> Unit, onToChat: () -> Unit) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var confirmEnable by remember { mutableStateOf(false) }
    var cmd by remember { mutableStateOf("") }
    var confirmRun by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Root и Android") },
                navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(AppIcons.Back, contentDescription = "Назад") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Агент работает в песочнице Linux и сам не видит Android: ни системных данных, ни root (uid=0 там ненастоящий). " +
                    "Ниже четыре способа, как дать ему нужные данные: от самого безопасного к самому свободному. Выбирай любой.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Option("1. Вручную", "Ты сам выполняешь команду в терминале с root (Termux) и кладёшь вывод в общую папку проекта. Агент читает файл. Приложение ничего не запускает.") {
                val sample = "su -c 'dumpsys audio' > /storage/emulated/0/PocketCode/${vm.currentProject}/audio.txt"
                Text(sample, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(sample)) }) { Text("Скопировать пример") }
                Text("Путь зависит от того, где лежит проект. Если он внутри приложения, используй пункты 2 или 3.", style = MaterialTheme.typography.labelSmall)
            }

            Option("2. Собрать диагностику", "Приложение само снимает фиксированный набор данных через su и кладёт файлы в папку pc-diagnostics проекта. Агент только читает готовые файлы. Список команд виден заранее, лишнего не выполнится.") {
                vm.diagPresets.forEach { (name, cmds) ->
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(cmds.joinToString("\n") { "$ " + it.second }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    Button(onClick = { vm.runRootDiag(name) }, enabled = !vm.rootBusy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (vm.rootBusy) "Подожди…" else "Собрать: $name")
                    }
                }
                vm.rootMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(
                    onClick = {
                        vm.send("Диагностика телефона снята в папку pc-diagnostics проекта. Прочитай файлы там, найди причину моей проблемы и предложи безопасное решение. Ничего не меняй без моего согласия.")
                        onToChat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Передать агенту и вернуться в чат") }
            }

            Option("3. Агент предлагает, ты выполняешь", "Агент пишет команду в чате, ты проверяешь её и выполняешь здесь от root одной кнопкой. Вывод показывается и сохраняется в pc-diagnostics/last-command.txt.") {
                OutlinedTextField(value = cmd, onValueChange = { cmd = it }, label = { Text("Команда для su -c") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { cmd = vm.lastCodeBlock() }) { Text("Из ответа агента") }
                    OutlinedButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString("Безопасно ли выполнять на Android от root эту команду? Что она сделает?\n\n$cmd")) }, enabled = cmd.isNotBlank()) { Text("Скопировать для проверки") }
                }
                dangerNote(cmd)?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
                Button(onClick = { confirmRun = true }, enabled = cmd.isNotBlank() && !vm.rootBusy, modifier = Modifier.fillMaxWidth()) { Text("Выполнить от root") }
                vm.rootOut?.let {
                    Text(it.take(3000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    OutlinedButton(
                        onClick = { vm.send("Результат команды от root сохранён в pc-diagnostics/last-command.txt. Прочитай его и продолжай."); onToChat() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Передать агенту") }
                }
            }

            Option("4. Мост: агент сам просит команды", "Агент в Linux получает команду pcroot и присылает через неё команды на телефон. Каждая показывается тебе в окне, выполняется только после твоего подтверждения. Самый свободный способ, по умолчанию выключен.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (vm.hostMode == "ask") "Включён: спрашивать каждый раз" else "Выключен")
                    }
                    Switch(
                        checked = vm.hostMode == "ask",
                        onCheckedChange = { if (it) confirmEnable = true else vm.setHostModeValue("off") },
                    )
                }
                Text("⚠ $BRIDGE_WARNING", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("Окно подтверждения не закрывается само, но кнопки выполнения включаются через 15 секунд после появления запроса. Если приложение свёрнуто, придёт уведомление.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (confirmEnable) {
        val left = rememberCountdown("enable")
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text("Включить мост к Android?") },
            text = { Text("⚠ $BRIDGE_WARNING", color = MaterialTheme.colorScheme.error) },
            confirmButton = {
                TextButton(onClick = { confirmEnable = false; vm.setHostModeValue("ask") }, enabled = left == 0) {
                    Text(if (left > 0) "Включить ($left)" else "Включить")
                }
            },
            dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("Отмена") } },
        )
    }
    if (confirmRun) {
        AlertDialog(
            onDismissRequest = { confirmRun = false },
            title = { Text("Выполнить от root?") },
            text = { Text(cmd.take(800), fontFamily = FontFamily.Monospace) },
            confirmButton = { TextButton(onClick = { confirmRun = false; vm.runRootOnce(cmd) }) { Text("Выполнить") } },
            dismissButton = { TextButton(onClick = { confirmRun = false }) { Text("Отмена") } },
        )
    }
}

/** Окно подтверждения: агент в Linux просит выполнить команду на самом телефоне. Без таймера, но кнопки включаются через 15 секунд. */
@Composable
fun HostBridgeDialog(vm: ChatController) {
    val req = vm.hostPending.firstOrNull() ?: return
    val left = rememberCountdown(req.id)
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val summary = "Агент OpenCode в Linux-песочнице на моём телефоне просит выполнить на самом Android (root, Magisk) команду:\n\n" + req.command +
        "\n\nБезопасно ли её выполнять? Что она сделает?"
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Агент просит команду на телефоне") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⚠ Команда выполнится на твоём телефоне. Прочитай её внимательно.", color = MaterialTheme.colorScheme.error)
                Text("Окно не закроется само, агент подождёт. Кнопки выполнения включатся через 15 секунд.", style = MaterialTheme.typography.bodySmall)
                Text(req.command.take(1500), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                dangerNote(req.command)?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
                Text(
                    "«От root» выполнит команду через Magisk (root должен быть выдан приложению). dumpsys, getprop, logcat только читают. «Без root» запустит её с правами самого приложения.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(summary)) }) { Text("Скопировать описание, чтобы спросить нейросеть") }
                if (vm.hostPending.size > 1) Text("Ещё запросов в очереди: ${vm.hostPending.size - 1}", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { vm.hostAnswer(req, "shell") }, enabled = left == 0) { Text("Без root") }
                TextButton(onClick = { vm.hostAnswer(req, "root") }, enabled = left == 0) { Text(if (left > 0) "От root ($left)" else "От root") }
            }
        },
        dismissButton = { TextButton(onClick = { vm.hostAnswer(req, "deny") }) { Text("Отклонить") } },
    )
}
