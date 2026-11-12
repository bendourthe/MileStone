package com.bendourthe.milestone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Background and surface are transparent so [AuroraBackground] shows through; containers carry their own teal. */
private val AuroraColors = darkColorScheme(
    primary = AuroraGreen,
    onPrimary = AbyssTeal,
    primaryContainer = Color(0xFF145A55),
    onPrimaryContainer = Mist,
    secondary = Gold,
    onSecondary = Ink,
    secondaryContainer = Color(0xFF1C4C55),
    onSecondaryContainer = Mist,
    tertiary = AuroraCyan,
    onTertiary = AbyssTeal,
    background = Color.Transparent,
    onBackground = Mist,
    surface = Color.Transparent,
    onSurface = Mist,
    surfaceVariant = Color(0xFF1A464C),
    onSurfaceVariant = MistMuted,
    surfaceContainerLowest = Color(0xB303161A),
    surfaceContainerLow = Color(0xB3082D33),
    surfaceContainer = Color(0xF2051E23),
    surfaceContainerHigh = Color(0xFF0E4047),
    surfaceContainerHighest = Color(0xFF134A52),
    outline = Color(0xFF4F8A88),
    outlineVariant = Color(0xFF2A5A5E),
    inverseSurface = Mist,
    inverseOnSurface = DeepTeal,
)

/** One dark teal look on every phone, whatever the system theme; dynamic color is intentionally not used. */
@Composable
fun MileStoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AuroraColors, content = content)
}
