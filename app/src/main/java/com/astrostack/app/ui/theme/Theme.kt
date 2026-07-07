package com.astrostack.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Deep-red dark theme for AstroStack.
 *
 * All colours are red-tinted and very dark so astronomers can use their phone
 * at the telescope without destroying their dark adaptation.
 */
private val AstroColorScheme = darkColorScheme(
    primary             = Astro_Red500,
    onPrimary           = Astro_OnError,
    primaryContainer    = Astro_Red800,
    onPrimaryContainer  = Astro_Red200,

    secondary           = Astro_Red600,
    onSecondary         = Astro_OnError,
    secondaryContainer  = Astro_Red900,
    onSecondaryContainer = Astro_Red400,

    tertiary            = Astro_Red400,
    onTertiary          = Astro_OnError,

    background          = Astro_Background,
    onBackground        = Astro_OnBackground,

    surface             = Astro_Surface,
    onSurface           = Astro_OnSurface,
    surfaceVariant      = Astro_Surface2,
    onSurfaceVariant    = Astro_OnSurfaceVariant,

    error               = Astro_Error,
    onError             = Astro_OnError,

    outline             = Astro_Red900,
    outlineVariant      = Astro_Surface3,
)

object ThemeConfig {
    var isRedScreenMode by mutableStateOf(true) // default to red screen
}

@Composable
fun AstroStackTheme(content: @Composable () -> Unit) {
    val colorScheme = if (ThemeConfig.isRedScreenMode) {
        AstroColorScheme
    } else {
        // Standard Material 3 dark color scheme for daytime/default brightness
        darkColorScheme()
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = androidx.compose.material3.Typography(),
        content     = content,
    )
}
