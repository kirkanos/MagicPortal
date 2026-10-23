package net.kirkanos.mtgportal.feature.cards

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.data.AuthState
import net.kirkanos.mtgportal.core.model.Condition
import net.kirkanos.mtgportal.core.model.ImageUrls
import net.kirkanos.mtgportal.core.model.Language
import net.kirkanos.mtgportal.core.model.SetCode
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.CardGroupDto
import net.kirkanos.mtgportal.core.network.dto.PrintDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.ui.Format
import net.kirkanos.mtgportal.core.ui.appContainer
import net.kirkanos.mtgportal.core.ui.components.Badge
import net.kirkanos.mtgportal.core.ui.components.CardImage
import net.kirkanos.mtgportal.core.ui.components.ManaCostRow
import net.kirkanos.mtgportal.core.ui.components.OracleText
import net.kirkanos.mtgportal.core.ui.components.SetSymbol
import net.kirkanos.mtgportal.core.ui.components.errorText
import net.kirkanos.mtgportal.core.ui.components.rarityColor
import net.kirkanos.mtgportal.core.ui.components.rarityLabel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors

/**
 * The detail dialog of the web grid (cardGroupModalHTML + loadCardVariants in
 * shared.js): image with flip for double-faced cards, cost, type, rules text,
 * totals, the wishlist star, one row per language/finish/condition variant and
 * the other printings of the card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailSheet(g: CardGroupDto, onDismiss: () -> Unit) {
    val c = appContainer()
    val images by c.config.imageUrls.collectAsStateWithLifecycle()
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val wished by c.wishlist.names.collectAsStateWithLifecycle()
    val unlocked = auth == AuthState.Open || auth == AuthState.Unlocked
    val scope = rememberCoroutineScope()
    var wishError by remember { mutableStateOf<ApiResult<Nothing>?>(null) }

    var prints by remember(g.key) { mutableStateOf<List<PrintDto>?>(null) }
    LaunchedEffect(g.key) {
        val r = safeCall { c.api.prints(g.name) }
        // Only OTHER printings, like the web: the open one is dropped.
        prints = (r as? ApiResult.Success)?.data.orEmpty().filterNot {
            SetCode.key(it.setCode) == SetCode.key(g.setCode) && it.collectorNumber == g.collectorNumber
        }
    }

    val variants = g.variants.sortedWith(compareBy({ it.language }, { it.foil }))
    val totalMarket = variants.sumOf { (it.marketPrice ?: 0.0) * it.quantity }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlipImage(g, images)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                ManaCostRow(g.manaCost, images)
            }
            if (g.typeLine.isNotEmpty()) Text(g.typeLine, color = PortalColors.TextDim)
            if (g.oracleText.isNotEmpty()) OracleText(g.oracleText, images)

            HorizontalDivider()
            Fact("Set", "${g.setName} (${g.setCode} #${g.collectorNumber})")
            Fact(stringResource(R.string.detail_rarity), rarityLabel(g.rarity))
            Fact(stringResource(R.string.detail_copies), g.totalQty.toString())
            Fact(stringResource(R.string.detail_market_total), Format.eur(totalMarket))
            Text(stringResource(R.string.detail_price_hint), fontSize = 11.sp, color = PortalColors.TextDim)

            // Marking is a write: only with write access, like the CSS gate on the web.
            if (unlocked) {
                val on = g.name.lowercase() in wished
                TextButton(onClick = {
                    scope.launch {
                        val r = c.wishlist.set(g.name, !on)
                        wishError = r as? ApiResult.HttpError ?: r as? ApiResult.NetworkError
                    }
                }) { Text((if (on) "★ " else "☆ ") + stringResource(R.string.detail_wishlist)) }
                wishError?.let { Text(stringResource(R.string.wish_save_failed, errorText(it)), color = PortalColors.Red) }
            }

            Text(stringResource(R.string.detail_languages), style = MaterialTheme.typography.titleSmall)
            variants.forEach { v ->
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${Language.flag(v.language)} ${v.language.ifEmpty { "?" }.uppercase()}")
                        if (v.foil.isNotEmpty() && v.foil != "normal") Badge(v.foil, PortalColors.Accent2, PortalColors.Text)
                        Text(Condition.label(v.condition), color = PortalColors.TextDim, modifier = Modifier.weight(1f))
                        Text("×${v.quantity}")
                        Text(Format.eur(v.marketPrice), modifier = Modifier.width(76.dp), maxLines = 1)
                    }
                    val meta = listOfNotNull(
                        v.binderName.ifEmpty { null },
                        v.added.take(10).ifEmpty { null }?.let { stringResource(R.string.detail_added, it) },
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = PortalColors.TextDim)
                }
            }

            Text(stringResource(R.string.detail_other_printings), style = MaterialTheme.typography.titleSmall)
            when (val p = prints) {
                null -> Text(stringResource(R.string.detail_loading_printings), color = PortalColors.TextDim)
                emptyList<PrintDto>() -> Text(stringResource(R.string.detail_no_other_printings), color = PortalColors.TextDim)
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(p, key = { it.setCode + "#" + it.collectorNumber }) { pr -> PrintTile(pr, images) }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row {
        Text(label, color = PortalColors.TextDim, modifier = Modifier.width(150.dp))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

/**
 * Front image, and for double-faced cards a flip to the back. The back image
 * is only requested once flipped, like the deferred data-src on the web.
 */
@Composable
private fun FlipImage(g: CardGroupDto, images: ImageUrls) {
    var flipped by remember(g.key) { mutableStateOf(false) }
    var backRequested by remember(g.key) { mutableStateOf(false) }
    val angle by animateFloatAsState(if (flipped) 180f else 0f, label = "flip")
    val front = images.card(g.imageId, ImageUrls.Size.NORMAL)
    val back = images.card(g.imageId, ImageUrls.Size.NORMAL, ImageUrls.Face.BACK, hasBack = g.hasBack)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth(0.72f).graphicsLayer { rotationY = angle; cameraDistance = 12f * density }) {
            if (angle <= 90f) {
                CardImage(front, g.name)
            } else {
                // Mirrored back so it reads correctly after the 180° turn.
                Box(Modifier.graphicsLayer { rotationY = 180f }) { CardImage(if (backRequested) back else null, g.name) }
            }
        }
        if (back != null) {
            TextButton(onClick = { backRequested = true; flipped = !flipped }) { Text(stringResource(R.string.detail_flip)) }
        }
    }
}

@Composable
private fun PrintTile(p: PrintDto, images: ImageUrls) {
    Column(Modifier.width(96.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardImage(images.card(p.imageId, ImageUrls.Size.SMALL), p.setName)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            SetSymbol(images.setIcon(p.iconSvgUri), p.rarity, 12.dp)
            Text(p.setName.ifEmpty { p.setCode }, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = rarityColor(p.rarity))
        }
        Text(
            when {
                p.priceEur != null -> Format.eur(p.priceEur)
                p.priceEurFoil != null -> Format.eur(p.priceEurFoil) + " (Foil)"
                else -> "–"
            },
            fontSize = 11.sp,
        )
    }
}
