package io.github.liminalium.phonelab.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF6FE3B8),
    onPrimary = Color(0xFF00382A),
    secondary = Color(0xFF9CCBDA),
    background = Color(0xFF0F1A1F),
    surface = Color(0xFF0F1A1F),
    surfaceVariant = Color(0xFF1C2B32),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0B7A5A),
    onPrimary = Color.White,
    secondary = Color(0xFF2F6475),
    surfaceVariant = Color(0xFFE3EEEA),
)

@Composable
fun PhoneLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

object StatusColors {
    val ok = Color(0xFF2E9E6A)
    val warn = Color(0xFFD99A1E)
    val fail = Color(0xFFD0453B)
    val muted = Color(0xFF8A8F98)
}
