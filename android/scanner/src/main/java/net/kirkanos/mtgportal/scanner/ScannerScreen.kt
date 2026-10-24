package net.kirkanos.mtgportal.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import net.kirkanos.mtgportal.scanner.camera.CardFrameAnalyzer
import net.kirkanos.mtgportal.scanner.camera.GuideFrame
import net.kirkanos.mtgportal.scanner.camera.Quad
import net.kirkanos.mtgportal.scanner.catalog.CatalogStore
import java.io.File
import java.util.concurrent.Executors

/**
 * The camera scanner behind [ScannerEntry]; the app hands in where the
 * catalog comes from and, in debug builds, where to dump frames.
 */
class CameraScanner(private val store: CatalogStore, private val dumpDir: File? = null) : ScannerEntry {
    // The orientation lock is deliberate, see below.
    @SuppressLint("SourceLockedOrientationActivity")
    @Composable
    override fun Screen(onResult: (ScanSessionResult) -> Unit, onClose: () -> Unit) {
        val vm: ScannerViewModel = viewModel(factory = viewModelFactory { initializer { ScannerViewModel(store, dumpDir) } })
        val context = LocalContext.current
        var granted by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
        // Held flat over a table, the phone flips to landscape at random -
        // seen on the device, half the frames came sideways. Card, frame and
        // text all assume portrait, so the scanner keeps it.
        val activity = context.findActivity()
        DisposableEffect(activity) {
            val before = activity?.requestedOrientation
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            onDispose { if (before != null) activity.requestedOrientation = before }
        }
        LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
        ScannerContent(vm, granted, onAskPermission = { ask.launch(Manifest.permission.CAMERA) }, onDone = { onResult(vm.result()) }, onClose = onClose)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val LANGUAGES = listOf("en", "de", "fr", "it", "es", "pt", "ja", "ko", "ru", "zhs", "zht")
private val FINISHES = listOf("normal", "foil", "etched")

@Composable
private fun ScannerContent(
    vm: ScannerViewModel,
    granted: Boolean,
    onAskPermission: () -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Long?>(null) }
    var searching by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    LaunchedEffect(vm) {
        vm.accepted.collect {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.scanner_close)) }
            Text(stringResource(R.string.scanner_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { searching = true }, enabled = state.catalog is CatalogState.Ready) { Text(stringResource(R.string.scanner_search)) }
            val count = state.items.sumOf { it.quantity }
            Button(onClick = onDone, enabled = count > 0) { Text(pluralStringResource(R.plurals.scanner_done, count, count)) }
        }

        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(Color.Black)) {
            if (granted) CameraPreview(vm, state.torch) else PermissionHint(onAskPermission)
            GuideOverlay(Modifier.fillMaxSize(), state.outline)
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                if (granted) OutlinedButton(onClick = vm::toggleTorch) { Text(if (state.torch) "🔦 ✓" else "🔦") }
            }
            StatusChip(state, Modifier.align(Alignment.BottomCenter).padding(12.dp), onRetry = vm::loadCatalog)
        }

        if (state.items.isEmpty()) {
            Text(
                stringResource(R.string.scanner_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(state.items, key = { it.id }) { item ->
                SessionRow(item) { editing = item.id }
                HorizontalDivider()
            }
        }
    }

    state.items.firstOrNull { it.id == editing }?.let { item ->
        EditSheet(item, vm, onDismiss = { editing = null })
    }
    if (searching) SearchSheet(vm, onDismiss = { searching = false })
}

/**
 * For cards the camera does not get: type the name (typos forgiven, works
 * offline on the device's catalog), tap a result to add it - the newest
 * regular printing, the others to pick from in the edit sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(vm: ScannerViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { vm.search(query) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).imePadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.scanner_search_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (query.length >= 2 && results.isEmpty()) {
                Text(stringResource(R.string.scanner_search_none), modifier = Modifier.padding(vertical = 12.dp))
            }
            LazyColumn {
                items(results, key = { it.first().i }) { printings ->
                    val p = printings.first()
                    ListItem(
                        modifier = Modifier.clickable { vm.addManual(printings); onDismiss() },
                        headlineContent = { Text(p.name) },
                        supportingContent = {
                            Text(pluralStringResource(R.plurals.scanner_search_printings, printings.size, p.setCode.uppercase(), printings.size))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(vm: ScannerViewModel, torch: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            // The default analysis size (640x480) leaves the collector line a
            // few pixels high; 1440 px across puts the card at ~1200 px.
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(android.util.Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            imageAnalysisOutputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
            setImageAnalysisAnalyzer(executor, CardFrameAnalyzer(vm.busy, vm::onFrame))
        }
    }
    DisposableEffect(owner) {
        controller.bindToLifecycle(owner)
        onDispose {
            controller.unbind()
            executor.shutdown()
        }
    }
    LaunchedEffect(torch) { controller.enableTorch(torch) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                this.controller = controller
            }
        },
    )
}

/**
 * Darkens everything but the card frame, like the guides of other scanners,
 * and draws the card outline the cropper found, so the user sees when the
 * card is picked up.
 */
