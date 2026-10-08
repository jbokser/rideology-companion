package com.jbokser.rideology_companion.data

import java.io.Reader
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlin.math.*

data class Coordinate(val latitude: Double, val longitude: Double) {
    fun display(): String = "${dms(latitude, 'N', 'S')} ${dms(longitude, 'E', 'W')}"
    private fun dms(value: Double, positive: Char, negative: Char): String {
        val hundredths = (abs(value) * 360000).roundToLong()
        return String.format(Locale.US, "%c%03d°%02d′%05.2f″", if (value < 0) negative else positive,
            hundredths / 360000, (hundredths / 6000) % 60, (hundredths % 6000) / 100.0)
    }
}
data class Sample(val time: Long, val rpm: Double?, val speed: Double?, val temperature: Double?, val gear: String?, val coordinate: Coordinate?)
data class RideLog(val title: String, val samples: List<Sample>, val warnings: List<String>)
data class Peak(val value: Double, val seconds: Double, val meters: Double?)
data class GearMaximum(val gear: String, val rpm: Double?, val speed: Double?)
data class RideSummary(
    val title: String, val engine: Peak?, val speed: Peak?, val acceleration: Peak?, val braking: Peak?,
    val temperature: Peak?, val idle: Double?, val average: Double?, val median: Double?,
    val totalSeconds: Double, val movingSeconds: Double?, val distanceMeters: Double?,
    val start: Coordinate?, val end: Coordinate?, val straightMeters: Double?, val bearing: Double?,
    val gears: List<GearMaximum>, val warnings: List<String>, val maxSpeedLocation: Coordinate? = null,
    val telemetry: RideTelemetry? = null, val speedDistribution: List<SpeedDistanceBin> = emptyList()
)

object RideCsvParser {
    private val fields = setOf("elapsed_msec", "gps_latitude", "gps_longitude", "water_temperature(℃)", "engine_RPM", "wheel_speed(km/h)", "gear_position")

    fun parse(input: InputStream): RideLog {
        val bytes = input.readBytes()
        fun decode(charset: Charset, action: CodingErrorAction = CodingErrorAction.REPORT): String = charset.newDecoder()
            .onMalformedInput(action)
            .onUnmappableCharacter(action)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val bomCharset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> null
        }
        val charset = bomCharset ?: if (runCatching { decode(Charsets.UTF_8) }.isSuccess) Charsets.UTF_8 else Charset.forName("windows-31j")
        val decoded = try {
            decode(charset, if (charset == Charset.forName("windows-31j")) CodingErrorAction.REPLACE else CodingErrorAction.REPORT)
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Unsupported CSV encoding. Use UTF-8, Shift-JIS, or UTF-16 with a byte order mark.")
        }
        return parse(decoded.reader())
    }

    fun parse(reader: Reader): RideLog {
        var title = "Untitled ride"
        var header: Map<String, Int>? = null
        val samples = mutableListOf<Sample>()
        val warnings = linkedSetOf<String>()
        for (row in records(reader)) {
            if (row.all { it.isBlank() }) continue
            if (header == null) {
                val names = row.map { it.trim().removePrefix("\uFEFF") }
                if (names.firstOrNull().equals("Title", true)) title = row.drop(1).joinToString(",").replace("\uFFFD", "").trim().ifEmpty { title }
                if ("elapsed_msec" in names) {
                    val selectedNames = names.filter { it in fields }
                    require(selectedNames.distinct().size == selectedNames.size) { "The data header contains duplicate measurement columns." }
                    header = names.mapIndexedNotNull { index, name -> if (name in fields) name to index else null }.toMap()
                    (fields - header.keys).forEach { warnings.add("Missing column: $it. Affected metrics are unavailable.") }
                }
                continue
            }
            fun cell(name: String): String? = header[name]?.let { row.getOrNull(it)?.trim() }
            fun number(name: String, valid: (Double) -> Boolean): Double? {
                if (name !in header) return null
                val value = cell(name)?.toDoubleOrNull()
                if (value == null || !value.isFinite() || !valid(value)) {
                    warnings.add("Invalid values in $name. Affected metrics are unavailable.")
                    return null
                }
                return value
            }
            val time = cell("elapsed_msec")?.toLongOrNull()
            require(time != null && time >= 0) { "Every data row must have a valid nonnegative elapsed_msec value." }
            require(samples.lastOrNull()?.let { time > it.time } != false) { "Elapsed timestamps must be strictly increasing." }
            val lat = number("gps_latitude") { it in -90.0..90.0 }
            val lon = number("gps_longitude") { it in -180.0..180.0 }
            val gear = cell("gear_position")?.takeIf { it == "N" || (it.toIntOrNull()?.let { n -> n > 0 } == true) }
            samples.add(Sample(time, number("engine_RPM") { it >= 0 }, number("wheel_speed(km/h)") { it >= 0 },
                number("water_temperature(℃)") { true }, gear, if (lat != null && lon != null) Coordinate(lat, lon) else null))
            require(samples.size <= 500000) { "This version supports up to 500,000 samples per file." }
        }
        require(header != null) { "No data header containing elapsed_msec was found." }
        require(samples.size >= 2) { "At least two data samples are required." }
        if (samples.zipWithNext().any { (a, b) -> b.time - a.time > 2000 }) warnings.add("Sampling gaps detected. Interval estimates hold the preceding sample through each gap.")
        return RideLog(title, samples, warnings.toList())
    }

    private fun records(reader: Reader): Sequence<List<String>> = sequence {
        val input = reader.buffered()
        input.mark(1)
        if (input.read() != '\uFEFF'.code) input.reset()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        while (true) {
            val code = input.read()
            if (code == -1) break
            val c = code.toChar()
            if (c == '"') {
                if (quoted) {
                    input.mark(1)
                    if (input.read() == '"'.code) field.append('"') else { input.reset(); quoted = false }
                } else {
                    require(field.isEmpty()) { "Invalid CSV quoting." }
                    quoted = true
                }
            } else if (!quoted && c == ',') {
                row.add(field.toString()); field.setLength(0)
            } else if (!quoted && (c == '\n' || c == '\r')) {
                if (c == '\r') { input.mark(1); if (input.read() != '\n'.code) input.reset() }
                row.add(field.toString()); field.setLength(0)
                yield(row); row = mutableListOf()
            } else field.append(c)
            require(field.length <= 1000000 && row.size <= 10000) { "CSV record is too large." }
        }
        require(!quoted) { "The CSV contains an unterminated quoted field." }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); yield(row) }
    }
}

