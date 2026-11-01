package com.pocketcode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class HubItem(val icon: ImageVector, val title: String, val about: String, val badge: String? = null, val header: Boolean = false, val onClick: () -> Unit = {})

/** Заголовок группы внутри раздела. */
fun hubHeader(text: String) = HubItem(AppIcons.Chevron, text, "", null, true)

/** Раздел нижней панели: крупные карточки с пояснениями, куда это ведёт. */
@Composable
fun HubScreen(title: String, subtitle: String, items: List<HubItem>, onSearch: (() -> Unit)? = null) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceContainer),
                        ),
                    )
                    .padding(horizontal = 20.dp, vertical = 26.dp),
            ) {
                if (onSearch != null) androidx.compose.material3.IconButton(onClick = onSearch, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(androidx.compose.material.icons.Icons.Default.Search, contentDescription = "Поиск по настройкам", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column {
                    Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                }
            }
        }
        items(items) { it ->
            if (it.header) {
                Text(
                    it.title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp, top = 10.dp),
                )
                return@items
            }
            Card(
                onClick = it.onClick,
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Icon(it.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(it.title, style = MaterialTheme.typography.titleMedium)
                        Text(it.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    it.badge?.let { b -> Text(b, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    Icon(AppIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
