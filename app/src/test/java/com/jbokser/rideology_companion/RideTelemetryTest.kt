package com.jbokser.rideology_companion

import com.jbokser.rideology_companion.data.*
import org.junit.Assert.*
import org.junit.Test

class RideTelemetryTest {
    private fun sample(time: Long, latitude: Double?, speed: Double?, gear: String? = "1", rpm: Double? = 2000.0) =
        Sample(time, rpm, speed, null, gear, latitude?.let { Coordinate(it, 0.0) })

    private fun analyze(vararg samples: Sample) = RideTelemetryAnalyzer.analyze(RideLog("Ride", samples.toList(), emptyList()))

    @Test
    fun distributionUsesEndingSpeedAndHalfOpenTwentyKmhBins() {
        val ride = analyze(sample(0, 0.0, 200.0), sample(1000, 0.01, 19.999), sample(2000, 0.02, 20.0),
            sample(3000, 0.03, 40.0), sample(13000, 1.0, 100.0), sample(14000, 1.01, null))
        val bins = RideChartCalculations.distribution(ride)
        assertEquals(listOf(0, 20, 40), bins.map { it.lowerKmh })
        bins.forEach { assertEquals(1.1119492664, it.distanceKm, 0.000001) }
        assertEquals(ride.intervals.filter { it.endingSpeed != null }.sumOf { it.distanceKm }, bins.sumOf { it.distanceKm }, 0.000001)
    }

    @Test
    fun distributionPreservesZeroSpeedGpsDistanceAndEmptyRanges() {
        val ride = analyze(sample(0, 0.0, 10.0), sample(1000, 0.01, 0.0), sample(2000, 0.02, 60.0))
        val bins = RideChartCalculations.distribution(ride)
        assertEquals(4, bins.size)
        assertTrue(bins[0].distanceKm > 0)
        assertEquals(0.0, bins[1].distanceKm, 0.0)
        assertEquals(0.0, bins[2].distanceKm, 0.0)
        assertTrue(bins[3].distanceKm > 0)
    }

    @Test
    fun chartScalesContainMaximaAndSupportShortDistancesAndZeroValues() {
        for (value in listOf(0.0, 0.001, 0.11, 64.0, 179.0, 5303.0, 9815.0)) {
            val scale = RideChartCalculations.scale(value)
            assertTrue(scale.maximum >= value)
            assertTrue(scale.maximum > 0)
            assertTrue(scale.tickStep > 0)
            assertTrue(scale.maximum / scale.tickStep <= 5.001)
        }
        assertTrue(RideChartCalculations.scale(0.11).maximum < 1)
    }

    @Test
    fun gapsDoNotAddGpsDistanceAndUseEndingSampleSpeed() {
        val ride = analyze(sample(0, 0.0, 10.0), sample(1000, 0.01, 20.0), sample(2000, 0.02, 40.0),
            sample(12000, 1.0, 60.0), sample(13000, 1.01, 80.0))
        assertEquals(1000.0, ride.medianSamplingMillis, 0.001)
        assertEquals(3.335847799, ride.distanceKm, 0.000001)
        assertEquals(ride.points[2].distanceKm, ride.points[3].distanceKm, 0.0)
        assertTrue(ride.points[3].breakBefore)
        assertEquals(listOf(1, 2, 4), ride.intervals.map { it.endingSampleIndex })
        assertEquals(listOf(20.0, 40.0, 80.0), ride.intervals.map { it.endingSpeed })
        assertEquals(ride.distanceKm, ride.intervals.sumOf { it.distanceKm }, 0.000001)
    }

    @Test
    fun intervalAtThresholdIsIncludedAndMissingGpsIsNotBridged() {
        val ride = analyze(sample(0, 0.0, 10.0), sample(1000, 0.01, 20.0), sample(2500, 0.02, 30.0),
            sample(3500, null, 40.0), sample(4500, 1.0, 50.0), sample(5500, 1.01, 60.0))
        assertEquals(listOf(1, 2, 5), ride.intervals.map { it.endingSampleIndex })
        assertTrue(ride.points[3].breakBefore)
        assertTrue(ride.points[4].breakBefore)
        assertEquals(ride.points[2].distanceKm, ride.points[4].distanceKm, 0.0)
    }

    @Test
    fun firstMaximaAndUnknownGearsArePreserved() {
        val ride = analyze(sample(0, 0.0, 10.0, "N", 1000.0), sample(1000, 0.01, 60.0, "1", 4000.0),
            sample(2000, 0.02, 60.0, "Error", 5000.0), sample(3000, 0.03, null, "7", 5000.0),
            sample(4000, 0.04, 20.0, "6", null))
        assertEquals(listOf(0, 1, null, null, 6), ride.points.map { it.gear })
        assertEquals(1, ride.maximumSpeed!!.sampleIndex)
        assertEquals(2, ride.maximumRpm!!.sampleIndex)
        assertNull(ride.intervals[2].endingSpeed)
    }

    @Test
    fun evenIntervalCountUsesMeanOfMiddleSamplingIntervals() {
        val ride = analyze(sample(0, 0.0, 0.0), sample(1000, 0.01, 0.0), sample(3000, 0.02, 0.0))
        assertEquals(1500.0, ride.medianSamplingMillis, 0.0)
        assertEquals(2, ride.intervals.size)
    }
}
