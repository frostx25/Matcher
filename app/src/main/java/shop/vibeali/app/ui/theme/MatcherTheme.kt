package shop.vibeali.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Black = Color(0xFF0B0B0F)
private val Surface = Color(0xFF17131A)
private val Pink = Color(0xFFFF2D7A)
private val SoftPink = Color(0xFFFF78A8)
private val TextPrimary = Color(0xFFFFF7FB)
private val TextSecondary = Color(0xFFB9AEB5)

private val MatcherColorScheme = darkColorScheme(
    primary = Pink,
    onPrimary = Black,
    primaryContainer = Color(0xFF5F1236),
    onPrimaryContainer = TextPrimary,
    secondary = SoftPink,
    onSecondary = Black,
    background = Black,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFF251E27),
    onSurfaceVariant = TextSecondary,
)

@Composable
fun MatcherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MatcherColorScheme,
        typography = Typography(),
        content = content,
    )
}

