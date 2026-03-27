package com.enki.connect.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Enki Design System — shared color palette.
 * Synced with Command Center ui/src/app.css.
 *
 * These colors MUST match the web UI. When updating, sync both files.
 */

// ─── Brand Colors (match app.css --color-enki-*) ────────────────────────
val EnkiBlue = Color(0xFF3B82F6)       // Primary — hsl(217.2 91.2% 59.8%)
val EnkiGreen = Color(0xFF22C55E)      // Success — hsl(142 71% 45%)
val EnkiPurple = Color(0xFF8B5CF6)     // Accent — hsl(271 91% 65%)
val EnkiCyan = Color(0xFF06B6D4)       // Info — hsl(189 94% 43%)
val EnkiOrange = Color(0xFFF97316)     // Warning — hsl(24 95% 53%)
val EnkiRed = Color(0xFFEF4444)        // Error/Danger — hsl(350 89% 55%)

// ─── Severity Colors (match app.css --color-severity-*) ─────────────────
val SeverityCritical = Color(0xFFEF4444)   // Red
val SeverityHigh = Color(0xFFF97316)       // Orange
val SeverityMedium = Color(0xFFEAB308)     // Yellow
val SeverityLow = Color(0xFF3B82F6)        // Blue
val SeverityInfo = Color(0xFF6B7280)       // Gray

// ─── Dark Theme (default) ───────────────────────────────────────────────
private val DarkColors = darkColorScheme(
    primary = EnkiBlue,
    onPrimary = Color.White,
    secondary = EnkiPurple,
    onSecondary = Color.White,
    tertiary = EnkiGreen,
    onTertiary = Color.White,
    background = Color(0xFF0A0A0F),        // match app.css --background dark
    surface = Color(0xFF111118),            // match app.css --card dark
    surfaceVariant = Color(0xFF1A1A24),     // match app.css --muted dark
    onBackground = Color(0xFFFAFAFA),       // match app.css --foreground dark
    onSurface = Color(0xFFFAFAFA),
    onSurfaceVariant = Color(0xFF9CA3AF),   // match app.css --muted-foreground dark
    outline = Color(0xFF2A2A36),            // match app.css --border dark
    outlineVariant = Color(0xFF1E1E2A),
    error = EnkiRed,
    onError = Color.White,
)

// ─── Light Theme ────────────────────────────────────────────────────────
private val LightColors = lightColorScheme(
    primary = EnkiBlue,
    onPrimary = Color.White,
    secondary = EnkiPurple,
    tertiary = EnkiGreen,
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0A0A0F),
    onSurface = Color(0xFF0A0A0F),
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0xFFE2E8F0),
    error = EnkiRed,
)

@Composable
fun EnkiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
