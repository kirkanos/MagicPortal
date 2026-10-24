package net.kirkanos.mtgportal.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The palette of the web UI (:root in app/css/style.css), so app and browser
// look like the same product. The web UI is dark only; so is the app.
object PortalColors {
    val Bg = Color(0xFF12131A)
    val BgElevated = Color(0xFF1B1D29)
    val BgCard = Color(0xFF21232F)
    val Text = Color(0xFFE8E8F0)
    val TextDim = Color(0xFF9A9CAE)
    val Accent = Color(0xFFC9A24D)
    val Accent2 = Color(0xFF7B5CFF)
    val Border = Color(0xFF2F3140)
    val Red = Color(0xFFE05656)
    val Green = Color(0xFF4DBB6A)
}

private val Scheme = darkColorScheme(
    primary = PortalColors.Accent,
    onPrimary = PortalColors.Bg,
    secondary = PortalColors.Accent2,
    background = PortalColors.Bg,
    onBackground = PortalColors.Text,
    surface = PortalColors.BgElevated,
    onSurface = PortalColors.Text,
    surfaceVariant = PortalColors.BgCard,
    onSurfaceVariant = PortalColors.TextDim,
    outline = PortalColors.Border,
    error = PortalColors.Red,
)

@Composable
fun MtgPortalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
