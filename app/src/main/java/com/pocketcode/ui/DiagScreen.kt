package com.pocketcode.ui

import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/** Самопроверка, отчёт об ошибках, расход токенов и резервная копия настроек. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagScreen(vm: ChatController, onBack: () -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var priceIn by remember { mutableStateOf(vm.settings.priceIn.takeIf { it > 0 }?.toString() ?: "") }
    var priceOut by remember { mutableStateOf(vm.settings.priceOut.takeIf { it > 0 }?.toString() ?: "") }
    var limit by remember { mutableStateOf(vm.settings.dailyTokenLimit.takeIf { it > 0 }?.toString() ?: "") }
    var copied by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val version = vm.usageVersion

    fun k(n: Long) = when {
        n >= 1_000_000 -> "%.2f млн".format(n / 1_000_000.0)
        n >= 1000 -> "${n / 1000} тыс."
        else -> "$n"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Диагностика и расход") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Самопроверка", style = MaterialTheme.typography.titleMedium)
            Text(
                "Коротко проверяет ключ и связь с моделью, файлы, shell, Python, git, интернет, Linux и root. " +
                    "Запрос к модели тратит несколько токенов.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { vm.runSelfCheck() }, enabled = !vm.checking) { Text(if (vm.checking) "Проверяю…" else "Запустить проверку") }
            vm.checks.forEach {
                Text((if (it.ok) "✅ " else "❌ ") + it.name + ": " + it.detail, style = MaterialTheme.typography.bodySmall)
            }

            Text("Отчёт для разработчика", style = MaterialTheme.typography.titleMedium)
            Text(
                "Версия, устройство, настройки (без ключей) и последние ошибки. Скопируй и пришли мне вместо кучи скриншотов.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.buildReport())); copied = true }) { Text(if (copied) "Скопировано" else "Копировать") }
                OutlinedButton(onClick = {
                    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, vm.buildReport()) }
                    context.startActivity(Intent.createChooser(i, "Отчёт"))
                }) { Text("Поделиться") }
                TextButton(onClick = { com.pocketcode.AppLog.clear(); copied = false }) { Text("Очистить журнал") }
            }

            Text("Расход токенов", style = MaterialTheme.typography.titleMedium)
            val today = vm.usageToday()
            val total = vm.usageTotal()
            Text("Сегодня: вход ${k(today[0])}, выход ${k(today[1])}, из них из кэша ${k(today[2])}, запросов ${today[3]}" +
                if (vm.settings.priceIn > 0 || vm.settings.priceOut > 0) ", ≈ \$%.2f".format(vm.costOf(today[0], today[1])) else "",
                style = MaterialTheme.typography.bodyMedium)
            Text("Всего: вход ${k(total[0])}, выход ${k(total[1])}, запросов ${total[3]}" +
                if (vm.settings.priceIn > 0 || vm.settings.priceOut > 0) ", ≈ \$%.2f".format(vm.costOf(total[0], total[1])) else "",
                style = MaterialTheme.typography.bodyMedium)
            Text("По профилям за этот месяц", style = MaterialTheme.typography.titleSmall)
            vm.profiles.forEach { p ->
                val u = vm.usageMonth(p.id)
                Text("• ${p.label}: вход ${k(u[0])}, выход ${k(u[1])}, запросов ${u[3]}" +
                    if (vm.settings.priceIn > 0 || vm.settings.priceOut > 0) ", ≈ \$%.2f".format(vm.costOf(u[0], u[1])) else "",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("По проектам:", style = MaterialTheme.typography.titleSmall)
            vm.usageByProject().take(6).forEach { Text("• ${it.first}: ${k(it.second)}", style = MaterialTheme.typography.bodySmall) }
            Text(
                "Почему входных токенов так много: на каждом шаге агент заново отправляет всю историю чата, а шагов в задаче десятки. " +
                    "Помогает: короткие чаты (/clear между задачами), меньший размер контекста в настройках, кэширование, подагент task для обзора кода.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(value = priceIn, onValueChange = { priceIn = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Цена входа, \$ за 1 млн токенов") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = priceOut, onValueChange = { priceOut = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Цена выхода, \$ за 1 млн токенов") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = limit, onValueChange = { limit = it.filter(Char::isDigit).take(10) },
                label = { Text("Суточный лимит токенов (пусто — без лимита)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    vm.saveSettings(
                        vm.settings.copy(
                            priceIn = priceIn.toFloatOrNull() ?: 0f,
                            priceOut = priceOut.toFloatOrNull() ?: 0f,
                            dailyTokenLimit = limit.toIntOrNull() ?: 0,
                        ),
                        vm.profiles, vm.activeProfileId,
                    )
                }) { Text("Сохранить") }
                TextButton(onClick = { vm.resetUsage() }) { Text("Сбросить счётчики") }
            }

            Text("Резервная копия настроек", style = MaterialTheme.typography.titleMedium)
            Text(
                "Профили с ключами, правила, хуки, MCP, расписания, устройства и общие навыки одним файлом. Для переноса на другой телефон. " +
                    "Файл содержит секреты, не отправляй его никому.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExport) { Text("Сохранить копию") }
                OutlinedButton(onClick = onImport) { Text("Восстановить") }
            }
            vm.backupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
