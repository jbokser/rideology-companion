package com.jbokser.rideology_companion.data

import java.util.Locale

data class ReportMetric(val label: String, val value: String, val detail: String? = null)

object RideReport {
    fun metrics(ride: RideSummary): List<ReportMetric> = listOf(
        peak("Max engine speed", ride.engine, "rpm", 0),
        peak("Max wheel speed", ride.speed, "km/h", 1),
        peak("Max acceleration", ride.acceleration, "g", 2),
        peak("Max braking deceleration", ride.braking, "g", 2),
        peak("Max water temperature", ride.temperature, "°C", 1),
        ReportMetric("Average idle engine speed", value(ride.idle, "rpm", 0)),
        ReportMetric("Average moving speed", value(ride.average, "km/h", 1)),
        ReportMetric("Median moving speed", value(ride.median, "km/h", 1)),
        ReportMetric("Total time", time(ride.totalSeconds)),
        ReportMetric("Moving time", ride.movingSeconds?.let(::time) ?: "Unavailable"),
        ReportMetric("Distance", value(ride.distanceMeters?.div(1000), "km", 2)),
        ReportMetric("Straight-line distance", value(ride.straightMeters?.div(1000), "km", 2)),
        ReportMetric("Course", ride.bearing?.let { "${compass(it)} ${format(it, 0)}°" } ?: "Unavailable")
    )

    fun plainText(ride: RideSummary): String = report(ride, false)

    fun messageText(ride: RideSummary): String = report(ride, true)

    private fun report(ride: RideSummary, formatted: Boolean): String = buildString {
        fun heading(text: String) = if (formatted) "*$text*" else text
        appendLine(heading(ride.title))
        appendLine()
        appendLine(heading("Ride summary"))
        if (!formatted) appendLine("============")
        metrics(ride).forEach { metric ->
            append("${heading(metric.label)}: ${metric.value}")
            metric.detail?.let { append(" ($it)") }
            appendLine()
        }
    }.trimEnd()

    fun locationsText(ride: RideSummary, locationDetails: Map<Coordinate, LocationDetails>, formatted: Boolean = false): String = buildString {
        fun heading(text: String) = if (formatted) "*$text*" else text
        appendLine(heading(ride.title))
        appendLine()
        appendLine(heading("Locations"))
        listOf("Starting point" to ride.start, "Ending point" to ride.end,
            "Maximum speed location" to ride.maxSpeedLocation).forEach { (label, coordinate) ->
            appendLine("${heading(label)}: ${coordinate?.display() ?: "Unavailable"}")
            coordinate?.let {
                locationDetails[it]?.lines()?.forEach { line -> appendLine(line) }
                appendLine("https://www.google.com/maps/search/?api=1&query=${it.latitude},${it.longitude}")
            }
            appendLine()
        }
    }.trimEnd()

    fun highlightedGearRpm(ride: RideSummary): Double? =
        ride.gears.mapNotNull { it.rpm }.maxOrNull()?.takeUnless { it == ride.gears.lastOrNull()?.rpm }

    fun gearRows(ride: RideSummary): List<List<String>> = listOf(listOf("Gear", "RPM", "km/h")) + ride.gears.map {
            listOf(it.gear, it.rpm?.let { rpm -> format(rpm, 0) } ?: "N/A",
                it.speed?.let { speed -> format(speed, 1) } ?: "N/A")
    }

    fun gearTable(ride: RideSummary): List<String> {
        val rows = gearRows(ride)
        val widths = (0..2).map { column -> rows.maxOf { it[column].length } }
        return rows.map { row -> row.indices.joinToString("  ") { row[it].padStart(widths[it]) } }
    }

    fun format(value: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", value)

    private fun value(value: Double?, unit: String, digits: Int): String =
        value?.let { "${format(it, digits)} $unit" } ?: "Unavailable"

    private fun peak(label: String, peak: Peak?, unit: String, digits: Int): ReportMetric =
        if (peak == null) ReportMetric(label, "Unavailable") else ReportMetric(
            label, value(peak.value, unit, digits),
            "for ${format(peak.seconds, 1)} s / ${value(peak.meters, "m", 0)}"
        )

    private fun time(seconds: Double): String {
        val total = seconds.toLong()
        return String.format(Locale.US, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
    }

    private fun compass(bearing: Double): String =
        listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((bearing + 22.5) / 45).toInt() % 8]
}
