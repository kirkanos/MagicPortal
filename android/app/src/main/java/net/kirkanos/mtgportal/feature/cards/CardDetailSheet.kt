package net.kirkanos.mtgportal.feature.cards

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import net.kirkanos.mtgportal.core.ui.components.HelpButton
import net.kirkanos.mtgportal.core.model.Condition
import net.kirkanos.mtgportal.core.model.ImageUrls
import net.kirkanos.mtgportal.core.model.Language
import net.kirkanos.mtgportal.core.model.SetCode
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.data.AuthState
import net.kirkanos.mtgportal.core.data.BinderTarget
import net.kirkanos.mtgportal.core.network.dto.BinderDto
import net.kirkanos.mtgportal.core.network.dto.CardGroupDto
import net.kirkanos.mtgportal.core.network.dto.RowPatchDto
import net.kirkanos.mtgportal.core.network.dto.VariantDto
import net.kirkanos.mtgportal.feature.collection.BinderPickerDialog
import net.kirkanos.mtgportal.core.network.dto.PrintDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.ui.Format
import net.kirkanos.mtgportal.core.ui.appContainer
import net.kirkanos.mtgportal.core.ui.components.Badge
import net.kirkanos.mtgportal.core.ui.components.CardImage
import net.kirkanos.mtgportal.core.ui.components.ManaCostRow
import net.kirkanos.mtgportal.core.ui.components.OracleText
import net.kirkanos.mtgportal.core.ui.components.SetSymbol
import net.kirkanos.mtgportal.core.ui.components.WishStar
import net.kirkanos.mtgportal.core.ui.components.rarityColor
import net.kirkanos.mtgportal.core.ui.components.rarityLabel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors

