package com.hoon.watchtransit.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = PrimaryAmber,
    onPrimary = OnPrimaryAmber,
    primaryContainer = PrimaryContainerAmber,
    onPrimaryContainer = OnPrimaryContainerAmber,
    secondary = SecondaryPink,
    onSecondary = OnSecondaryPink,
    secondaryContainer = SecondaryContainerPink,
    onSecondaryContainer = OnSecondaryContainerPink,
    tertiary = TertiaryOlive,
    onTertiary = OnTertiaryOlive,
    tertiaryContainer = TertiaryContainerOlive,
    onTertiaryContainer = OnTertiaryContainerOlive,
    error = ErrorRed,
    onError = OnErrorRed,
    errorContainer = ErrorContainerRed,
    onErrorContainer = OnErrorContainerRed,
    background = BackgroundCream,
    onBackground = OnBackgroundDark,
    surface = SurfaceCream,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantWarm,
    onSurfaceVariant = OnSurfaceVariantWarm,
    outline = OutlineWarm,
    outlineVariant = OutlineVariantWarm
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryAmberDark,
    onPrimary = OnPrimaryAmberDark,
    primaryContainer = PrimaryContainerAmberDark,
    onPrimaryContainer = OnPrimaryContainerAmberDark,
    secondary = SecondaryPinkDark,
    onSecondary = OnSecondaryPinkDark,
    secondaryContainer = SecondaryContainerPinkDark,
    onSecondaryContainer = OnSecondaryContainerPinkDark,
    tertiary = TertiaryOliveDark,
    onTertiary = OnTertiaryOliveDark,
    tertiaryContainer = TertiaryContainerOliveDark,
    onTertiaryContainer = OnTertiaryContainerOliveDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDarkText,
    surface = SurfaceDark,
    onSurface = OnSurfaceDarkText,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark
)

@Composable
fun SmartRiderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
