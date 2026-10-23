package net.kirkanos.mtgportal.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import net.kirkanos.mtgportal.core.model.ImageUrls
import net.kirkanos.mtgportal.core.model.ManaCost

/** A mana cost like "{2}{R}{R}" as a row of symbols (renderManaCost in shared.js). */
@Composable
fun ManaCostRow(cost: String?, images: ImageUrls, size: Dp = 18.dp) {
    val tokens = ManaCost.tokens(cost)
    if (tokens.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        tokens.forEach { AsyncImage(model = images.manaSymbol(it), contentDescription = "{$it}", modifier = Modifier.size(size)) }
    }
}

/**
 * Rules text with the {…} symbols drawn inline, like renderOracleText() in
 * shared.js. Each distinct token becomes one inline image slot.
 */
@Composable
fun OracleText(text: String?, images: ImageUrls, modifier: Modifier = Modifier) {
    val segments = ManaCost.segments(text)
    if (segments.isEmpty()) return
    val tokens = segments.filterIsInstance<ManaCost.Segment.Symbol>().map { it.token }.distinct()
    val annotated = buildAnnotatedString {
        segments.forEach { seg ->
            when (seg) {
                is ManaCost.Segment.Text -> append(seg.value)
                is ManaCost.Segment.Symbol -> appendInlineContent("sym:" + seg.token, "{${seg.token}}")
            }
        }
    }
    val inline = tokens.associate { token ->
        "sym:$token" to InlineTextContent(Placeholder(1.1.em, 1.1.em, PlaceholderVerticalAlign.TextCenter)) {
            AsyncImage(model = images.manaSymbol(token), contentDescription = "{$token}")
        }
    }
    Text(annotated, inlineContent = inline, style = LocalTextStyle.current, modifier = modifier)
}
