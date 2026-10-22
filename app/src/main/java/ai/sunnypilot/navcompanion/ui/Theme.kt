package ai.sunnypilot.navcompanion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SunnyColors = darkColorScheme(
  primary = Color(0xFF38E08B),
  onPrimary = Color(0xFF06281A),
  primaryContainer = Color(0xFF12402E),
  onPrimaryContainer = Color(0xFFB8F5D6),
  secondary = Color(0xFF6ECBF5),
  onSecondary = Color(0xFF04212F),
  background = Color(0xFF06121A),
  onBackground = Color(0xFFE4EFF4),
  surface = Color(0xFF101C24),
  onSurface = Color(0xFFE4EFF4),
  surfaceVariant = Color(0xFF1B2A34),
  onSurfaceVariant = Color(0xFF9DB2BE),
  error = Color(0xFFFF6B6B),
)

@Composable
fun SunnyNavTheme(content: @Composable () -> Unit) {
  // The map style is dark, so the app always uses the dark palette for contrast in a car.
  MaterialTheme(
    colorScheme = SunnyColors,
    content = content,
  )
}
