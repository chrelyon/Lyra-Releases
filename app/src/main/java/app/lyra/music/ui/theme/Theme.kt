package app.lyra.music.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = LyraPurple,
    secondary = LyraTeal,
    background = LyraLightBackground,
    surface = LyraLightSurface,
    surfaceVariant = ColorTokens.LightSurfaceVariant,
)

private val DarkColors = darkColorScheme(
    primary = LyraPurpleDark,
    secondary = LyraTealDark,
    background = LyraDarkBackground,
    surface = LyraDarkSurface,
    surfaceVariant = LyraDarkSurfaceVariant,
)

private object ColorTokens {
    val LightSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFEAE6EF)
}

@Composable
fun LyraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current

    // MainActivity usa enableEdgeToEdge(): le system bar restano gestite dal
    // sistema moderno e qui aggiorniamo soltanto il contrasto delle icone.
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
