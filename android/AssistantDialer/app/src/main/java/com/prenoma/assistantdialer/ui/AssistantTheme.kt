package com.prenoma.assistantdialer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AssistantColors = darkColorScheme(
    primary = Color(0xFF6EADFF),
    onPrimary = Color(0xFF061321),
    secondary = Color(0xFF91A0B2),
    background = Color(0xFF080A0D),
    onBackground = Color(0xFFF4F7FB),
    surface = Color(0xFF0F1319),
    onSurface = Color(0xFFF4F7FB),
    error = Color(0xFFFF6B6B),
)

@Composable
fun AssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AssistantColors, content = content)
}