object RideAnalyzer {
    fun analyze(log: RideLog): RideSummary {
        val s = log.samples
        val intervals = s.zipWithNext()
        fun duration(a: Sample, b: Sample) = (b.time - a.time) / 1000.0
        val speedValid = s.all { it.speed != null }
        fun peak(select: (Sample) -> Double?): Peak? {
            if (s.any { select(it) == null }) return null
            val maximum = s.maxOf { select(it)!! }
            val matches = intervals.filter { select(it.first) == maximum }
            return Peak(maximum, matches.sumOf { duration(it.first, it.second) },
                if (speedValid) matches.sumOf { it.first.speed!! / 3.6 * duration(it.first, it.second) } else null)
        }
        fun acceleration(braking: Boolean): Peak? {
            if (!speedValid) return null
            val values = intervals.map { (a, b) -> ((b.speed!! - a.speed!!) / 3.6 / duration(a, b) / 9.80665) * if (braking) -1 else 1 }
            val maximum = values.maxOrNull()!!.coerceAtLeast(0.0)
            val matches = intervals.indices.filter { maximum > 0 && values[it] == maximum }
            return Peak(maximum, matches.sumOf { duration(intervals[it].first, intervals[it].second) },
                matches.sumOf { intervals[it].first.speed!! / 3.6 * duration(intervals[it].first, intervals[it].second) })
        }
        val moving = if (speedValid) s.filter { it.speed!! != 0.0 }.map { it.speed!! }.sorted() else emptyList()
        val idle = if (speedValid && s.all { it.rpm != null }) s.filter { it.speed == 0.0 }.map { it.rpm!! }.takeIf { it.isNotEmpty() }?.average() else null
        val start = s.first().coordinate
        val end = s.last().coordinate
        val straight = if (start != null && end != null) gpsDistanceMeters(start, end) else null
        val bearing = if (start != null && end != null && straight!! > 0.01) bearing(start, end) else null
        val gears = s.filter { it.gear != null && it.gear != "N" }.groupBy { it.gear!! }.toSortedMap(compareBy { it.toInt() }).map { (gear, rows) ->
            GearMaximum(gear, if (rows.all { it.rpm != null }) rows.maxOf { it.rpm!! } else null, if (rows.all { it.speed != null }) rows.maxOf { it.speed!! } else null)
        }
        val maxSpeed = peak { it.speed }
        val maxSpeedLocation = maxSpeed?.let { maximum -> s.first { it.speed == maximum.value }.coordinate }
        val telemetry = RideTelemetryAnalyzer.analyze(log)
        return RideSummary(log.title, peak { it.rpm }, maxSpeed, acceleration(false), acceleration(true), peak { it.temperature }, idle,
            moving.takeIf { it.isNotEmpty() }?.average(), moving.takeIf { it.isNotEmpty() }?.let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 },
            (s.last().time - s.first().time) / 1000.0,
            if (speedValid) intervals.filter { it.first.speed != 0.0 }.sumOf { duration(it.first, it.second) } else null,
            if (speedValid) intervals.sumOf { it.first.speed!! / 3.6 * duration(it.first, it.second) } else null,
            start, end, straight, bearing, gears, log.warnings, maxSpeedLocation, telemetry, RideChartCalculations.distribution(telemetry))
    }
    private fun bearing(a: Coordinate, b: Coordinate): Double {
        val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
        val delta = Math.toRadians(b.longitude - a.longitude)
        return (Math.toDegrees(atan2(sin(delta) * cos(lat2), cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(delta))) + 360) % 360
    }
}

internal fun gpsDistanceMeters(a: Coordinate, b: Coordinate): Double {
    val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
    val h = sin((lat2 - lat1) / 2).pow(2) + cos(lat1) * cos(lat2) * sin(Math.toRadians(b.longitude - a.longitude) / 2).pow(2)
    return 6371000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
