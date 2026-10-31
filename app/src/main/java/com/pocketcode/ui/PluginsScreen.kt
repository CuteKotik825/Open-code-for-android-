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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.dp
import java.io.File

/** Навыки, команды и плагины. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(vm: ChatController, onBack: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var global by remember { mutableStateOf(false) }
    var schedPrompt by remember { mutableStateOf("") }
    var schedInterval by remember { mutableStateOf(1440) }
    var mcpName by remember { mutableStateOf("") }
    var mcpUrl by remember { mutableStateOf("") }
    var mcpHeaders by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.refreshExtensions() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Навыки, команды, MCP") },
                navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(com.pocketcode.ui.AppIcons.Back, contentDescription = "Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Как этим пользоваться", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "КОМАНДА — это заготовка запроса, которую ты запускаешь сам. В чате напиши /review или /tests файл.py, и текст команды уйдет агенту. " +
                            "Всё, что ты допишешь после команды, подставится вместо \$ARGUMENTS. Список команд появляется, когда в поле ввода набираешь /.\n\n" +
                            "НАВЫК — это правила и знания, которые агент подключает сам, когда задача подходит под описание. Ты ничего не вызываешь: " +
                            "например, навык «Telegram-боты» сработает, когда попросишь написать бота. Агент видит только короткие описания, а полный текст читает при необходимости, поэтому навыки почти не тратят токены.\n\n" +
                            "ПЛАГИН — любой репозиторий GitHub, где лежат папки skills/ и commands/. Вставь ссылку в разделе «Плагины» ниже, и всё из него станет доступно.\n\n" +
                            "AGENTS.md в корне проекта агент читает в начале каждого чата: что за проект, как запускать, какие правила. Это самый простой способ «научить» агента проекту.\n\n" +
                            "ГДЕ ЛЕЖАТ: «Для проекта» — папка .pocketcode/ внутри проекта, «Для всех» — общая папка приложения. " +
                            "Быстрый старт: поставь что-нибудь из каталога ниже и сразу пробуй в чате.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text("Каталог готовых", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ставить для всех проектов", Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = global, onCheckedChange = { global = it })
            }
            com.pocketcode.SKILL_CATALOG.forEach { item ->
                val have = vm.isInstalled(item)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (item.isSkill) "Навык · " else "Команда /${item.name} · ") + item.title, style = MaterialTheme.typography.titleSmall)
                        Text(item.about, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { vm.installCatalogItem(item, global) }, enabled = !have) { Text(if (have) "Есть" else "Поставить") }
                }
            }

            Text("Навыки (${vm.skillList.size})", style = MaterialTheme.typography.titleMedium)
            if (vm.skillList.isEmpty()) Text("Пока нет.", style = MaterialTheme.typography.bodySmall)
            vm.skillList.forEach {
                Column {
                    Text(it.name, style = MaterialTheme.typography.titleSmall)
                    Text("${it.description}\n${it.source}", style = MaterialTheme.typography.bodySmall)
                }
            }

            Text("Команды (${vm.commandList.size})", style = MaterialTheme.typography.titleMedium)
            if (vm.commandList.isEmpty()) Text("Пока нет.", style = MaterialTheme.typography.bodySmall)
            vm.commandList.forEach {
                Column {
                    Text("/${it.name}", style = MaterialTheme.typography.titleSmall)
                    Text("${it.description}\n${it.source}", style = MaterialTheme.typography.bodySmall)
                }
            }

            Text("Создать заготовку", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = newName, onValueChange = { newName = it }, label = { Text("Имя (латиницей)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Для всех проектов", Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = global, onCheckedChange = { global = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.createSample("skill", newName, global) }, enabled = newName.isNotBlank()) { Text("Навык") }
                OutlinedButton(onClick = { vm.createSample("command", newName, global) }, enabled = newName.isNotBlank()) { Text("Команда") }
            }
            Text(
                "Созданный файл потом открой в «Файлы» (для проекта — .pocketcode/) и допиши инструкции. Либо попроси агента.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Плагины", style = MaterialTheme.typography.titleMedium)
            File(vm.extensions.pluginsDir.path).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.name, Modifier.weight(1f))
                    TextButton(onClick = { vm.removePlugin(d.name) }) { Text("Удалить") }
                }
            }
            OutlinedTextField(
                value = url, onValueChange = { url = it }, label = { Text("owner/repo или адрес GitHub") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.installPlugin(url); url = "" }, enabled = url.isNotBlank() && !vm.pluginBusy) { Text("Установить плагин") }
            vm.pluginMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Text("Расписание", style = MaterialTheme.typography.titleMedium)
            Text(
                "Агент сам выполняет задачу в текущем проекте с чистым контекстом: например «прогони тесты и опиши, что сломалось». " +
                    "Подтверждений нет: безопасные команды чтения выполняются, остальное отклоняется. Результат сохраняется в истории чатов, приходит уведомление. " +
                    "Android запускает такие задачи не точно по времени и может откладывать их в режиме сна; для надёжности отключи экономию батареи для приложения.",
                style = MaterialTheme.typography.bodySmall,
            )
            vm.schedules.forEach { sc ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${sc.project} · каждые ${if (sc.intervalMin >= 1440) "${sc.intervalMin / 1440} сут." else if (sc.intervalMin >= 60) "${sc.intervalMin / 60} ч" else "${sc.intervalMin} мин"}" + if (sc.enabled) "" else " · выключено", style = MaterialTheme.typography.titleSmall)
                        Text(sc.prompt.take(200), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { vm.runScheduled(sc.id, true) }, enabled = !vm.running) { Text("Запустить сейчас") }
                            TextButton(onClick = { vm.toggleSchedule(sc.id) }) { Text(if (sc.enabled) "Выключить" else "Включить") }
                            TextButton(onClick = { vm.removeSchedule(sc.id) }) { Text("Удалить") }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = schedPrompt, onValueChange = { schedPrompt = it }, label = { Text("Что делать (для проекта «${vm.currentProject}»)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60 to "1 ч", 360 to "6 ч", 1440 to "1 сут.", 10080 to "7 сут.").forEach { (m, l) ->
                    androidx.compose.material3.FilterChip(selected = schedInterval == m, onClick = { schedInterval = m }, label = { Text(l) })
                }
            }
            Button(onClick = { vm.addSchedule(schedPrompt, schedInterval); schedPrompt = "" }, enabled = schedPrompt.isNotBlank()) { Text("Добавить в расписание") }

            Text("MCP-серверы", style = MaterialTheme.typography.titleMedium)
            Text(
                "MCP подключает внешние инструменты (GitHub, базы данных, трекеры) по сети. Нужен сервер с HTTP-адресом (Streamable HTTP). " +
                    "Инструменты сервера агент получает сам; всё, что не помечено сервером как «только чтение», он подтверждает с тобой.",
                style = MaterialTheme.typography.bodySmall,
            )
            vm.mcpServers.forEach { srv ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${srv.name} · инструментов ${srv.tools.size}" + if (srv.enabled) "" else " · выключен", style = MaterialTheme.typography.titleSmall)
                        Text(srv.url, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { vm.refreshMcp(srv.id) }, enabled = !vm.mcpBusy) { Text("Обновить") }
                            TextButton(onClick = { vm.toggleMcp(srv.id) }) { Text(if (srv.enabled) "Выключить" else "Включить") }
                            TextButton(onClick = { vm.removeMcp(srv.id) }) { Text("Удалить") }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = mcpName, onValueChange = { mcpName = it }, label = { Text("Имя сервера") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = mcpUrl, onValueChange = { mcpUrl = it }, label = { Text("Адрес (https://…)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = mcpHeaders, onValueChange = { mcpHeaders = it },
                label = { Text("Заголовки, по строке (например Authorization: Bearer …)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.addMcp(mcpName, mcpUrl, mcpHeaders); mcpName = ""; mcpUrl = ""; mcpHeaders = "" },
                enabled = mcpName.isNotBlank() && mcpUrl.isNotBlank() && !vm.mcpBusy,
            ) { Text("Добавить сервер") }
            vm.mcpMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
