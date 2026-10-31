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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Устройства для ADB-отладки и root. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(vm: ChatController, onBack: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var pairAddress by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Устройства и root") },
                navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ADB: отладка устройств", style = MaterialTheme.typography.titleMedium)
            Text(
                "Агент сможет управлять другими Android-устройствами, как с ПК: shell, logcat, установка APK, копирование файлов. " +
                    "Работает через настоящий adb в Linux, поэтому Linux должен быть установлен. На устройстве включи «Отладка по Wi-Fi» " +
                    "(Параметры разработчика). Безопасные команды чтения выполняются сразу, остальные агент подтверждает с тобой.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { vm.installAdb() },
                enabled = !vm.deviceBusy && vm.linuxInstalled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (vm.deviceBusy) "Подожди…" else "Установить ADB в Linux") }
            if (!vm.linuxInstalled) {
                Text("Сначала установи Linux: Проекты и файлы → Среда Linux.", style = MaterialTheme.typography.bodySmall)
            }

            Text("Сопряжение (Android 11 и новее)", style = MaterialTheme.typography.titleSmall)
            Text(
                "На устройстве: Отладка по Wi-Fi → «Подключение устройства с помощью кода сопряжения». Введи показанные адрес с портом и код.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = pairAddress,
                onValueChange = { pairAddress = it },
                label = { Text("Адрес сопряжения, например 192.168.1.5:37000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pairCode,
                onValueChange = { pairCode = it.filter(Char::isDigit) },
                label = { Text("Код сопряжения") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { vm.adbPair(pairAddress, pairCode) },
                enabled = !vm.deviceBusy && pairAddress.isNotBlank() && pairCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сопрячь") }

            Text("Мои устройства", style = MaterialTheme.typography.titleSmall)
            Text(
                "Адрес подключения (не тот, что для сопряжения) показан на главном экране «Отладки по Wi-Fi». Для обычного adb tcpip это порт 5555.",
                style = MaterialTheme.typography.bodySmall,
            )
            vm.devices.forEach { d ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.label)
                            Text(d.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { vm.testDevice(d) }, enabled = !vm.deviceBusy) { Text("Проверить") }
                        TextButton(onClick = { vm.removeDevice(d.id) }) { Text("Удалить") }
                    }
                }
            }
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Подпись, например «Старый планшет»") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Адрес, например 192.168.1.5:5555") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    vm.addDevice(label, address)
                    if (vm.devices.any { it.address == address.trim() }) {
                        label = ""
                        address = ""
                    }
                },
                enabled = address.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Добавить устройство") }

            Text("Root на этом телефоне", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Если на телефоне есть root, агент сможет выполнять команды от root вне папки проекта. Каждая такая команда " +
                    "всегда спрашивает подтверждение, даже если включено «Выполнять без подтверждения». " +
                    "Выше по умолчанию команды вне папки проекта тоже не выполняются без вопроса.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = vm.rootEnabled, onClick = { vm.chooseRoot(true) }, label = { Text("Root включён") })
                FilterChip(selected = !vm.rootEnabled, onClick = { vm.chooseRoot(false) }, label = { Text("Выключен") })
            }
            OutlinedButton(
                onClick = { vm.checkRoot() },
                enabled = !vm.deviceBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Проверить root") }

            vm.deviceStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
