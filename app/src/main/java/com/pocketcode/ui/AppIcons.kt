package com.pocketcode.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Несколько значков по контурам Material (чтобы не тянуть тяжёлую библиотеку значков). */
object AppIcons {
    private fun make(name: String, d: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
            .build()

    val Chat by lazy { make("chat", "M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z") }
    val Folder by lazy { make("folder", "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z") }
    val Bolt by lazy { make("bolt", "M7 2v11h3v9l7-12h-4l4-8z") }
    val Grid by lazy { make("grid", "M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z") }
    val Back by lazy { make("back", "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z") }
    val Chevron by lazy { make("chevron", "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z") }
}
