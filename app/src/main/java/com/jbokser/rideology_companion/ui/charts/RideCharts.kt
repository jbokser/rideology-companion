package com.jbokser.rideology_companion.ui.charts

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jbokser.rideology_companion.data.*
import com.jbokser.rideology_companion.ui.theme.ChartBlue
import com.jbokser.rideology_companion.ui.theme.ChartPurple
import com.jbokser.rideology_companion.ui.theme.RideGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.log10

internal enum class Channel { SPEED, RPM, GEAR }

@Composable
fun TelemetryCharts(telemetry: RideTelemetry) {
    if (telemetry.intervals.isEmpty()) {
        Text("No consecutive GPS samples are available for the telemetry charts.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TelemetryPanel(telemetry, Channel.SPEED, "Speed", "km/h", ChartBlue, telemetry.maximumSpeed)
        TelemetryPanel(telemetry, Channel.RPM, "Engine RPM", "rpm", ChartPurple, telemetry.maximumRpm)
        TelemetryPanel(telemetry, Channel.GEAR, "Gear", "", RideGreen, null)
    }
}

internal fun TelemetryPoint.value(channel: Channel): Double? = when (channel) {
    Channel.SPEED -> speed
    Channel.RPM -> rpm
    Channel.GEAR -> gear?.toDouble()
}

@Composable
private fun TelemetryPanel(
    telemetry: RideTelemetry, channel: Channel, title: String, unit: String,
    color: Color, maximum: TelemetryMaximum?
) {
    Text(if (unit.isEmpty() || channel == Channel.RPM) title else "$title ($unit)", style = MaterialTheme.typography.titleMedium)
    if (channel != Channel.GEAR && maximum == null || channel == Channel.GEAR && telemetry.points.none { it.gear != null }) {
        Text("No valid $title data available.")
        return
    }
    val scale = remember(maximum, channel) {
        if (channel == Channel.GEAR) ChartScale(6.0, 1.0) else RideChartCalculations.scale(maximum?.value ?: 0.0)
    }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val left = with(density) { 52.dp.toPx() }
    val right = with(density) { 12.dp.toPx() }
    val top = with(density) { 30.dp.toPx() }
    val bottom = with(density) { 42.dp.toPx() }
    val domain = telemetry.distanceKm.takeIf { it > 0 } ?: 1.0
    var line by remember(telemetry, channel) { mutableStateOf<Path?>(null) }
    LaunchedEffect(telemetry, channel, canvasSize, density, scale) {
        if (canvasSize.width <= left + right || canvasSize.height <= top + bottom) return@LaunchedEffect
        line = withContext(Dispatchers.Default) {
            telemetryPath(telemetry, channel, scale, domain, left, top,
                canvasSize.width - left - right, canvasSize.height - top - bottom)
        }
    }
    val description = "$title chart. Horizontal axis: cumulative GPS distance in kilometers. " +
        "Vertical axis: ${if (channel == Channel.GEAR) "neutral and gears 1 to 6" else unit}." +
        (maximum?.let { " First maximum ${RideReport.format(it.value, 0)} $unit at ${RideReport.format(it.distanceKm, 2)} km." } ?: "")
    Canvas(Modifier.fillMaxWidth().height(220.dp).onSizeChanged { canvasSize = it }
        .testTag("telemetry_${channel.name.lowercase()}").semantics { contentDescription = description }) {
        val width = size.width - left - right
        val height = size.height - top - bottom
        if (width <= 0 || height <= 0) return@Canvas
        drawTelemetryPlot(line, channel, color, maximum, scale, domain, left, top, width, height)
    }
}

internal fun DrawScope.labelPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.WHITE
    textSize = 10.sp.toPx()
    typeface = Typeface.MONOSPACE
}

internal fun DrawScope.axes(
    left: Float, top: Float, width: Float, height: Float, scale: ChartScale,
    domain: Double, gear: Boolean = false, horizontalTicks: Boolean = true
) {
    val paint = labelPaint()
    val count = (scale.maximum / scale.tickStep).toInt()
    for (index in 0..count) {
        val value = index * scale.tickStep
        val y = top + height - (value / scale.maximum * height).toFloat()
        drawLine(Color.White.copy(alpha = 0.18f), Offset(left, y), Offset(left + width, y), 1.dp.toPx())
        val digits = if (scale.tickStep < 1) ceil(-log10(scale.tickStep)).toInt() else 0
        val label = if (gear && index == 0) "N" else RideReport.format(value, digits)
        drawContext.canvas.nativeCanvas.drawText(label, left - 6.dp.toPx() - paint.measureText(label), y + paint.textSize / 3, paint)
    }
    drawLine(Color.White, Offset(left, top), Offset(left, top + height), 1.dp.toPx())
    drawLine(Color.White, Offset(left, top + height), Offset(left + width, top + height), 1.dp.toPx())
    if (horizontalTicks) {
        for (index in 0..3) {
            val label = RideReport.format(domain * index / 3, 2)
            val x = left + width * index / 3
            drawContext.canvas.nativeCanvas.drawText(label, x - paint.measureText(label) / 2,
                top + height + 17.dp.toPx(), paint)
        }
        val label = "Distance (km)"
        drawContext.canvas.nativeCanvas.drawText(label, left + (width - paint.measureText(label)) / 2,
            size.height - 3.dp.toPx(), paint)
    }
}

