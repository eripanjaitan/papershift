package com.papershift.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// PRD §8 — dark theme only in v1.0, regardless of the system setting.
private val Surface = Color(0xFF14121A)
private val Accent = Color(0xFF7C4DFF)

/** Accent for text on [Surface]; #7C4DFF itself is only ≈3.8:1 there (PRD §8). */
val AccentText = Color(0xFFB39DFF)

private val PaperShiftColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Accent,
    onPrimaryContainer = Color.White,
    secondary = AccentText,
    onSecondary = Surface,
    secondaryContainer = Color(0xFF2E2547),
    onSecondaryContainer = Color.White,
    background = Surface,
    onBackground = Color(0xFFE7E1EF),
    surface = Surface,
    onSurface = Color(0xFFE7E1EF),
    surfaceVariant = Color(0xFF24202E),
    onSurfaceVariant = Color(0xFFCAC3D6),
    surfaceContainer = Color(0xFF1C1924),
    surfaceContainerHigh = Color(0xFF221F2B),
    surfaceContainerHighest = Color(0xFF2B2735),
    outline = Color(0xFF948E9F),
    outlineVariant = Color(0xFF494453),
)

@Composable
fun PaperShiftTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PaperShiftColors,
        typography = PaperShiftTypography,
        content = content,
    )
}
