package com.pocketcode.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class Accent(val id: String, val title: String, val dark: Color, val light: Color)

val ACCENTS = listOf(
    Accent("blue", "Синий", Color(0xFF8AB4F8), Color(0xFF2F5DA8)),
    Accent("teal", "Бирюзовый", Color(0xFF4DD0C4), Color(0xFF00796B)),
    Accent("violet", "Фиолетовый", Color(0xFFB69DF8), Color(0xFF6A4CC4)),
    Accent("green", "Зелёный", Color(0xFF7BD88F), Color(0xFF2E7D45)),
    Accent("orange", "Оранжевый", Color(0xFFFFB067), Color(0xFFC25E00)),
    Accent("rose", "Розовый", Color(0xFFF48FB1), Color(0xFFB4246B)),
)

fun accentById(id: String): Accent? = ACCENTS.firstOrNull { it.id == id }

/** Своя палитра: одинакова на всех телефонах. amoled — чистый чёрный фон. */
fun buildScheme(dark: Boolean, amoled: Boolean, a: Accent): ColorScheme {
    val white = Color.White
    val black = Color.Black
    return if (dark) {
        val base = if (amoled) black else Color(0xFF111318)
        val c = a.dark
        darkColorScheme(
            primary = c,
            onPrimary = Color(0xFF0B0F14),
            primaryContainer = lerp(base, c, 0.30f),
            onPrimaryContainer = lerp(c, white, 0.6f),
            secondary = lerp(c, white, 0.2f),
            onSecondary = Color(0xFF0B0F14),
            secondaryContainer = lerp(base, c, 0.18f),
            onSecondaryContainer = lerp(c, white, 0.55f),
            tertiary = c,
            background = base,
            onBackground = Color(0xFFE6E7EB),
            surface = base,
            onSurface = Color(0xFFE6E7EB),
            surfaceVariant = lerp(base, white, if (amoled) 0.12f else 0.13f),
            onSurfaceVariant = Color(0xFFABB1BD),
            surfaceContainerLowest = lerp(base, black, 0.4f),
            surfaceContainerLow = lerp(base, white, 0.035f),
            surfaceContainer = lerp(base, white, 0.06f),
            surfaceContainerHigh = lerp(base, white, 0.09f),
            surfaceContainerHighest = lerp(base, white, 0.12f),
            outline = Color(0xFF666D7A),
            outlineVariant = lerp(base, white, 0.14f),
        )
    } else {
        val base = Color(0xFFF8F9FC)
        val c = a.light
        lightColorScheme(
            primary = c,
            onPrimary = white,
            primaryContainer = lerp(white, c, 0.16f),
            onPrimaryContainer = lerp(c, black, 0.45f),
            secondary = lerp(c, black, 0.1f),
            secondaryContainer = lerp(white, c, 0.10f),
            onSecondaryContainer = lerp(c, black, 0.4f),
            tertiary = c,
            background = base,
            surface = base,
            onSurface = Color(0xFF1A1C20),
            surfaceVariant = lerp(base, black, 0.07f),
            onSurfaceVariant = Color(0xFF4A505C),
            surfaceContainerLowest = white,
            surfaceContainerLow = lerp(base, black, 0.02f),
            surfaceContainer = lerp(base, black, 0.04f),
            surfaceContainerHigh = lerp(base, black, 0.06f),
            surfaceContainerHighest = lerp(base, black, 0.08f),
            outline = Color(0xFF8A909C),
            outlineVariant = lerp(base, black, 0.12f),
        )
    }
}

/** Динамические цвета из обоев, но с чёрным фоном для AMOLED. */
fun amoledize(s: ColorScheme): ColorScheme = s.copy(
    background = Color.Black, surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A), surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF181818), surfaceContainerHighest = Color(0xFF202020),
)

val AppTypography = androidx.compose.material3.Typography(
    headlineMedium = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    titleSmall = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    labelMedium = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)