/**
 * The detail dialog of the web grid (cardGroupModalHTML + loadCardVariants in
 * shared.js): image with flip for double-faced cards, cost, type, rules text,
 * totals, the wishlist star, one row per language/finish/condition variant and
 * the other printings of the card.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CardDetailSheet(g: CardGroupDto, onDismiss: () -> Unit) {
    val c = appContainer()
    val images by c.config.imageUrls.collectAsStateWithLifecycle()
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val unlocked = auth == AuthState.Open || auth == AuthState.Unlocked
    val scope = rememberCoroutineScope()

    // Edited in place: +1, -1 and moves update this list from the server's
    // answer; the grid behind reloads on its own (CollectionWriteRepository.changes).
    var rows by remember(g.key) { mutableStateOf(g.variants) }
    var busy by remember(g.key) { mutableStateOf(false) }
    var notice by remember(g.key) { mutableStateOf<String?>(null) }
    var moving by remember(g.key) { mutableStateOf<VariantDto?>(null) }
    var editing by remember(g.key) { mutableStateOf<VariantDto?>(null) }
    var binders by remember { mutableStateOf<List<BinderDto>>(emptyList()) }
    val volatileText = stringResource(R.string.detail_edit_volatile)
    val mergedText = stringResource(R.string.detail_edit_merged)
    val reprintedText = stringResource(R.string.detail_edit_reprinted)

    fun write(block: suspend () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            notice = block()
            busy = false
        }
    }
    fun failure(r: ApiResult<*>) = when (r) {
        is ApiResult.HttpError -> r.message
        is ApiResult.NetworkError -> r.cause.message ?: r.cause.javaClass.simpleName
        is ApiResult.Success -> null
    }

    var prints by remember(g.key) { mutableStateOf<List<PrintDto>?>(null) }
    LaunchedEffect(g.key) {
        val r = safeCall { c.api.prints(g.name) }
        // Only OTHER printings, like the web: the open one is dropped.
        prints = (r as? ApiResult.Success)?.data.orEmpty().filterNot {
            SetCode.key(it.setCode) == SetCode.key(g.setCode) && it.collectorNumber == g.collectorNumber
        }
    }

    val variants = rows.sortedWith(compareBy({ it.language }, { it.foil }))
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
            Fact(stringResource(R.string.detail_copies), variants.sumOf { it.quantity }.toString())
            Fact(stringResource(R.string.detail_market_total), Format.eur(totalMarket), help = stringResource(R.string.detail_price_hint))

            WishStar(g.name, label = stringResource(R.string.detail_wishlist))

            Text(stringResource(R.string.detail_languages), style = MaterialTheme.typography.titleSmall)
            variants.forEach { v ->
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${Language.flag(v.language)} ${v.language.ifEmpty { "?" }.uppercase()}")
                        if (v.foil.isNotEmpty() && v.foil != "normal") Badge(v.foil, PortalColors.Accent2, foreground = PortalColors.Text)
                        Text(Condition.label(v.condition), color = PortalColors.TextDim, modifier = Modifier.weight(1f))
                        Text("×${v.quantity}")
                        Text(Format.eur(v.marketPrice), modifier = Modifier.width(76.dp), maxLines = 1)
                    }
                    val meta = listOfNotNull(
                        v.binderName.ifEmpty { null },
                        v.added.take(10).ifEmpty { null }?.let { stringResource(R.string.detail_added, it) },
                        if (v.origin == "app") stringResource(R.string.detail_origin_app) else null,
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = PortalColors.TextDim)
                    if (unlocked && v.id != 0L) {
                        FlowRow(verticalArrangement = Arrangement.Center) {
                            TextButton(enabled = !busy, onClick = {
                                write {
                                    when (val r = c.collectionWrite.patch(v.id, RowPatchDto(quantityDelta = -1))) {
                                        is ApiResult.Success -> {
                                            rows = applyWrite(rows, r.data.row, if (r.data.deleted) setOf(v.id) else emptySet())
                                            if (v.origin == "csv") volatileText else null
                                        }
                                        else -> failure(r)
                                    }
                                }
                            }) { Text("−1") }
                            TextButton(enabled = !busy, onClick = {
                                write {
                                    when (val r = c.collectionWrite.addOne(g.setCode, g.collectorNumber, v)) {
                                        is ApiResult.Success -> { rows = applyWrite(rows, r.data); null }
                                        else -> failure(r)
                                    }
                                }
                            }) { Text("+1") }
                            TextButton(enabled = !busy, onClick = {
                                moving = v
                                scope.launch { (safeCall { c.api.binders() } as? ApiResult.Success)?.let { binders = it.data } }
                            }) { Text(stringResource(R.string.detail_move)) }
                            TextButton(enabled = !busy, onClick = { editing = v }) { Text(stringResource(R.string.detail_edit)) }
                        }
                    }
                }
            }
            notice?.let { Text(it, fontSize = 12.sp, color = PortalColors.Accent) }

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

    editing?.let { v ->
        val current = PrintDto(setCode = g.setCode, setName = g.setName, collectorNumber = g.collectorNumber)
        EditVariantDialog(v, listOf(current) + prints.orEmpty(), onDismiss = { editing = null }, onSave = { patch ->
            editing = null
            if (patch == null) return@EditVariantDialog
            write {
                when (val r = c.collectionWrite.patch(v.id, patch)) {
                    is ApiResult.Success -> {
                        if (patch.setCode != null) {
                            // Another card now: the copies leave this one; the grid reloads.
                            rows = applyWrite(rows, null, setOf(v.id))
                            if (rows.isEmpty()) onDismiss()
                            val row = r.data.row
                            return@write row?.let { reprintedText.format("${it.setName} (${it.setCode} #${it.collectorNumber})") }
                        }
                        val merged = r.data.mergedInto != 0L
                        rows = applyWrite(rows, r.data.row, if (merged) setOf(v.id) else emptySet())
                        when {
                            v.origin == "csv" -> volatileText
                            merged -> mergedText
                            else -> null
                        }
                    }
                    else -> failure(r)
                }
            }
        })
    }

    moving?.let { v ->
        MoveDialog(v, binders, g.setCode, onDismiss = { moving = null }, onPick = { target ->
            moving = null
            if (target.name == v.binderName) return@MoveDialog
            write {
                when (val r = c.collectionWrite.patch(v.id, RowPatchDto(binderName = target.name, binderType = target.type))) {
                    is ApiResult.Success -> {
                        rows = applyWrite(rows, r.data.row, setOf(v.id))
                        if (v.origin == "csv") volatileText else null
                    }
                    else -> failure(r)
                }
            }
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditVariantDialog(v: VariantDto, printings: List<PrintDto>, onSave: (RowPatchDto?) -> Unit, onDismiss: () -> Unit) {
    val curLang = v.language.ifEmpty { "en" }
    val curFoil = v.foil.ifEmpty { "normal" }
    val curCond = v.condition.ifEmpty { "near_mint" }
    var lang by remember { mutableStateOf(curLang) }
    var foil by remember { mutableStateOf(curFoil) }
    var cond by remember { mutableStateOf(curCond) }
    val curPrint = printings.first()
    var print by remember { mutableStateOf(curPrint) }
    var printsOpen by remember { mutableStateOf(false) }
    fun printLabel(p: PrintDto) = "${p.setName.ifEmpty { p.setCode }} (${p.setCode.uppercase()} #${p.collectorNumber})"
    // A value outside the lists (old ManaBox spellings) stays selectable.
    val langs = if (curLang in Language.CODES) Language.CODES else listOf(curLang) + Language.CODES
    val conds = if (curCond in Condition.ORDER) Condition.ORDER else listOf(curCond) + Condition.ORDER

    @Composable
    fun Choices(title: String, options: List<String>, selected: String, label: @Composable (String) -> String, onPick: (String) -> Unit) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o -> FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) }) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.detail_edit_printing), style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(onClick = { printsOpen = true }, enabled = printings.size > 1, modifier = Modifier.fillMaxWidth()) {
                        Text(printLabel(print), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = printsOpen, onDismissRequest = { printsOpen = false }) {
                        printings.forEach { p ->
                            DropdownMenuItem(text = { Text(printLabel(p)) }, onClick = { print = p; printsOpen = false })
                        }
                    }
                }
                Choices(stringResource(R.string.detail_edit_language), langs, lang, { "${Language.flag(it)} ${it.uppercase()}".trim() }) { lang = it }
                Choices(stringResource(R.string.detail_edit_finish), listOf("normal", "foil", "etched"), foil, {
                    when (it) { "foil" -> "Foil"; "etched" -> "Etched"; else -> stringResource(R.string.detail_edit_normal) }
                }) { foil = it }
                Choices(stringResource(R.string.detail_edit_condition), conds, cond, { Condition.label(it) }) { cond = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val reprint = print !== curPrint
                val patch = RowPatchDto(
                    setCode = if (reprint) SetCode.key(print.setCode) else null,
                    collectorNumber = if (reprint) print.collectorNumber else null,
                    language = lang.takeIf { it != curLang },
                    foil = foil.takeIf { it != curFoil },
                    condition = cond.takeIf { it != curCond },
                )
                onSave(if (patch == RowPatchDto()) null else patch)
            }) { Text(stringResource(R.string.binder_note_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun MoveDialog(v: VariantDto, binders: List<BinderDto>, setCode: String, onPick: (BinderTarget) -> Unit, onDismiss: () -> Unit) {
    BinderPickerDialog(binders, BinderTarget(v.binderName, v.binderType), onPick, onDismiss, sets = listOf(setCode))
}

@Composable
private fun Fact(label: String, value: String, help: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = PortalColors.TextDim, modifier = Modifier.width(150.dp))
        Text(value, fontWeight = FontWeight.Medium)
        help?.let { HelpButton(it, Modifier.height(20.dp)) }
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
