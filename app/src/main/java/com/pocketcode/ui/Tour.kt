package com.pocketcode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private class TourPage(val icon: ImageVector, val title: String, val body: String)

private val TOUR = listOf(
    TourPage(AppIcons.Bolt, "Добро пожаловать в PocketCode", "Кодинг-агент прямо на телефоне: читает и правит файлы, запускает команды, работает с git. Ниже за минуту покажем, где что лежит. Всё это можно пропустить."),
    TourPage(AppIcons.Grid, "Сначала подключи модель", "Ещё → Настройки: вставь ключ любого провайдера. Или открой Агент → OpenCode: там можно работать с бесплатными моделями без своего ключа."),
    TourPage(AppIcons.Chat, "Чат", "Пиши задачу обычными словами. Над полем ввода быстрые кнопки: остановить, файлы, терминал, правки, новый чат. Кнопка «Чат» внизу всегда возвращает сюда."),
    TourPage(AppIcons.Folder, "Проект", "Проекты, файлы, поиск, терминал, git. Раздел «Правки агента» показывает, что он изменил, и позволяет откатить по блокам."),
    TourPage(AppIcons.Bolt, "Агент", "Беседы и аккаунты OpenCode, история чатов, навыки и MCP, что агент сейчас «помнит» в контексте и сравнение моделей."),
    TourPage(AppIcons.Grid, "Ещё", "Настройки и тема, среда Linux, устройства и root, синхронизация, обновления, резервная копия, диагностика и поддержка в Telegram."),
    TourPage(AppIcons.Folder, "Linux для агента", "Ещё → Среда Linux: установи мини-Linux (около 4 МБ), и агент сможет ставить python, git, node. Скачивание идёт в фоне, с экрана можно выйти. Если грузится долго, включи VPN."),
)

/** Подсказки первого запуска. Пропустить можно в любой момент, но только после подтверждения. */
@Composable
fun TourDialog(onDone: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    var confirmSkip by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { confirmSkip = true }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { confirmSkip = true }) { Text("Пропустить") }
                }
                val p = TOUR[page]
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(96.dp).clip(RoundedCornerShape(30.dp)).background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                            ),
                        ),
                        contentAlignment = Alignment.Center,
                    ) { androidx.compose.material3.Icon(p.icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                    Text(p.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 28.dp, bottom = 12.dp))
                    Text(p.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TOUR.indices.forEach { i ->
                        Box(
                            Modifier.size(if (i == page) 10.dp else 7.dp).clip(CircleShape)
                                .background(if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (page > 0) androidx.compose.material3.OutlinedButton(onClick = { page-- }, modifier = Modifier.weight(1f)) { Text("Назад") }
                    Button(onClick = { if (page == TOUR.lastIndex) onDone() else page++ }, modifier = Modifier.weight(1f)) {
                        Text(if (page == TOUR.lastIndex) "Готово" else "Далее")
                    }
                }
            }
        }
    }
    if (confirmSkip) {
        AlertDialog(
            onDismissRequest = { confirmSkip = false },
            title = { Text("Пропустить подсказки?") },
            text = { Text("Их можно открыть снова: Ещё → Подсказки.") },
            confirmButton = { TextButton(onClick = { confirmSkip = false; onDone() }) { Text("Да, пропустить") } },
            dismissButton = { TextButton(onClick = { confirmSkip = false }) { Text("Продолжить") } },
        )
    }
}

/** Полоса сверху на любом экране: идёт установка Linux или скачивание обновления, либо есть итог. */
@Composable
fun BackgroundBanner(vm: ChatController) {
    val busy = vm.linuxBusy || vm.updateBusy
    val text: String? = when {
        vm.linuxBusy -> "Linux: " + (vm.linuxStatus ?: "устанавливаю…")
        vm.updateBusy -> "Обновление: " + (vm.updateStatus ?: "работаю…")
        vm.updateReady != null -> "Обновление скачано"
        vm.doneNote != null -> vm.doneNote
        else -> null
    }
    if (text == null) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2)
                if (!busy && vm.updateReady != null) TextButton(onClick = { vm.applyUpdate() }) { Text("Установить") }
                else if (!busy && vm.doneNote != null) TextButton(onClick = { vm.clearDoneNote() }) { Text("Ок") }
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                Text("Можно пользоваться приложением. С VPN обычно быстрее.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
            }
        }
    }
}

/** Предложение обновиться при запуске. */
@Composable
fun UpdatePromptDialog(vm: ChatController) {
    val info = vm.updateInfo ?: return
    AlertDialog(
        onDismissRequest = { vm.dismissUpdatePrompt() },
        title = { Text("Доступно обновление") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Новая сборка ${info.build}" + (if (info.size > 0) " (${info.size / 1048576} МБ)" else "") + ". У тебя ${vm.currentVersion}.")
                if (info.notes.isNotBlank()) Text(info.notes.take(400), style = MaterialTheme.typography.bodySmall)
                Text("Скачивание пойдёт в фоне, можно пользоваться приложением. С включённым VPN обычно быстрее. Установишь, когда скачается.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { vm.dismissUpdatePrompt(); vm.installUpdate() }) { Text("Скачать") } },
        dismissButton = { TextButton(onClick = { vm.dismissUpdatePrompt() }) { Text("Позже") } },
    )
}

/** Карточка приветствия: только пока пользователь её не закрыл. */
@Composable
fun WelcomeCard(vm: ChatController, onOpenGroup: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(
            androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceContainer),
            ),
        ).padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Добро пожаловать в PocketCode", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Вопросы, идеи и баги: группа в Telegram. Там же можно спросить, как настроить Linux или модель.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenGroup) { Text("Открыть группу") }
                TextButton(onClick = { vm.hideWelcome() }) { Text("Скрыть") }
            }
        }
    }
}
