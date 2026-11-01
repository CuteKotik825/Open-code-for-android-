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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Экранные координаты элементов, которые подсвечивает тур. */
object TourAnchors {
    val rects = androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>()
}

fun Modifier.tourAnchor(key: String): Modifier =
    this.onGloballyPositioned { TourAnchors.rects[key] = it.boundsInRoot() }

private class TourStep(val anchor: String?, val title: String, val body: String)

private val TOUR = listOf(
    TourStep(null, "Добро пожаловать в PocketCode", "Кодинг-агент прямо на телефоне. За минуту покажем, где что находится. Всё можно пропустить."),
    TourStep("input", "Сюда пишешь задачу", "Опиши обычными словами, что сделать с проектом. Можно приложить картинку и отправить: агент сам читает файлы, правит их и запускает команды."),
    TourStep("quick", "Быстрые кнопки", "Остановить агента, открыть файлы или терминал, посмотреть его правки, начать новый чат. Всё под рукой над полем ввода."),
    TourStep("project", "Текущий проект", "Агент работает только в этой папке. Нажми на название, чтобы сменить проект или создать новый."),
    TourStep("nav_project", "Раздел «Проект»", "Файлы, поиск, терминал, git и «Правки агента»: там видно, что он изменил, и можно откатить."),
    TourStep("nav_agent", "Раздел «Агент»", "Модель и ключи, OpenCode, история чатов, навыки и MCP, что агент помнит в контексте. Начни с «Модель и ключи»: без ключа или OpenCode он не заработает."),
    TourStep("nav_more", "Раздел «Ещё»", "Настройки и тема, среда Linux, устройства и root, обновления, резервная копия, диагностика и поддержка в Telegram."),
    TourStep(null, "Linux для агента", "В «Ещё → Среда Linux» ставится мини-Linux (около 4 МБ): с ним агент может ставить python, git и node. Скачивание идёт в фоне, с экрана можно выйти. Если грузится долго, включи VPN."),
)

/** Интерактивные подсказки первого запуска: затемнение с подсветкой нужного места. Пропуск только с подтверждением. */
@Composable
fun TourOverlay(onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var confirmSkip by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var area by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val st = TOUR[step]
    val raw = st.anchor?.let { TourAnchors.rects[it] }
    val target = raw?.translate(-origin.x, -origin.y)
    androidx.activity.compose.BackHandler { confirmSkip = true }
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "tour").animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1300)),
        label = "pulse",
    )
    val ring = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxSize()
            .androidx_root { origin = it.first; area = it.second }
            .androidx_swallowTouches(),
    ) {
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxSize().graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen),
        ) {
            drawRect(Color.Black.copy(alpha = 0.78f))
            if (target != null) {
                val pad = 10.dp.toPx()
                val tl = androidx.compose.ui.geometry.Offset(target.left - pad, target.top - pad)
                val sz = androidx.compose.ui.geometry.Size(target.width + 2 * pad, target.height + 2 * pad)
                val r = androidx.compose.ui.geometry.CornerRadius(minOf(sz.width, sz.height) / 2f)
                drawRoundRect(Color.Transparent, tl, sz, r, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
                val grow = 14.dp.toPx() * pulse
                drawRoundRect(
                    ring.copy(alpha = 1f - pulse),
                    androidx.compose.ui.geometry.Offset(tl.x - grow, tl.y - grow),
                    androidx.compose.ui.geometry.Size(sz.width + 2 * grow, sz.height + 2 * grow),
                    androidx.compose.ui.geometry.CornerRadius(minOf(sz.width, sz.height) / 2f + grow),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()),
                )
            }
        }
        val low = target != null && target.center.y > area.height / 2f
        val boxAlign = when {
            target == null -> Alignment.Center
            low -> Alignment.TopCenter
            else -> Alignment.BottomCenter
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.align(boxAlign).fillMaxWidth().padding(horizontal = 20.dp, vertical = if (target == null) 0.dp else 64.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${step + 1} из ${TOUR.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { confirmSkip = true }) { Text("Пропустить") }
                }
                Text(st.title, style = MaterialTheme.typography.titleLarge)
                Text(st.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (step > 0) androidx.compose.material3.OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) { Text("Назад") }
                    Button(onClick = { if (step == TOUR.lastIndex) onDone() else step++ }, modifier = Modifier.weight(1f)) {
                        Text(if (step == TOUR.lastIndex) "Готово" else "Далее")
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

private fun Modifier.androidx_root(f: (Pair<androidx.compose.ui.geometry.Offset, androidx.compose.ui.unit.IntSize>) -> Unit): Modifier =
    this.onGloballyPositioned { f(it.positionInRoot() to it.size) }

private fun Modifier.androidx_swallowTouches(): Modifier =
    this.pointerInput(Unit) { detectTapGestures { } }

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
                if (info.notes.isNotBlank()) Text(info.notes.take(1500), style = MaterialTheme.typography.bodySmall)
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
