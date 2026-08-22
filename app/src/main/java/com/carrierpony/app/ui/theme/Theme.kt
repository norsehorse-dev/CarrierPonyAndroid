// Theme.kt
// CarrierPony Android
//
// Material theme keyed to the CarrierPony palette. Dynamic color is
// deliberately off: the brand IS the color story, matching iOS.

package com.carrierpony.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = CPAccent,
    onPrimary = Color.White,
    secondary = CPAccentLite,
    onSecondary = Color.White,
    tertiary = CPAccentDeep,
    error = CPAccentDeep
)

private val DarkColorScheme = darkColorScheme(
    primary = CPAccent,
    onPrimary = Color.White,
    secondary = CPAccentLite,
    onSecondary = Color.White,
    tertiary = CPAccentDeep,
    error = CPAccentDeep
)

@Composable
fun CarrierPonyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
