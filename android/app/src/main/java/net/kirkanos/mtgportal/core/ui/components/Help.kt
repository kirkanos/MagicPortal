package net.kirkanos.mtgportal.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.ui.theme.PortalColors

/**
 * A small "?" that shows an explanation in a bubble on tap, so the screens
 * themselves carry no paragraphs. The web UI has the same button.
 */
@Composable
fun HelpButton(text: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.common_help)
    Box(
        modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button) { open = true }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(20.dp).border(1.5.dp, PortalColors.TextDim, CircleShape), contentAlignment = Alignment.Center) {
            Text("?", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PortalColors.TextDim)
        }
        if (open) {
            val margin = with(LocalDensity.current) { 12.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(margin) { BelowAnchor(margin) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PortalColors.BgCard,
                    border = BorderStroke(1.dp, PortalColors.Border),
                    shadowElevation = 6.dp,
                    modifier = Modifier.widthIn(max = 320.dp).clickable { open = false },
                ) {
                    Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = PortalColors.Text)
                }
            }
        }
    }
}

/** Below the "?" (above when there is no room), pushed inside the window. */
private class BelowAnchor(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(margin, maxX)
        val fitsBelow = anchorBounds.bottom + popupContentSize.height <= windowSize.height - margin
        val y = if (fitsBelow) anchorBounds.bottom else (anchorBounds.top - popupContentSize.height).coerceAtLeast(margin)
        return IntOffset(x, y)
    }
}
