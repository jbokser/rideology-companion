package com.jbokser.rideology_companion.ui.charts

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.jbokser.rideology_companion.data.*
import com.jbokser.rideology_companion.ui.theme.ChartBlue
import com.jbokser.rideology_companion.ui.theme.ChartPurple
import com.jbokser.rideology_companion.ui.theme.RideGreen
import java.io.File
import java.util.UUID

enum class ChartImageKind(val label: String, val filePrefix: String) {
    SUMMARY("Ride summary", "summary"), TELEMETRY("Telemetry", "telemetry"), DISTRIBUTION("Speed distribution", "speed_distribution")
}

object ChartImageExporter {
    fun render(ride: RideSummary, kind: ChartImageKind): Bitmap {
        if (kind == ChartImageKind.SUMMARY) return renderSummary(ride)
        val telemetry = requireNotNull(ride.telemetry) { "No telemetry data is available." }
        require(if (kind == ChartImageKind.TELEMETRY) telemetry.intervals.isNotEmpty() else ride.speedDistribution.isNotEmpty()) {
            "No GPS data is available for this chart."
        }
        val width = if (kind == ChartImageKind.TELEMETRY) 1080 else maxOf(1080, 80 + (64 + ride.speedDistribution.size * 36) * 3)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 42f; typeface = Typeface.DEFAULT }
        val titleLines = wrapTitle(ride.title, paint, width - 80f)
        val header = 126 + titleLines.size * 54
        val height = header + if (kind == ChartImageKind.TELEMETRY) 3 * 752 + 40 else 900
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val native = android.graphics.Canvas(bitmap)
            native.drawColor(android.graphics.Color.BLACK)
            paint.color = android.graphics.Color.rgb(102, 255, 0)
            paint.textSize = 32f
            native.drawText("RIDEOLOGY COMPANION · ${kind.label}", 40f, 50f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 42f
            titleLines.forEachIndexed { index, line -> native.drawText(line, 40f, 112f + index * 54, paint) }
            val drawDensity = Density(3f)
            val scope = CanvasDrawScope()
            fun plot(top: Float, plotHeight: Float, draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
                native.save()
                native.translate(40f, top)
                scope.draw(drawDensity, LayoutDirection.Ltr, Canvas(native), Size(width - 80f, plotHeight), draw)
                native.restore()
            }
            if (kind == ChartImageKind.TELEMETRY) {
                val domain = telemetry.distanceKm.takeIf { it > 0 } ?: 1.0
                Channel.entries.forEachIndexed { index, channel ->
                    val top = header + index * 752f
                    val title = when (channel) { Channel.SPEED -> "Speed (km/h)"; Channel.RPM -> "Engine RPM"; Channel.GEAR -> "Gear" }
                    native.drawText(title, 40f, top + 42f, paint)
                    val maximum = when (channel) { Channel.SPEED -> telemetry.maximumSpeed; Channel.RPM -> telemetry.maximumRpm; Channel.GEAR -> null }
                    val color = when (channel) { Channel.SPEED -> ChartBlue; Channel.RPM -> ChartPurple; Channel.GEAR -> RideGreen }
                    val scale = if (channel == Channel.GEAR) ChartScale(6.0, 1.0) else RideChartCalculations.scale(maximum?.value ?: 0.0)
                    if (telemetry.points.none { it.value(channel) != null }) native.drawText("Unavailable", 40f, top + 110f, paint) else {
                        plot(top + 60, 660f) {
                            val left = 52.dp.toPx(); val plotTop = 30.dp.toPx()
                            val plotWidth = size.width - left - 12.dp.toPx()
                            val plotHeight = size.height - plotTop - 42.dp.toPx()
                            val path = telemetryPath(telemetry, channel, scale, domain, left, plotTop, plotWidth, plotHeight)
                            drawTelemetryPlot(path, channel, color, maximum, scale, domain, left, plotTop, plotWidth, plotHeight)
                        }
                    }
                }
            } else {
                native.drawText("Distance (km)", 40f, header + 42f, paint)
                plot(header + 60f, 810f) {
                    drawDistributionPlot(ride.speedDistribution, RideChartCalculations.scale(ride.speedDistribution.maxOf { it.distanceKm }))
                }
            }
            return bitmap
        } catch (exception: Exception) {
            bitmap.recycle()
            throw exception
        }
    }

    private fun renderSummary(ride: RideSummary): Bitmap {
        val width = 1080
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 34f
            typeface = Typeface.MONOSPACE
        }
        data class Line(val text: String, val x: Float, val size: Float, val green: Boolean = false) {
            val height: Float get() = size + 14f
        }
        val lines = buildList {
            fun text(value: String, indent: Boolean = false, size: Float = 34f, green: Boolean = false) {
                val x = if (indent) 96f else 56f
                paint.textSize = size
                wrapTitle(value, paint, width - x - 56f).forEach { add(Line(it, x, size, green)) }
            }
            fun space() { add(Line("", 56f, 14f)) }
            fun location(label: String, coordinate: Coordinate?) {
                text("$label:")
                text(coordinate?.display() ?: "Unavailable", indent = true)
                space()
            }
            text(ride.title)
            space()
            text("Ride summary", green = true)
            space()
            RideReport.metrics(ride).forEach { metric ->
                text("${metric.label}:")
                text(metric.value, indent = true)
                metric.detail?.let { text(it, indent = true, size = 26f) }
                space()
            }
            location("Starting point", ride.start)
            location("Ending point", ride.end)
            location("Maximum speed location", ride.maxSpeedLocation)
            text("Max for each gear", green = true)
            if (ride.gears.isEmpty()) text("No valid numbered gear data available.") else {
                RideReport.plainText(ride).substringAfter("```\n").substringBefore("\n```").lines()
                    .forEach { text(it) }
            }
            if (ride.warnings.isNotEmpty()) {
                space()
                text("Data notes", green = true)
                ride.warnings.forEach { text("- $it") }
            }
        }
        val bitmap = Bitmap.createBitmap(width, (160f + lines.sumOf { it.height.toDouble() }).toInt(), Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.BLACK)
            paint.color = android.graphics.Color.rgb(102, 255, 0)
            canvas.drawText("RIDEOLOGY COMPANION", 56f, 58f, paint)
            paint.textSize = 26f
            canvas.drawText("Ride log analysis by @jbokser · v${com.jbokser.rideology_companion.BuildConfig.VERSION_NAME}", 56f, 100f, paint)
            var baseline = 160f
            lines.forEach { line ->
                paint.textSize = line.size
                paint.color = if (line.green) android.graphics.Color.rgb(102, 255, 0) else android.graphics.Color.WHITE
                canvas.drawText(line.text, line.x, baseline, paint)
                baseline += line.height
            }
            paint.color = android.graphics.Color.rgb(102, 255, 0)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawRect(24f, 24f, width - 24f, bitmap.height - 24f, paint)
            return bitmap
        } catch (exception: Exception) {
            bitmap.recycle()
            throw exception
        }
    }

    private fun wrapTitle(title: String, paint: Paint, width: Float): List<String> = buildList {
        title.lines().forEach { paragraph ->
            var remaining = paragraph
            while (remaining.isNotEmpty()) {
                val fitted = paint.breakText(remaining, true, width, null).coerceAtLeast(1)
                val wordBoundary = if (fitted < remaining.length) remaining.take(fitted).lastIndexOf(' ') else -1
                val count = if (wordBoundary > 0) wordBoundary else fitted
                add(remaining.take(count))
                remaining = remaining.drop(count).trimStart()
            }
        }
    }.ifEmpty { listOf("Untitled ride") }

    fun shareIntent(context: Context, bitmap: Bitmap, kind: ChartImageKind, title: String): Intent {
        val directory = File(context.cacheDir, "chart_exports").apply { check(mkdirs() || isDirectory) }
        val file = File(directory, "${kind.filePrefix}_${UUID.randomUUID()}.jpg")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.chartfiles", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "$title · ${kind.label}")
            clipData = ClipData.newUri(context.contentResolver, kind.label, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun saveToGallery(context: Context, bitmap: Bitmap, kind: ChartImageKind): Uri {
        val name = "${kind.filePrefix}_${UUID.randomUUID()}.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Rideology Companion")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "The gallery image could not be created." }
            try {
                requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) > 0)
                return uri
            } catch (exception: Exception) {
                resolver.delete(uri, null, null)
                throw exception
            }
        }
        @Suppress("DEPRECATION")
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Rideology Companion")
            .apply { check(mkdirs() || isDirectory) }
        val file = File(directory, name)
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            return Uri.fromFile(file)
        } catch (exception: Exception) {
            file.delete()
            throw exception
        }
    }
}
