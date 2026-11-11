package com.hoon.watchtransit.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = SubwayBadgeBlue,
    onPrimary = Color.White,
    primaryContainer = ButtonDark,
    onPrimaryContainer = Color.White,
    secondary = BusBadgeMint,
    onSecondary = BusBadgeText,
    secondaryContainer = EditOrderButtonDark,
    onSecondaryContainer = Color.White,
    tertiary = SubwayBadgeBlue,
    onTertiary = Color.White,
    tertiaryContainer = ManualButtonDark,
    onTertiaryContainer = Color.White,
    error = ErrorRed,
    onError = OnErrorRed,
    errorContainer = ErrorContainerRed,
    onErrorContainer = OnErrorContainerRed,
    background = AppBgDark,
    onBackground = Color.White,
    surface = CardBgDark,
    onSurface = Color.White,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextMuted,
    outline = TextPlaceholder,
    outlineVariant = DividerDark
)

private val DarkColorScheme = darkColorScheme(
    primary = SubwayBadgeBlue,
    onPrimary = Color.White,
    primaryContainer = ButtonDark,
    onPrimaryContainer = Color.White,
    secondary = BusBadgeMint,
    onSecondary = BusBadgeText,
    secondaryContainer = EditOrderButtonDark,
    onSecondaryContainer = Color.White,
    tertiary = SubwayBadgeBlue,
    onTertiary = Color.White,
    tertiaryContainer = ManualButtonDark,
    onTertiaryContainer = Color.White,
    error = ErrorRed,
    onError = OnErrorRed,
    errorContainer = ErrorContainerRed,
    onErrorContainer = OnErrorContainerRed,
    background = AppBgDark,
    onBackground = Color.White,
    surface = CardBgDark,
    onSurface = Color.White,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextMuted,
    outline = TextPlaceholder,
    outlineVariant = DividerDark
)

@Composable
fun SmartRiderTheme(
    darkTheme: Boolean = true, // 이미지와 동일한 모던 다크 테마 기본 적용
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color(0xFF121318).toArgb()
            window.navigationBarColor = Color(0xFF121318).toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
