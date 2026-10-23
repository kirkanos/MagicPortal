package net.kirkanos.mtgportal.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.ui.theme.PortalColors

/** Rarity colours of the web UI (.rarity-* in style.css), as on a real card's set symbol. */
fun rarityColor(rarity: String?): Color = when (rarity?.lowercase()) {
    "common" -> Color(0xFF9A9CAE)
    "uncommon" -> Color(0xFFC9C9D4)
    "rare" -> Color(0xFFC9A24D)
    "mythic" -> Color(0xFFE0743D)
    "special" -> Color(0xFF7B5CFF)
    else -> PortalColors.TextDim
}

/** RARITY_LABEL in grid.js. */
fun rarityLabel(rarity: String?): String = when (rarity?.lowercase()) {
    "common" -> "Common"
    "uncommon" -> "Uncommon"
    "rare" -> "Rare"
    "mythic" -> "Mythic"
    "special" -> "Special"
    "bonus" -> "Bonus"
    else -> rarity.orEmpty()
}

/**
 * A card image with the 63:88 shape of a real card, or the "Kein Bild"
 * placeholder when no image exists.
 */
@Composable
fun CardImage(url: String?, contentDescription: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.aspectRatio(63f / 88f).clip(shape).background(PortalColors.BgCard)) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                stringResource(R.string.cards_no_image),
                color = PortalColors.TextDim,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * The set symbol tinted in the rarity colour - the CSS mask trick of the web
 * UI. Scryfall's set SVGs are monochrome, so SrcIn paints them in one colour.
 */
@Composable
fun SetSymbol(url: String?, rarity: String?, size: Dp = 14.dp) {
    if (url == null) return
    AsyncImage(
        model = url,
        contentDescription = null,
        colorFilter = ColorFilter.tint(rarityColor(rarity), BlendMode.SrcIn),
        modifier = Modifier.size(size),
    )
}

@Composable
fun Badge(text: String, background: Color, foreground: Color = PortalColors.Bg, modifier: Modifier = Modifier) {
    Text(
        text,
        color = foreground,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(background).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** The stat tiles above several pages of the web UI. */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(PortalColors.BgCard).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = PortalColors.Accent, maxLines = 1)
        Text(label, fontSize = 11.sp, color = PortalColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Two tiles per row - four in a row are unreadable on a phone. */
@Composable
fun StatGrid(tiles: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (v, l) -> StatTile(v, l, Modifier.weight(1f)) }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}
