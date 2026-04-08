package com.arus.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val ArusColorScheme = lightColorScheme(
    primary = ArusDeepBlue,
    onPrimary = ArusPureWhite,
    primaryContainer = ArusSkyBlue,
    onPrimaryContainer = ArusDeepBlue,
    secondary = ArusSteelGrey,
    onSecondary = ArusPureWhite,
    tertiary = ArusElectricBlue,   
    onTertiary = ArusPureWhite,
    background = ArusIceWhite,      
    onBackground = ArusMidnightText,
    surface = ArusPureWhite,        
    onSurface = ArusMidnightText,
    surfaceVariant = ArusIceWhite, 
    outline = ArusBorder,           
    error = ErrorRed,
    onError = ArusPureWhite
)

@Composable
fun ArusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            window.statusBarColor = android.graphics.Color.TRANSPARENT

            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = ArusColorScheme,
        typography = Typography,
        content = content
    )
}