package com.pocketcode.ui

import androidx.compose.foundation.horizontalScroll
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
    var contextTokens by remember { mutableStateOf(initial.contextTokens.toString()) }
    var freshOnStart by remember { mutableStateOf(initial.freshOnStart) }
    var smartApprove by remember { mutableStateOf(initial.smartApprove) }
    var allowRules by remember { mutableStateOf(initial.allowRules) }
    var resetDone by remember { mutableStateOf(false) }
    var promptCaching by remember { mutableStateOf(initial.promptCaching) }

    // Профили ключей: форма ниже всегда редактирует активный профиль
    var profiles by remember { mutableStateOf(vm.profiles) }
    var activeId by remember { mutableStateOf(vm.activeProfileId) }
    var label by remember { mutableStateOf(vm.profiles.firstOrNull { it.id == vm.activeProfileId }?.label ?: "Основной") }
    fun formProfile() = Profile(activeId, label.ifBlank { "Без названия" }, provider, baseUrl.trim(), model.trim(), apiKey.trim())
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
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } },
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
            Text(
                "Для OpenRouter укажи https://openrouter.ai/api/v1 и имя модели вроде anthropic/claude-sonnet-4.5.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = contextTokens,
                onValueChange = { contextTokens = it.filter(Char::isDigit).take(8) },
                label = { Text("Размер контекста модели, токенов") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Когда история займёт около 70% этого размера, старая часть заменяется сводкой. " +
                    "Для Claude можно 200000, для небольших моделей укажи меньше.",
                style = MaterialTheme.typography.bodySmall,
            )

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

            Button(
                onClick = {
                    val finalProfiles = withForm()
                    onSave(
                        Settings(
                            provider = provider,
                            baseUrl = baseUrl,
                            model = model,
                            apiKey = apiKey,
                            githubToken = githubToken,
                            gitName = gitName,
                            gitEmail = gitEmail,
                            autoApprove = autoApprove,
                            contextTokens = (contextTokens.toIntOrNull() ?: 128_000).coerceIn(8_000, 2_000_000),
                            freshOnStart = freshOnStart,
                            smartApprove = smartApprove,
                            allowRules = allowRules,
                            promptCaching = promptCaching,
                        ),
                        finalProfiles,
                        activeId,
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }
        }
    }
}
