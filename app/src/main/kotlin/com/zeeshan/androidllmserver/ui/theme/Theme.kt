package com.zeeshan.androidllmserver.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    secondary = Color(0xFF81C995),
    tertiary = Color(0xFFF28B82),
    surface = Color(0xFF1E1E1E),
    background = Color(0xFF121212),
    onSurface = Color(0xFFE8EAED),
    onBackground = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF2D2D2D),
    outline = Color(0xFF5F6368),
    surfaceContainerHigh = Color(0xFF2D2D2D),
)

@Composable
fun LlmServerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(
            headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, color = Color(0xFFE8EAED)),
            titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color(0xFFE8EAED)),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color(0xFFE8EAED)),
        ),
        content = content,
    )
}
