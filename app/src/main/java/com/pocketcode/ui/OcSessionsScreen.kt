package com.pocketcode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.launch
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
    var cId by remember { mutableStateOf("custom") }
    var cUrl by remember { mutableStateOf("") }
    var cModel by remember { mutableStateOf("") }
    var cKey by remember { mutableStateOf("") }
    var cMsg by remember { mutableStateOf<String?>(null) }
    var cBusy by remember { mutableStateOf(false) }
    var fb by remember { mutableStateOf(vm.ocFallbackModel) }
    var query by remember { mutableStateOf("") }
    var renameFor by remember { mutableStateOf<ChatController.OcSession?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    val uiScope = androidx.compose.runtime.rememberCoroutineScope()
    var accMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshOcSessions(); vm.refreshOcAccounts(); vm.refreshOcCustom() }
    var exportFor by remember { mutableStateOf<ChatController.OcSession?>(null) }
    val mdLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri -> val s = exportFor; if (uri != null && s != null) vm.exportOcSessionTo(s, uri) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Диалоги OpenCode") },
            navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(com.pocketcode.ui.AppIcons.Back, contentDescription = "Назад") } },
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
            OutlinedButton(onClick = { vm.resetSessionAndChat(true); onOpened() }, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) { Text("Сбросить сессию, оставив последнее сообщение") }
            OutlinedButton(onClick = { vm.resetSessionAndChat(false); onOpened() }, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) { Text("Сбросить сессию и чат полностью (анонимно)") }
            androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Аккаунты OpenCode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Беседы хранятся отдельно от входа, поэтому при смене или выходе из аккаунта они остаются. Чтобы добавить второй аккаунт: выйди, войди другим (поле ключа ниже или ☰ → Терминал → «Войти»), затем сохрани его здесь под именем.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("При лимите сам переключиться на другой аккаунт и повторить", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        androidx.compose.material3.Switch(checked = vm.ocAutoSwitch, onCheckedChange = { vm.setOcAutoSwitchValue(it) })
                    }
                    Text(if (vm.ocActiveAccount.isNotEmpty()) "Сейчас: ${vm.ocActiveAccount}" else "Сейчас: без сохранённого аккаунта", style = MaterialTheme.typography.labelMedium)
                    vm.ocAccounts.forEach { a ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text((if (a == vm.ocActiveAccount) "● " else "○ ") + a)
                                Text(vm.accStatsText(a), style = MaterialTheme.typography.labelSmall)
                            }
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
                    Text("Свой провайдер для OpenCode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Любой OpenAI-совместимый сервис (адрес вида https://хост/v1). Настройка записывается в конфиг OpenCode, и его можно использовать в режиме OpenCode. Запросы и код идут через этот сервис, подключай только тем, кому доверяешь.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    vm.ocCustomProviders.forEach { p ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("✓ $p", Modifier.weight(1f))
                            TextButton(onClick = { vm.ocCustomRemove(p) }) { Text("Убрать") }
                        }
                    }
                    Text("Быстро заполнить:", style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.pocketcode.PROVIDER_TEMPLATES.filter { it.provider == com.pocketcode.Provider.OPENAI }.forEach { t ->
                            androidx.compose.material3.AssistChip(onClick = {
                                cId = t.name.lowercase().replace(Regex("[^a-z0-9]+"), "").ifEmpty { "custom" }; cUrl = t.baseUrl; cModel = t.model
                            }, label = { Text(t.name) })
                        }
                    }
                    androidx.compose.material3.OutlinedTextField(value = cId, onValueChange = { cId = it }, singleLine = true, label = { Text("Название (латиницей)") }, modifier = Modifier.fillMaxWidth())
                    androidx.compose.material3.OutlinedTextField(value = cUrl, onValueChange = { cUrl = it }, singleLine = true, label = { Text("Адрес API (Base URL)") }, modifier = Modifier.fillMaxWidth())
                    androidx.compose.material3.OutlinedTextField(value = cModel, onValueChange = { cModel = it }, singleLine = true, label = { Text("Модель (как в кабинете сервиса)") }, modifier = Modifier.fillMaxWidth())
                    androidx.compose.material3.OutlinedTextField(
                        value = cKey, onValueChange = { cKey = it }, singleLine = true, label = { Text("Ключ") }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                    Button(onClick = { cMsg = vm.ocCustomSet(cId, cUrl, cModel, cKey); cKey = "" }, enabled = cUrl.isNotBlank() && cModel.isNotBlank()) { Text("Сохранить провайдер") }
                    if (vm.ocCustomProviders.isNotEmpty()) OutlinedButton(
                        onClick = { cBusy = true; cMsg = "Проверяю…"; uiScope.launch { cMsg = vm.ocCustomTest(vm.ocCustomProviders.first()); cBusy = false } },
                        enabled = !cBusy,
                    ) { Text("Проверить провайдера «${vm.ocCustomProviders.first()}»") }
                    cMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    androidx.compose.material3.OutlinedTextField(
                        value = fb, onValueChange = { fb = it; vm.setOcFallbackValue(it) }, singleLine = true,
                        label = { Text("Запасная модель при лимите (провайдер/модель)") }, modifier = Modifier.fillMaxWidth(),
                    )
                    var px by remember { mutableStateOf(vm.netProxy) }
                    androidx.compose.material3.OutlinedTextField(
                        value = px, onValueChange = { px = it; vm.setNetProxyValue(it) }, singleLine = true,
                        label = { Text("HTTP-прокси для OpenCode и терминала (http://хост:порт)") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Загрузка бесед. Если список или беседа открываются слишком долго, выбери значение поменьше:", style = MaterialTheme.typography.bodySmall)
                    Text("Сколько бесед в списке:", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(20, 50, 100, 200).forEach { v ->
                            androidx.compose.material3.FilterChip(selected = vm.ocListLimit == v, onClick = { vm.setOcListLimitValue(v); vm.refreshOcSessions() }, label = { Text("$v") })
                        }
                    }
                    Text("Сколько последних сообщений показывать при открытии беседы:", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(10, 20, 60, 150).forEach { v ->
                            androidx.compose.material3.FilterChip(selected = vm.ocShowLimit == v, onClick = { vm.setOcShowLimitValue(v) }, label = { Text("$v") })
                        }
                    }
                    Text(
                        "Беседы OpenCode бывают огромными, и телефону приходится вытаскивать и разбирать всю беседу целиком. Чем меньше число, тем меньше текста он разбирает и показывает, поэтому открывается быстрее. Сам OpenCode помнит всю беседу независимо от этого числа: лимит влияет только на то, что видно в чате.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text("Повтор после 429 (слишком частые запросы) через:", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 5, 15, 30).forEach { m ->
                            androidx.compose.material3.FilterChip(selected = vm.ocRateRetryMin == m, onClick = { vm.setOcRateRetryValue(m) }, label = { Text("$m мин") })
                        }
                    }
                    Text("Бюджет запросов OpenCode в день (приложение остановит и предупредит заранее):", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "выкл", 20 to "20", 50 to "50", 100 to "100").forEach { (v, l) ->
                            androidx.compose.material3.FilterChip(selected = vm.ocDailyBudget == v, onClick = { vm.setOcDailyBudgetValue(v) }, label = { Text(l) })
                        }
                    }
                    Text("Доступ OpenCode вне папки проекта (/proc, /etc и т.п.):", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("deny" to "Запретить", "ask" to "Спрашивать", "allow" to "Разрешить всё").forEach { (v, l) ->
                            androidx.compose.material3.FilterChip(selected = vm.ocExtMode == v, onClick = { vm.setOcExtModeValue(v) }, label = { Text(l) })
                        }
                    }
                    Text(
                        when (vm.ocExtMode) {
                            "ask" -> "OpenCode не может сам спросить в этом режиме, поэтому приложение покажет окно: что именно он хотел, с кнопкой «скопировать», чтобы ты мог спросить у нейросети. Времени на ответ сколько угодно."
                            "allow" -> "Любые обращения вне проекта разрешены без вопросов. Linux в приложении изолирован: Android и чужие приложения ему не видны."
                            else -> "Всё вне папки проекта отклоняется (кроме /tmp)."
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Каждый новый запрос начинает новую беседу (без длинной истории, меньше риск лимитов)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        androidx.compose.material3.Switch(checked = vm.ocFreshEach, onCheckedChange = { vm.setOcFreshEachValue(it) })
                    }
                    Text("Автосжатие беседы при контексте больше:", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "выкл", 80_000 to "80к", 120_000 to "120к", 200_000 to "200к").forEach { (v, l) ->
                            androidx.compose.material3.FilterChip(selected = vm.ocAutoCompact == v, onClick = { vm.setOcAutoCompactValue(v) }, label = { Text(l) })
                        }
                    }
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
            androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Когда лучше работать", style = MaterialTheme.typography.titleSmall)
                    Text(vm.ocBestHoursText(), style = MaterialTheme.typography.bodySmall)
                    Text("Считается по твоим запросам к OpenCode: какая доля прошла без лимита и ошибок сервера в разное время суток. Сетевые сбои не учитываются. Чем больше запросов, тем точнее.", style = MaterialTheme.typography.labelSmall)
                }
            }
            androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Журнал запросов", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Скрыть" else "Показать") }
                        if (showLog) TextButton(onClick = { vm.ocLogClear() }) { Text("Очистить") }
                    }
                    if (showLog) {
                        val lines = vm.ocLogLines().take(40)
                        if (lines.isEmpty()) Text("Пока пусто.", style = MaterialTheme.typography.bodySmall)
                        lines.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            androidx.compose.material3.OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Поиск по беседам (название и последнее сообщение)") }, modifier = Modifier.fillMaxWidth(),
            )
            vm.ocSessionsMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
          } }
          run {
                val q = query.trim()
                val shown = vm.ocSessions
                    .filter { q.isEmpty() || vm.ocTitleOf(it).contains(q, true) || (vm.ocPreviews[it.id] ?: "").contains(q, true) }
                    .sortedByDescending { it.id in vm.ocPins }
                items(shown) { s ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text((if (s.id in vm.ocPins) "📌 " else "") + vm.ocTitleOf(s), style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(s.updated.ifEmpty { s.id }, style = MaterialTheme.typography.labelSmall)
                            Text(
                                vm.ocPreviews[s.id]?.let { "…$it" } ?: "загружаю последнее сообщение…",
                                style = MaterialTheme.typography.bodySmall, maxLines = 3,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.useOcSession(s); onOpened() }, enabled = !vm.running && !vm.ocSessionsBusy) {
                                    Text(if (vm.ocSessionId == s.id) "Выбрана" else "Открыть")
                                }
                                TextButton(onClick = { vm.toggleOcPin(s.id) }) { Text(if (s.id in vm.ocPins) "Открепить" else "📌") }
                                TextButton(onClick = { renameFor = s; renameText = vm.ocTitleOf(s) }) { Text("✎") }
                                TextButton(onClick = { exportFor = s; mdLauncher.launch("opencode-" + s.id.takeLast(8) + ".md") }, enabled = !vm.ocSessionsBusy) { Text("В Markdown") }
                                TextButton(onClick = { confirmDelete = s }, enabled = !vm.ocSessionsBusy) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
        }
    }
    renameFor?.let { s ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Название беседы") },
            text = { androidx.compose.material3.OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true, label = { Text("Пусто — вернуть исходное") }) },
            confirmButton = { TextButton(onClick = { vm.renameOc(s.id, renameText); renameFor = null }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Отмена") } },
        )
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
