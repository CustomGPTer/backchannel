package uk.jj.backchannel.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow

object Prefs {
    const val THEME_DARK = 0
    const val THEME_LIGHT = 1
    const val THEME_SYSTEM = 2

    val theme = MutableStateFlow(THEME_DARK)

    fun load(context: Context) {
        theme.value = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getInt("theme", THEME_DARK)
    }

    fun setTheme(context: Context, mode: Int) {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putInt("theme", mode).apply()
        theme.value = mode
    }
}

private val Dark = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF115E59),
    onPrimaryContainer = Color(0xFFD5FBF4),
    secondary = Color(0xFF9CCFC8),
    onSecondary = Color(0xFF02201D),
    secondaryContainer = Color(0xFF1C3A3A),
    onSecondaryContainer = Color(0xFFCDEDE8),
    background = Color(0xFF0B1113),
    onBackground = Color(0xFFE3EAEA),
    surface = Color(0xFF0B1113),
    onSurface = Color(0xFFE3EAEA),
    surfaceVariant = Color(0xFF1B2629),
    onSurfaceVariant = Color(0xFFA5B5B7),
    surfaceContainerLowest = Color(0xFF070B0C),
    surfaceContainerLow = Color(0xFF10181A),
    surfaceContainer = Color(0xFF141D20),
    surfaceContainerHigh = Color(0xFF1B2629),
    surfaceContainerHighest = Color(0xFF243134),
    outline = Color(0xFF5E7073),
    outlineVariant = Color(0xFF2C3A3D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBFF3EA),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A6360),
    secondaryContainer = Color(0xFFD9EFEB),
    onSecondaryContainer = Color(0xFF06201D),
    background = Color(0xFFF6FAF9),
    onBackground = Color(0xFF161D1D),
    surface = Color(0xFFF6FAF9),
    onSurface = Color(0xFF161D1D),
    surfaceVariant = Color(0xFFE2EBE9),
    onSurfaceVariant = Color(0xFF455352),
    outline = Color(0xFF6F7C7A),
    outlineVariant = Color(0xFFC3CFCD),
)

@Composable
fun isDark(mode: Int): Boolean = when (mode) {
    Prefs.THEME_LIGHT -> false
    Prefs.THEME_SYSTEM -> isSystemInDarkTheme()
    else -> true
}

@Composable
fun BackchannelTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
