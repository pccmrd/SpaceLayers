package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val HexWellDarkColorScheme =
  darkColorScheme(
    primary = CyanTelemetry,
    onPrimary = OnCyanTelemetry,
    primaryContainer = CyanContainer,
    onPrimaryContainer = StarlightWhite,
    secondary = GravityIndigo,
    onSecondary = OnGravityIndigo,
    secondaryContainer = IndigoContainer,
    onSecondaryContainer = StarlightWhite,
    tertiary = SolarAmber,
    onTertiary = OnSolarAmber,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = StarlightWhite,
    background = VoidNavy,
    onBackground = StarlightWhite,
    surface = DeepSlate,
    onSurface = StarlightWhite,
    surfaceVariant = ElevatedHull,
    onSurfaceVariant = MutedTelemetry,
    outline = PanelOutline,
    error = AlertCoral,
  )

@Composable
fun HexWellTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = HexWellDarkColorScheme,
    typography = Typography,
    content = content,
  )
}