@Composable
fun SpeedDistributionChart(bins: List<SpeedDistanceBin>) {
    if (bins.isEmpty()) {
        Text("No GPS distance with valid speed data is available.")
        return
    }
    Text("Distance (km)", style = MaterialTheme.typography.bodySmall)
    val scale = remember(bins) { RideChartCalculations.scale(bins.maxOf { it.distanceKm }) }
    val summary = bins.joinToString("; ") {
        "${it.lowerKmh} to ${it.upperKmh} km/h: ${RideReport.format(it.distanceKm, 2)} kilometers"
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val chartWidth = maxOf(maxWidth, (64 + bins.size * 36).dp)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Canvas(Modifier.width(chartWidth).height(250.dp).testTag("speed_distribution")
                .semantics { contentDescription = "Distance traveled by speed range. $summary" }) {
                drawDistributionPlot(bins, scale)
            }
        }
    }
    Text("Intervals include the lower boundary and exclude the upper boundary. Swipe horizontally to view all ranges.",
        style = MaterialTheme.typography.bodySmall)
}

internal fun telemetryPath(
    telemetry: RideTelemetry, channel: Channel, scale: ChartScale, domain: Double,
    left: Float, top: Float, width: Float, height: Float
): Path {
    val path = Path()
    var previous: Offset? = null
    telemetry.points.forEach { sample ->
        val value = sample.value(channel)
        if (value == null) previous = null else {
            val point = Offset(left + (sample.distanceKm / domain * width).toFloat(),
                top + height - (value / scale.maximum * height).toFloat())
            if (previous == null || sample.breakBefore) path.moveTo(point.x, point.y) else {
                if (channel == Channel.GEAR) path.lineTo(point.x, previous!!.y)
                path.lineTo(point.x, point.y)
            }
            previous = point
        }
    }
    return path
}

internal fun DrawScope.drawTelemetryPlot(
    line: Path?, channel: Channel, color: Color, maximum: TelemetryMaximum?, scale: ChartScale,
    domain: Double, left: Float, top: Float, width: Float, height: Float
) {
    axes(left, top, width, height, scale, domain, channel == Channel.GEAR)
    line?.let { drawPath(it, color, style = Stroke(width = 2.dp.toPx())) }
    maximum?.let { peak ->
        val x = left + (peak.distanceKm / domain * width).toFloat()
        val y = top + height - (peak.value / scale.maximum * height).toFloat()
        drawCircle(color, 4.dp.toPx(), Offset(x, y))
        val unit = if (channel == Channel.SPEED) "km/h" else "rpm"
        val label = "Max ${RideReport.format(peak.value, 0)} $unit"
        val paint = labelPaint()
        val labelWidth = paint.measureText(label)
        val labelX = (x - labelWidth / 2).coerceIn(left, (left + width - labelWidth).coerceAtLeast(left))
        val labelY = (y - 10.dp.toPx()).coerceAtLeast(paint.textSize)
        drawRect(Color.Black, Offset(labelX - 2.dp.toPx(), labelY - paint.textSize),
            Size(labelWidth + 4.dp.toPx(), paint.textSize + 4.dp.toPx()))
        drawContext.canvas.nativeCanvas.drawText(label, labelX, labelY, paint)
    }
}

internal fun DrawScope.drawDistributionPlot(bins: List<SpeedDistanceBin>, scale: ChartScale) {
    val left = 52.dp.toPx()
    val top = 28.dp.toPx()
    val width = size.width - left - 12.dp.toPx()
    val height = size.height - top - 76.dp.toPx()
    axes(left, top, width, height, scale, 1.0, horizontalTicks = false)
    val cellWidth = width / bins.size
    val paint = labelPaint()
    bins.forEachIndexed { index, bin ->
        val x = left + index * cellWidth
        val barHeight = (bin.distanceKm / scale.maximum * height).toFloat()
        drawRect(ChartBlue, Offset(x + cellWidth * 0.15f, top + height - barHeight), Size(cellWidth * 0.7f, barHeight))
        val value = RideReport.format(bin.distanceKm, 2)
        drawContext.canvas.nativeCanvas.drawText(value, x + (cellWidth - paint.measureText(value)) / 2,
            top + height - barHeight - 6.dp.toPx(), paint)
        val range = "${bin.lowerKmh}–${bin.upperKmh}"
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.translate(x + cellWidth / 2, top + height + 13.dp.toPx())
        canvas.rotate(-45f)
        canvas.drawText(range, -paint.measureText(range), paint.textSize / 3, paint)
        canvas.restore()
    }
    val label = "Speed range (km/h)"
    drawContext.canvas.nativeCanvas.drawText(label, left + (width - paint.measureText(label)) / 2,
        size.height - 6.dp.toPx(), paint)
}
