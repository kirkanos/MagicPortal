package net.kirkanos.mtgportal.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import java.util.Locale

/**
 * The statistics page's charts, drawn with Compose instead of a chart
 * library. On a phone, labelled horizontal bars read better than Chart.js's
 * vertical columns with slanted axis labels, and three small building blocks
 * cost less than a dependency whose API changed between major versions.
 */

data class ChartItem(val label: String, val value: Double, val color: Color = PortalColors.Accent)

/** CHART_COLORS in dashboard.js, for series without a meaning of their own. */
val CHART_COLORS = listOf(
    Color(0xFF7B5CFF), Color(0xFF4FA8E0), Color(0xFF4DBB6A), Color(0xFFE0743D), Color(0xFFC9A24D),
    Color(0xFFE05656), Color(0xFFC9C9D4), Color(0xFF8A8A94), Color(0xFFF0E6C8), Color(0xFFB57BFF),
)

/** A titled card around one chart, like .chart-card on the web. */
@Composable
fun ChartCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PortalColors.BgCard).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

/**
 * One labelled bar per item, scaled to the largest. The order is the
 * caller's: sorted for rankings, natural for buckets and months.
 */
@Composable
fun RankedBars(items: List<ChartItem>, format: (Double) -> String, labelWidth: Int = 120) {
    val max = items.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(labelWidth.dp))
                Box(Modifier.weight(1f).height(14.dp)) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth((item.value / max).toFloat().coerceIn(0.01f, 1f))
                            .clip(RoundedCornerShape(3.dp)).background(item.color),
                    )
                }
                Text(format(item.value), fontSize = 12.sp, color = PortalColors.TextDim)
            }
        }
    }
}

/** A ring with a legend that carries value and share - the web shows the share on hover. */
@Composable
fun Doughnut(items: List<ChartItem>, format: (Double) -> String) {
    val total = items.sumOf { it.value }.takeIf { it > 0 } ?: 1.0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(Modifier.size(120.dp)) {
            val stroke = size.minDimension * 0.22f
            val inset = stroke / 2
            var start = -90f
            items.forEach { item ->
                val sweep = (item.value / total * 360).toFloat()
                drawArc(
                    color = item.color,
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke),
                )
                start += sweep
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(item.color))
                    Text(item.label, fontSize = 12.sp)
                    Text(
                        "${format(item.value)} · ${String.format(Locale.GERMANY, "%.1f %%", item.value / total * 100)}",
                        fontSize = 11.sp, color = PortalColors.TextDim,
                    )
                }
            }
        }
    }
}

data class LineSeries(val label: String, val values: List<Double>, val color: Color)

/**
 * Lines over a shared x axis (dates or months), filled below like the web's
 * Chart.js lines. Axis labels: first and last x, min and max y.
 */
@Composable
fun LineChart(xLabels: List<String>, series: List<LineSeries>, format: (Double) -> String) {
    val all = series.flatMap { it.values }
    if (xLabels.size < 2 || all.isEmpty()) return
    val min = all.min()
    val max = all.max().let { if (it == min) min + 1 else it }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(format(max), fontSize = 11.sp, color = PortalColors.TextDim, modifier = Modifier.weight(1f))
        }
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val dx = size.width / (xLabels.size - 1)
            fun y(v: Double) = (size.height - (v - min) / (max - min) * size.height).toFloat()
            series.forEach { s ->
                val line = Path()
                s.values.forEachIndexed { i, v -> if (i == 0) line.moveTo(0f, y(v)) else line.lineTo(i * dx, y(v)) }
                val fill = Path().apply {
                    addPath(line)
                    lineTo((s.values.size - 1) * dx, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(fill, s.color.copy(alpha = 0.12f))
                drawPath(line, s.color, style = Stroke(width = 2.dp.toPx()))
            }
        }
        Row {
            Text(format(min), fontSize = 11.sp, color = PortalColors.TextDim, modifier = Modifier.weight(1f))
        }
        Row {
            Text(xLabels.first(), fontSize = 11.sp, color = PortalColors.TextDim, modifier = Modifier.weight(1f))
            Text(xLabels.last(), fontSize = 11.sp, color = PortalColors.TextDim)
        }
        if (series.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                series.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
                        Text("${s.label}: ${format(s.values.last())}", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
