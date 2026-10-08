package com.jbokser.rideology_companion.data

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class TelemetryPoint(
    val distanceKm: Double,
    val speed: Double?,
    val rpm: Double?,
    val gear: Int?,
    val breakBefore: Boolean
)

data class GpsDistanceInterval(val endingSampleIndex: Int, val distanceKm: Double, val endingSpeed: Double?)
data class TelemetryMaximum(val sampleIndex: Int, val distanceKm: Double, val value: Double)
data class SpeedDistanceBin(val lowerKmh: Int, val upperKmh: Int, val distanceKm: Double)
data class ChartScale(val maximum: Double, val tickStep: Double)
data class RideTelemetry(
    val points: List<TelemetryPoint>,
    val intervals: List<GpsDistanceInterval>,
    val medianSamplingMillis: Double,
    val maximumSpeed: TelemetryMaximum?,
    val maximumRpm: TelemetryMaximum?
) {
    val distanceKm: Double get() = points.lastOrNull()?.distanceKm ?: 0.0
}

object RideChartCalculations {
    fun distribution(telemetry: RideTelemetry): List<SpeedDistanceBin> {
        val eligible = telemetry.intervals.filter { it.endingSpeed != null && it.endingSpeed >= 0 && it.distanceKm > 0 }
        val highest = eligible.maxOfOrNull { floor(it.endingSpeed!! / 20).toInt() } ?: return emptyList()
        val distances = DoubleArray(highest + 1)
        eligible.forEach { distances[floor(it.endingSpeed!! / 20).toInt()] += it.distanceKm }
        return distances.mapIndexed { index, distance -> SpeedDistanceBin(index * 20, (index + 1) * 20, distance) }
    }

    fun scale(maximum: Double): ChartScale {
        val positiveMaximum = maximum.takeIf { it > 0 } ?: 1.0
        val target = positiveMaximum / 4
        val magnitude = 10.0.pow(floor(log10(target)))
        val fraction = target / magnitude
        val step = when {
            fraction <= 1 -> magnitude
            fraction <= 2 -> 2 * magnitude
            fraction <= 5 -> 5 * magnitude
            else -> 10 * magnitude
        }
        return ChartScale(ceil(positiveMaximum / step) * step, step)
    }
}

object RideTelemetryAnalyzer {
    fun analyze(log: RideLog): RideTelemetry {
        val samples = log.samples
        require(samples.size >= 2) { "At least two data samples are required." }
        val durations = samples.zipWithNext { a, b -> b.time - a.time }.sorted()
        require(durations.first() > 0) { "Elapsed timestamps must be strictly increasing." }
        val middle = durations.size / 2
        val median = if (durations.size % 2 == 0) {
            durations[middle - 1] / 2.0 + durations[middle] / 2.0
        } else durations[middle].toDouble()
        val intervals = mutableListOf<GpsDistanceInterval>()
        var distanceKm = 0.0
        val points = samples.mapIndexed { index, sample ->
            val previous = samples.getOrNull(index - 1)
            val contiguous = previous != null && sample.time - previous.time <= median * 1.5 &&
                previous.coordinate != null && sample.coordinate != null
            if (contiguous) {
                val km = gpsDistanceMeters(requireNotNull(previous?.coordinate), requireNotNull(sample.coordinate)) / 1000.0
                distanceKm += km
                intervals.add(GpsDistanceInterval(index, km, sample.speed))
            }
            val gear = if (sample.gear == "N") 0 else sample.gear?.toIntOrNull()?.takeIf { it in 1..6 }
            TelemetryPoint(distanceKm, sample.speed, sample.rpm, gear, !contiguous)
        }
        fun maximum(value: (TelemetryPoint) -> Double?): TelemetryMaximum? {
            val max = points.mapNotNull(value).maxOrNull() ?: return null
            val index = points.indexOfFirst { value(it) == max }
            return TelemetryMaximum(index, points[index].distanceKm, max)
        }
        return RideTelemetry(points, intervals, median, maximum { it.speed }, maximum { it.rpm })
    }
}
