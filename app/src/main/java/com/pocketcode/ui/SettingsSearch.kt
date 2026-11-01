package com.pocketcode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/** Пункт поиска: куда вести (screen, focus — раздел, к которому прокрутить) и по каким словам находить. */
class SettingEntry(val title: String, val where: String, val words: String, val screen: String, val focus: String? = null)

val SETTING_INDEX = listOf(
    SettingEntry("Ключи API и профили", "Агент → Модель и ключи", "ключ api провайдер профиль добавить openai deepseek openrouter anthropic токен", "MODELS", "keys"),
    SettingEntry("Выбор модели и адрес API", "Агент → Модель и ключи", "модель base url адрес сервер имя модели провайдер", "MODELS", "model"),
    SettingEntry("Эко-режим", "Агент → Модель и ключи", "эко экономия токенов дешевле контекст", "MODELS", "eco"),
    SettingEntry("Размер контекста, кэш, резервный профиль", "Агент → Модель и ключи", "контекст память кэширование сводка резервный профиль хуки автопроверки максимум шагов", "MODELS", "ctx"),
    SettingEntry("Размер шрифта и компактный вид", "Ещё → Настройки", "шрифт размер текста компактный вид мельче крупнее", "SETTINGS", "view"),
    SettingEntry("Git: автор коммитов и токен GitHub", "Ещё → Настройки", "git гитхаб github токен автор коммитов имя email почта", "SETTINGS", "git"),
    SettingEntry("Безопасность: подтверждение команд", "Ещё → Настройки", "безопасность подтверждение без подтверждения безопасные команды разрешённые команды правила запрет", "SETTINGS", "safe"),
    SettingEntry("Тема: тёмная, светлая, AMOLED", "Ещё → Настройки", "тема тёмная светлая темная amoled амолед чёрный фон", "SETTINGS", "theme"),
    SettingEntry("Цвет акцента и защита приложения", "Ещё → Настройки", "цвет акцент палитра обои защита приложения pin пароль отпечаток блокировка", "SETTINGS", "accent"),
    SettingEntry("Беседы и аккаунты OpenCode", "Агент → OpenCode", "opencode беседа сессия аккаунт диалоги список", "OCSESSIONS"),
    SettingEntry("Лимит загрузки бесед OpenCode", "Агент → OpenCode", "лимит загрузка долго грузится беседы список сообщений показывать быстрее", "OCSESSIONS"),
    SettingEntry("Прокси и VPN для OpenCode", "Агент → OpenCode", "прокси proxy vpn сеть соединение econnreset таймаут", "OCSESSIONS"),
    SettingEntry("Лимиты и бюджет запросов OpenCode", "Агент → OpenCode", "429 лимит бюджет запросов в день повтор запасная модель", "OCSESSIONS"),
    SettingEntry("Доступ OpenCode вне проекта / root", "Агент → OpenCode", "доступ вне папки проекта root рут proc разрешить запретить спрашивать external directory", "OCSESSIONS"),
    SettingEntry("Автосжатие беседы OpenCode", "Агент → OpenCode", "сжатие compact автосжатие контекст беседа", "OCSESSIONS"),
    SettingEntry("Среда Linux", "Ещё → Среда Linux", "linux линукс alpine proot установить bash python git node apk", "LINUX"),
    SettingEntry("Устройства, ADB и root", "Ещё → Устройства", "adb root рут устройство подключить usb беспроводная отладка", "DEVICES"),
    SettingEntry("Синхронизация и пульт", "Ещё → Синхронизация", "синхронизация пульт удалённо другой телефон файлы", "REMOTE"),
    SettingEntry("Обновления", "Ещё → Обновления", "обновление версия сборка проверить обновить автопроверка", "UPDATE"),
    SettingEntry("Резервная копия", "Ещё → Резервная копия", "резервная копия бэкап backup восстановить перенос пароль шифрование", "BACKUP"),
    SettingEntry("Диагностика и расход токенов", "Ещё → Диагностика", "диагностика самопроверка отчёт ошибка расход токенов цена лимит очистка кэш место батарея фон", "DIAG"),
    SettingEntry("Навыки, команды, MCP", "Агент → Навыки", "навыки skills команды mcp плагины инструкции", "PLUGINS"),
    SettingEntry("Что в контексте", "Агент → Контекст", "контекст память токены сколько весит", "CONTEXT"),
    SettingEntry("Сравнить модели", "Агент → Сравнить модели", "сравнить модели несколько", "COMPARE"),
    SettingEntry("История чатов", "Агент → История", "история чаты прошлые беседы", "SESSIONS"),
    SettingEntry("Проекты", "Проект → Проекты", "проект создать клонировать github папка снимок откат zip", "PROJECTS"),
    SettingEntry("Файлы", "Проект → Файлы", "файлы редактор просмотр", "FILES"),
    SettingEntry("Поиск по проекту", "Проект → Поиск", "поиск найти todo текст", "SEARCH"),
    SettingEntry("Правки агента", "Проект → Правки", "правки diff откат изменения", "DIFF"),
    SettingEntry("Терминал и запуск", "Проект → Терминал", "терминал команды запуск консоль", "TERMINAL"),
    SettingEntry("Git и GitHub", "Проект → Git", "git коммит ветка pr сборка actions", "GIT"),
)

/** Поиск по всем разделам и настройкам: нажми на результат, и откроется нужный экран, прокрутив к разделу. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSearchScreen(onBack: () -> Unit, onOpen: (SettingEntry) -> Unit) {
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val tokens = q.lowercase().split(' ', ',').filter { it.isNotBlank() }
    val found = if (tokens.isEmpty()) SETTING_INDEX
    else SETTING_INDEX.filter { e -> val hay = (e.title + " " + e.words + " " + e.where).lowercase(); tokens.all { it in hay } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Поиск") },
                navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(AppIcons.Back, contentDescription = "Назад") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                label = { Text("Что ищем: тема, прокси, ключ, лимит…") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            if (found.isEmpty()) Text("Ничего не нашлось. Попробуй другое слово: «тема», «ключ», «лимит», «обновление».", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(found) { e ->
                    Card(
                        onClick = { onOpen(e) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(e.title, style = MaterialTheme.typography.titleSmall)
                            Text(e.where, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
