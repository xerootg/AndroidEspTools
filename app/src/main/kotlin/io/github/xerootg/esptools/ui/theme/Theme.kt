package io.github.xerootg.esptools.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Orange = Color(0xFFF97316)
private val OrangeDark = Color(0xFFFDBA74)
private val Slate = Color(0xFF0F172A)

private val DarkScheme = darkColorScheme(
    primary = OrangeDark, onPrimary = Slate, primaryContainer = Color(0xFF7C2D12), onPrimaryContainer = Color(0xFFFFEDD5),
    secondary = Color(0xFF94A3B8), background = Color(0xFF0B1220), surface = Color(0xFF111827), surfaceVariant = Color(0xFF1F2937),
)
private val LightScheme = lightColorScheme(
    primary = Orange, onPrimary = Color.White, primaryContainer = Color(0xFFFFEDD5), onPrimaryContainer = Color(0xFF7C2D12),
    secondary = Color(0xFF475569),
)

@Composable
fun EspToolsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
