package app.cortado.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Espresso = Color(0xFF5B3A29)
val Crema = Color(0xFFF6EBDD)
val Sage = Color(0xFF3E7D5A)
val Honey = Color(0xFFC98A1A)
val Clay = Color(0xFFB4483A)
val Fog = Color(0xFF8A8580)

private val Light = lightColorScheme(
    primary = Espresso,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBD5C4),
    onPrimaryContainer = Color(0xFF2E1A10),
    secondary = Sage,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3EBDD),
    onSecondaryContainer = Color(0xFF0E2E1C),
    background = Color(0xFFFFFBF7),
    surface = Color(0xFFFFFBF7),
    surfaceContainer = Crema,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFE9C2A6),
    onPrimary = Color(0xFF3A2216),
    primaryContainer = Color(0xFF5B3A29),
    onPrimaryContainer = Color(0xFFFFDCC7),
    secondary = Color(0xFF9AD2B0),
    onSecondary = Color(0xFF00391F),
    secondaryContainer = Color(0xFF1F5139),
    onSecondaryContainer = Color(0xFFB6EFCB),
)

@Composable
fun CortadoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
