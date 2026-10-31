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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val VPN_NOTE = "Скачивание может идти быстрее с включённым VPN: часть провайдеров режет такие загрузки."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(AppIcons.Back, contentDescription = "Назад") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

/** Среда Linux: установка, переключение bash, пакеты. Установка идёт в фоне, с экрана можно выйти. */
@Composable
fun LinuxScreen(vm: ChatController, onBack: () -> Unit) = Page("Среда Linux", onBack) {
    if (!vm.linuxSupported) {
        Text("На этом телефоне Linux-среда недоступна (нужен 64- или 32-битный ARM).", style = MaterialTheme.typography.bodyMedium)
        return@Page
    }
    Text(
        if (vm.linuxInstalled) {
            "Alpine Linux установлен. Агент выполняет bash внутри него: настоящий sh, apk, свои пакеты. Папка проекта доступна как /work."
        } else {
            "Скачает Alpine Linux (около 4 МБ) и запустит его через proot. Тогда в bash можно ставить python, git, node, gcc командой apk add."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    if (vm.linuxBusy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Можно выйти с этого экрана и пользоваться приложением: установка продолжится, прогресс виден сверху на любом экране.", style = MaterialTheme.typography.bodySmall)
    }
    if (!vm.linuxInstalled) {
        Text(VPN_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Button(
            onClick = { vm.installLinux() },
            enabled = !vm.linuxBusy && !vm.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.linuxBusy) "Устанавливаю…" else "Установить Linux") }
    } else {
        Text("Где выполнять команды агента", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = vm.linuxEnabled, onClick = { vm.chooseLinuxShell(true) }, label = { Text("bash в Linux") })
            FilterChip(selected = !vm.linuxEnabled, onClick = { vm.chooseLinuxShell(false) }, label = { Text("bash Android") })
        }
        Text(
            if (vm.linuxEnabled) "Сейчас команды агента идут в настоящий Linux: можно ставить программы (apk add), есть python, git, node."
            else "Сейчас команды идут в простую оболочку Android: только базовые команды, без python, git и node.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = { vm.installLinuxTools() },
            enabled = !vm.linuxBusy && !vm.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.linuxBusy) "Подожди…" else "Поставить python, git, node") }
        TextButton(onClick = { vm.removeLinux() }, enabled = !vm.linuxBusy && !vm.running) { Text("Удалить Linux") }
    }
    vm.linuxStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** Версия, проверка и установка обновлений. */
@Composable
fun UpdateScreen(vm: ChatController, onBack: () -> Unit) = Page("Обновления", onBack) {
    Text("Установлено: ${vm.currentVersion}", style = MaterialTheme.typography.titleMedium)
    Text(
        "Каждая сборка из GitHub выкладывается в релизы. Если репозиторий публичный, токен не нужен. Для приватного нужен сохранённый GitHub-токен с правом Contents: read.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Проверять при запуске")
            Text("Если есть новая сборка, приложение предложит обновиться.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = vm.autoUpdate, onCheckedChange = { vm.setAutoUpdateValue(it) })
    }
    OutlinedButton(onClick = { vm.checkUpdate() }, enabled = !vm.updateBusy, modifier = Modifier.fillMaxWidth()) {
        Text(if (vm.updateBusy) "Подожди…" else "Проверить обновления")
    }
    if (vm.updateBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    vm.updateStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (vm.updateInfo != null && vm.updateReady == null) {
        Text(VPN_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Button(onClick = { vm.installUpdate() }, enabled = !vm.updateBusy, modifier = Modifier.fillMaxWidth()) { Text("Скачать") }
        Text("Можно выйти с этого экрана: скачивание пойдёт в фоне, сверху появится кнопка «Установить».", style = MaterialTheme.typography.labelSmall)
    }
    if (vm.updateReady != null) {
        Button(onClick = { vm.applyUpdate() }, modifier = Modifier.fillMaxWidth()) { Text("Установить") }
    }
    Text(
        "После установки сборки с другой подписью Android попросит сначала удалить старое приложение. Сохрани копию настроек (Ещё → Резервная копия).",
        style = MaterialTheme.typography.labelSmall,
    )
}

/** Резервная копия настроек: сохранить и восстановить. */
@Composable
fun BackupScreen(vm: ChatController, onBack: () -> Unit, onExport: () -> Unit, onImport: () -> Unit) = Page("Резервная копия", onBack) {
    Text(
        "Профили с ключами, правила, хуки, MCP, расписания, устройства и общие навыки одним файлом. Для переноса на другой телефон или переустановки. " +
            "Файл содержит секреты, не отправляй его никому.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = vm.backupPassword, onValueChange = { vm.backupPassword = it }, singleLine = true,
        label = { Text("Пароль для шифрования (необязательно)") },
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Text("С паролем файл шифруется (AES-256). Без пароля при восстановлении не откроется, не забудь его.", style = MaterialTheme.typography.labelSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onExport) { Text("Сохранить копию") }
        OutlinedButton(onClick = onImport) { Text("Восстановить") }
    }
    vm.backupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
