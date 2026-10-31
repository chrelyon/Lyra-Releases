package app.lyra.music.ui.theme

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily

/**
 * Typeface di branding per il solo logotipo testuale "Lyra".
 * Usa la famiglia di sistema rounded, quindi non aggiunge file font all'APK e
 * mantiene un fallback sicuro sui dispositivi che non espongono quel nome.
 */
val LyraBrandFontFamily: FontFamily = FontFamily(
    Typeface.create("sans-serif-rounded", Typeface.BOLD_ITALIC)
)
