package com.pocketcode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketcode.Profile
import com.pocketcode.Provider
import com.pocketcode.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: ChatController,
    initial: Settings,
    onSave: (Settings, List<Profile>, String) -> Unit,
    onResetContext: () -> Unit,
    onOpenCompare: () -> Unit,
    onBack: () -> Unit,
) {
    var provider by remember { mutableStateOf(initial.provider) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var githubToken by remember { mutableStateOf(initial.githubToken) }
    var gitName by remember { mutableStateOf(initial.gitName) }
    var gitEmail by remember { mutableStateOf(initial.gitEmail) }
    var autoApprove by remember { mutableStateOf(initial.autoApprove) }
    var contextTokens by remember { mutableStateOf((vm.profiles.firstOrNull { it.id == vm.activeProfileId }?.contextTokens?.takeIf { it > 0 } ?: initial.contextTokens).toString()) }
    var freshOnStart by remember { mutableStateOf(initial.freshOnStart) }
    var smartApprove by remember { mutableStateOf(initial.smartApprove) }
    var allowRules by remember { mutableStateOf(initial.allowRules) }
    var resetDone by remember { mutableStateOf(false) }
    var promptCaching by remember { mutableStateOf(initial.promptCaching) }
    var maxSteps by remember { mutableStateOf(initial.maxSteps.toString()) }
    var permissionRules by remember { mutableStateOf(initial.permissionRules) }
    var fileHooks by remember { mutableStateOf(initial.fileHooks) }
    var maxOutput by remember { mutableStateOf((vm.profiles.firstOrNull { it.id == vm.activeProfileId }?.maxOutputTokens ?: initial.maxOutputTokens).takeIf { it > 0 }?.toString() ?: "") }
    var helperModel by remember { mutableStateOf(initial.helperModel) }
    var fallbackId by remember { mutableStateOf(initial.fallbackProfileId) }
    var autoContinue by remember { mutableStateOf(initial.autoContinue) }
    var ecoMode by remember { mutableStateOf(initial.ecoMode) }
    var fontScale by remember { mutableStateOf(initial.fontScale) }
    var compactUi by remember { mutableStateOf(initial.compactUi) }
    var showTemplates by remember { mutableStateOf(false) }
    var hooksMsg by remember { mutableStateOf<String?>(null) }

    // Профили ключей: форма ниже всегда редактирует активный профиль
    var profiles by remember { mutableStateOf(vm.profiles) }
    var activeId by remember { mutableStateOf(vm.activeProfileId) }
    var label by remember { mutableStateOf(vm.profiles.firstOrNull { it.id == vm.activeProfileId }?.label ?: "Основной") }
    fun formProfile() = Profile(
        activeId, label.ifBlank { "Без названия" }, provider, baseUrl.trim(), model.trim(), apiKey.trim(),
        (contextTokens.toIntOrNull() ?: 128_000).coerceIn(8_000, 2_000_000), maxOutput.toIntOrNull() ?: 0,
    )
    fun withForm(): List<Profile> =
        if (profiles.any { it.id == activeId }) profiles.map { if (it.id == activeId) formProfile() else it }
        else profiles + formProfile()
    fun load(p: Profile) {
        activeId = p.id
        label = p.label
        provider = p.provider
        baseUrl = p.baseUrl
        model = p.model
        apiKey = p.apiKey
        contextTokens = p.contextTokens.takeIf { it > 0 }?.toString() ?: contextTokens
        maxOutput = p.maxOutputTokens.takeIf { it > 0 }?.toString() ?: ""
    }

    fun build() = vm.settings.copy(
        provider = provider, baseUrl = baseUrl, model = model, apiKey = apiKey,
        githubToken = githubToken, gitName = gitName, gitEmail = gitEmail, autoApprove = autoApprove,
        contextTokens = (contextTokens.toIntOrNull() ?: 128_000).coerceIn(8_000, 2_000_000),
        freshOnStart = freshOnStart, smartApprove = smartApprove, allowRules = allowRules,
        promptCaching = promptCaching, maxSteps = (maxSteps.toIntOrNull() ?: 60).coerceIn(0, 1000),
        autoContinue = autoContinue, helperModel = helperModel, maxOutputTokens = maxOutput.toIntOrNull() ?: 0,
        fallbackProfileId = fallbackId, permissionRules = permissionRules, fileHooks = fileHooks,
        ecoMode = ecoMode, fontScale = fontScale, compactUi = compactUi,
    )

    // Автосохранение: через полсекунды после последнего изменения
    val snapshot = listOf(
        provider, baseUrl, model, apiKey, githubToken, gitName, gitEmail, autoApprove, contextTokens, freshOnStart,
        smartApprove, allowRules, promptCaching, maxSteps, permissionRules, fileHooks, maxOutput, helperModel,
        fallbackId, autoContinue, ecoMode, fontScale, compactUi, label, activeId, profiles,
    )
    var firstRun by remember { mutableStateOf(true) }
    var savedMark by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot) {
        if (firstRun) { firstRun = false; return@LaunchedEffect }
        delay(700)
        onSave(build(), withForm(), activeId)
        savedMark = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (savedMark) "Настройки · сохранено" else "Настройки") },
                navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(com.pocketcode.ui.AppIcons.Back, contentDescription = "Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ключи и модели", style = MaterialTheme.typography.titleMedium)
            Text(
                "Можно хранить несколько ключей (например, рабочий и личный) и переключаться между ними. Подпись нужна только тебе.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                withForm().forEach { p ->
                    FilterChip(
                        selected = p.id == activeId,
                        onClick = {
                            if (p.id != activeId) {
                                profiles = withForm()
                                load(profiles.first { it.id == p.id })
                            }
                        },
                        label = { Text(p.label) },
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = {
                        profiles = withForm()
                        val fresh = Profile(
                            java.util.UUID.randomUUID().toString(),
                            "Профиль ${profiles.size + 1}",
                            Provider.ANTHROPIC,
                            Provider.ANTHROPIC.defaultBaseUrl,
                            Provider.ANTHROPIC.defaultModel,
                            "",
                        )
                        profiles = profiles + fresh
                        load(fresh)
                    },
                    label = { Text("+ Новый") },
                )
            }
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Подпись профиля") },
                placeholder = { Text("Например: Рабочий Claude") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (withForm().size > 1) {
                TextButton(onClick = {
                    val rest = withForm().filter { it.id != activeId }
                    profiles = rest
                    load(rest.first())
                }) { Text("Удалить этот профиль") }
            }


            OutlinedButton(onClick = { showTemplates = !showTemplates }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showTemplates) "Скрыть шаблоны провайдеров" else "Шаблоны провайдеров (DeepSeek, OpenRouter, Gemini…)")
            }
            if (showTemplates) {
                com.pocketcode.PROVIDER_TEMPLATES.forEach { t ->
                    androidx.compose.material3.OutlinedCard(
                        onClick = {
                            provider = t.provider; baseUrl = t.baseUrl; model = t.model
                            contextTokens = t.context.toString()
                            maxOutput = t.maxOutput.takeIf { it > 0 }?.toString() ?: ""
                            if (apiKey.isBlank() && t.keyPlaceholder.isNotBlank()) apiKey = t.keyPlaceholder
                            if (label.startsWith("Профиль") || label == "Основной") label = t.name
                            showTemplates = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(t.name, style = MaterialTheme.typography.titleSmall)
                            Text(t.note, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("Шаблон заполняет адрес, модель и размеры; останется вставить свой ключ.", style = MaterialTheme.typography.bodySmall)
            }

            Text("Модель", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Provider.values().forEach { p ->
                    FilterChip(
                        selected = provider == p,
                        onClick = {
                            provider = p
                            baseUrl = p.defaultBaseUrl
                            model = p.defaultModel
                        },
                        label = { Text(p.label) },
                    )
                }
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Базовый URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Модель") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API-ключ") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.verifyProfile(formProfile()) },
                    enabled = vm.verifyingId == null,
                    modifier = Modifier.weight(1f),
                ) { Text(if (vm.verifyingId != null) "Проверяю…" else "Проверить модель") }
                OutlinedButton(onClick = onOpenCompare, modifier = Modifier.weight(1f)) { Text("Сравнить модели") }
            }
            vm.verifyText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            run {
                val u = vm.usageMonth(activeId)
                fun k(n: Long) = if (n >= 1000) "${n / 1000} тыс." else "$n"
                Text(
                    "Потрачено этим профилем за месяц: вход ${k(u[0])}, выход ${k(u[1])}, запросов ${u[3]}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Для OpenRouter укажи https://openrouter.ai/api/v1 и имя модели вроде anthropic/claude-sonnet-4.5.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = contextTokens,
                onValueChange = { contextTokens = it.filter(Char::isDigit).take(8) },
                label = { Text("Размер контекста этого профиля, токенов") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Когда история займёт около 70% этого размера, старая часть заменяется сводкой. " +
                    "Для Claude можно 200000, для небольших моделей укажи меньше.\n\n" +
                    "Что ставить:\n" +
                    "• Claude Sonnet/Opus/Haiku: 200000.\n" +
                    "• GPT-4.1 и Gemini 2.x: до 1000000, но на практике удобно 200000–400000: дешевле и быстрее.\n" +
                    "• GPT-4o, DeepSeek, Qwen, Llama: 128000 (если у модели меньше, поставь её значение).\n" +
                    "• Бесплатные и маленькие модели (minimax-flash:free и т.п.): 32000–64000, они теряются в длинном контексте.\n" +
                    "Лучше ставить на 10–20% меньше, чем у модели: оценка токенов приблизительная. Контекст меньше — модель отвечает точнее и дешевле, но чаще делает сводки; больше — помнит дольше, но каждый шаг дороже.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Экономия и вид", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Эко-режим")
                    Text(
                        "Один переключатель: контекст не больше 64 000, старые результаты инструментов обрезаются сразу, сводки делает модель-помощник. Дешевле в 2–4 раза, но модель помнит меньше.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = ecoMode, onCheckedChange = { ecoMode = it })
            }
            Text("Размер шрифта: ${(fontScale * 100).toInt()}%")
            Slider(value = fontScale, onValueChange = { fontScale = (Math.round(it * 20) / 20f) }, valueRange = 0.8f..1.4f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Компактный вид")
                    Text("Весь интерфейс на 10% мельче: влезает больше текста.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = compactUi, onCheckedChange = { compactUi = it })
            }

            Text("Контекст и память", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Кэширование контекста")
                    Text(
                        "Anthropic: длинная история кэшируется, повторные шаги заметно дешевле и быстрее. OpenRouter: кэшируется системная часть. " +
                            "OpenAI, DeepSeek и многие другие кэшируют повторяющееся начало запроса сами, этот переключатель на них не влияет.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = promptCaching, onCheckedChange = { promptCaching = it })
            }
            OutlinedTextField(
                value = maxOutput,
                onValueChange = { maxOutput = it.filter(Char::isDigit).take(6) },
                label = { Text("Макс. длина ответа этого профиля, токенов") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Сколько модель может написать за один шаг: от этого зависит, как большой файл она запишет за раз. Пусто: Claude 16384, " +
                    "у остальных провайдеров свой предел (у DeepSeek обычно 4–8 тыс.). Можно поставить 32000 или 64000, если модель это поддерживает, иначе провайдер вернёт ошибку.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = helperModel,
                onValueChange = { helperModel = it },
                label = { Text("Модель-помощник (необязательно)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Дешёвая модель того же провайдера и ключа (например claude-haiku-5-5 или gpt-4o-mini). Ей поручаются сводки контекста и подагент task, " +
                    "а основная модель делает всю остальную работу. Пусто — везде основная.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Резервный профиль", style = MaterialTheme.typography.titleSmall)
            Text(
                "Если у основной модели кончилась квота, превышен лимит запросов или ключ не принят, агент сам перейдёт на выбранный профиль и продолжит.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(selected = fallbackId.isEmpty(), onClick = { fallbackId = "" }, label = { Text("Нет") })
                profiles.filter { it.id != activeId }.forEach { p ->
                    androidx.compose.material3.FilterChip(selected = fallbackId == p.id, onClick = { fallbackId = p.id }, label = { Text(p.label) })
                }
            }
            OutlinedTextField(
                value = permissionRules,
                onValueChange = { permissionRules = it },
                label = { Text("Правила инструментов") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "По строке: allow, ask или deny, имя инструмента (или *) и необязательная часть описания вызова. Работает первое подходящее правило. " +
                    "Примеры:\ndeny bash rm -rf\nask git push\nallow edit_file\nask write_file src/\n" +
                    "deny — запретить, ask — всегда спрашивать, allow — не спрашивать (root и режим плана это не отменяет).",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { hooksMsg = vm.detectHooks() }, modifier = Modifier.fillMaxWidth()) { Text("Подобрать автопроверки для проекта") }
            hooksMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(
                value = fileHooks,
                onValueChange = { fileHooks = it },
                label = { Text("Хуки после правки файла") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "По строке: шаблон имени файла, затем => и команда. После write_file/edit_file подходящего файла команда выполняется " +
                    "в среде bash, а её вывод агент видит сразу. {file} — путь файла. Примеры:\n*.py => python3 -m py_compile {file}\n*.json => python3 -m json.tool {file} > /dev/null\n* => npm test --silent",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = maxSteps,
                onValueChange = { maxSteps = it.filter(Char::isDigit).take(4) },
                label = { Text("Шагов за один запуск (0 — без лимита)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Автопродолжение")
                    Text(
                        "Когда лимит шагов достигнут, агент сам получает «продолжай» и идёт дальше до конца задачи (не более 50 раз подряд). Очередь сообщений начнётся после этого.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = autoContinue, onCheckedChange = { autoContinue = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Новый чат при запуске")
                    Text(
                        "Приложение стартует с чистого чата. Если выключить, откроется последний чат проекта вместе с его историей, и модель продолжит с того же места.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = freshOnStart, onCheckedChange = { freshOnStart = it })
            }
            Text(
                "Каждый чат хранит свою историю и никак не связан с другими. Модель помнит только текущий чат.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = {
                    onResetContext()
                    resetDone = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (resetDone) "Контекст сброшен" else "Сбросить контекст текущего чата") }
            Text(
                "Сброс стирает память модели в текущем чате, переписка на экране остаётся. То же делает команда /clear в поле ввода, а /new открывает новый чат.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Git", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(
                value = githubToken,
                onValueChange = { githubToken = it },
                label = { Text("GitHub-токен (для clone/push приватных репозиториев)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = gitName,
                onValueChange = { gitName = it },
                label = { Text("Имя автора коммитов") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = gitEmail,
                onValueChange = { gitEmail = it },
                label = { Text("E-mail автора коммитов") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Безопасность", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Выполнять без подтверждения")
                    Text(
                        "Агент сам запускает команды и правит файлы. Включай, только если доверяешь задаче.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = autoApprove, onCheckedChange = { autoApprove = it })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Не спрашивать про безопасные команды")
                    Text(
                        "Команды, которые только читают (ls, cat, grep, git status, git log и подобные), выполняются без вопроса. Всё с записью, удалением и перенаправлением вывода по-прежнему спрашивает.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = smartApprove, onCheckedChange = { smartApprove = it })
            }
            OutlinedTextField(
                value = allowRules,
                onValueChange = { allowRules = it },
                label = { Text("Свои разрешённые команды (по одной в строке)") },
                placeholder = { Text("npm test\npython3 -m pytest") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Команда считается разрешённой, если начинается с одной из этих строк, например «npm test». Цепочки через && и | проверяются по каждой части.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Крупный ввод (удобнее одной рукой)")
                    Text("Поле ввода выше, шрифт в нём крупнее.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = vm.bigInput, onCheckedChange = { vm.setBigInputValue(it) })
            }
            Text("Тема", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("dark" to "Тёмная", "light" to "Светлая", "system" to "Как в системе").forEach { (v, l) ->
                    androidx.compose.material3.FilterChip(selected = vm.themeMode == v, onClick = { vm.setThemeModeValue(v) }, label = { Text(l) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AMOLED: чисто чёрный фон")
                    Text("Экономит заряд на OLED-экранах. Действует в тёмной теме.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = vm.amoled, onCheckedChange = { vm.setAmoledValue(it) })
            }
            Text("Цвет акцента", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (ACCENTS.map { Triple(it.id, it.title, it.dark) } + Triple("system", "Из обоев", androidx.compose.ui.graphics.Color(0xFF9E9E9E))).forEach { (id, title, color) ->
                    androidx.compose.material3.FilterChip(
                        selected = vm.palette == id, onClick = { vm.setPaletteValue(id) },
                        leadingIcon = { androidx.compose.foundation.layout.Box(Modifier.size(14.dp).background(color, androidx.compose.foundation.shape.CircleShape)) },
                        label = { Text(title) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Защита приложения")
                    Text(
                        "При запуске и после минуты в фоне просит PIN, графический ключ или отпечаток, которые стоят на телефоне. Нужна блокировка экрана в настройках Android.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = vm.appLock, onCheckedChange = { vm.setAppLockValue(it) })
            }

            Text("Обновления", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text("Установлено: ${vm.currentVersion}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Каждая сборка из GitHub выкладывается в релизы. Если репозиторий публичный, токен не нужен. Для приватного нужен сохранённый GitHub-токен с правом Contents: read.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { vm.checkUpdate() },
                enabled = !vm.updateBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (vm.updateBusy) "Подожди…" else "Проверить обновления") }
            vm.updateStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (vm.updateInfo != null) {
                Button(
                    onClick = { vm.installUpdate() },
                    enabled = !vm.updateBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Скачать и установить") }
            }

            Text("Все изменения сохраняются автоматически.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }
}