@Composable
private fun GuideOverlay(modifier: Modifier, outline: Quad?) {
    Canvas(modifier) {
        val g = GuideFrame.rect(size.width, size.height)
        val shade = Color.Black.copy(alpha = 0.45f)
        drawRect(shade, Offset.Zero, Size(size.width, g.top))
        drawRect(shade, Offset(0f, g.bottom), Size(size.width, size.height - g.bottom))
        drawRect(shade, Offset(0f, g.top), Size(g.left, g.height))
        drawRect(shade, Offset(g.right, g.top), Size(size.width - g.right, g.height))
        drawRoundRect(
            Color.White,
            Offset(g.left, g.top),
            Size(g.width, g.height),
            CornerRadius(g.width * 0.045f),
            style = Stroke(width = 2.dp.toPx()),
        )
        if (outline != null) {
            val path = Path().apply {
                moveTo(outline.tl.x * size.width, outline.tl.y * size.height)
                lineTo(outline.tr.x * size.width, outline.tr.y * size.height)
                lineTo(outline.br.x * size.width, outline.br.y * size.height)
                lineTo(outline.bl.x * size.width, outline.bl.y * size.height)
                close()
            }
            drawPath(path, Color(0xFF4CAF50), style = Stroke(width = 3.dp.toPx()))
        }
    }
}

@Composable
private fun StatusChip(state: ScannerUiState, modifier: Modifier, onRetry: () -> Unit) {
    val text = when (val c = state.catalog) {
        CatalogState.Loading -> stringResource(R.string.scanner_catalog_loading)
        is CatalogState.Failed -> stringResource(R.string.scanner_catalog_failed, c.message)
        is CatalogState.Ready -> state.seeing ?: if (c.current) stringResource(R.string.scanner_hint) else stringResource(R.string.scanner_catalog_offline)
    }
    Text(
        text,
        color = Color.White,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .then(if (state.catalog is CatalogState.Failed) Modifier.clickable(onClick = onRetry) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun PermissionHint(onAsk: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.scanner_permission), color = Color.White)
        Spacer(Modifier.padding(8.dp))
        Button(onClick = onAsk) { Text(stringResource(R.string.scanner_permission_grant)) }
    }
}

@Composable
private fun SessionRow(item: SessionItem, onClick: () -> Unit) {
    val p = item.printing
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Text("${item.quantity}×", style = MaterialTheme.typography.titleMedium) },
        headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val extra = buildList {
                if (item.foil != "normal") add(item.foil)
                if (item.language != "en") add(item.language.uppercase())
            }.joinToString(" · ")
            Text("${p.setCode.uppercase()} #${p.collectorNumber}" + if (extra.isNotEmpty()) " · $extra" else "")
        },
        trailingContent = {
            if (item.uncertain) Text("?", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleLarge)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(item: SessionItem, vm: ScannerViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            item {
                Text(item.printing.name, style = MaterialTheme.typography.titleLarge)
                Text("${item.printing.setCode.uppercase()} #${item.printing.collectorNumber}", style = MaterialTheme.typography.bodyMedium)
                if (item.uncertain) {
                    Text(stringResource(R.string.scanner_uncertain), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    OutlinedButton(onClick = { vm.setQuantity(item.id, item.quantity - 1) }, enabled = item.quantity > 1) { Text("−") }
                    Text("${item.quantity}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
                    OutlinedButton(onClick = { vm.setQuantity(item.id, item.quantity + 1) }) { Text("+") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.remove(item.id); onDismiss() }) {
                        Text(stringResource(R.string.scanner_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FINISHES.forEach { f ->
                        FilterChip(selected = item.foil == f, onClick = { vm.setFoil(item.id, f) }, label = { Text(finishLabel(f)) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LANGUAGES.forEach { l ->
                        FilterChip(selected = item.language == l, onClick = { vm.setLanguage(item.id, l) }, label = { Text(l.uppercase()) })
                    }
                }
                if (item.alternatives.isNotEmpty()) {
                    Text(
                        stringResource(R.string.scanner_other_printings),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            items(item.alternatives, key = { it.i }) { p ->
                ListItem(
                    modifier = Modifier.clickable { vm.setPrinting(item.id, p) },
                    headlineContent = { Text("${p.setCode.uppercase()} #${p.collectorNumber}") },
                    supportingContent = { if (p.name != item.printing.name) Text(p.name) },
                )
            }
            item { Spacer(Modifier.width(1.dp).padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun finishLabel(f: String) = when (f) {
    "foil" -> stringResource(R.string.scanner_foil)
    "etched" -> stringResource(R.string.scanner_etched)
    else -> stringResource(R.string.scanner_nonfoil)
}
