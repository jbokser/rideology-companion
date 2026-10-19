package com.jbokser.rideology_companion

import com.jbokser.rideology_companion.data.*
import org.junit.Assert.*
import org.junit.Test

class TheoreticalGearSpeedTest {
    @Test
    fun eachGearUsesTheHighestHistoricalRpmForItsPair() {
        val record = GearRecord("Rider", "Bike", listOf(GearMaximum("1", 5000.0, 50.0),
            GearMaximum("2", 10000.0, 150.0), GearMaximum("3", 8000.0, 160.0)))
        val theoretical = RideReport.theoreticalGears(record.summary())
        assertEquals(listOf(10000.0, 10000.0, 10000.0), theoretical.map { it.rpm })
        assertEquals(listOf(100.0, 150.0, 200.0), theoretical.map { it.speed })
        assertTrue(RideReport.theoreticalSubtitle(record.summary()).contains("10000 rpm"))
        assertEquals(listOf(listOf("Gear", "km/h", "Diff %"), listOf("1", "100.0", "100.0%"),
            listOf("2", "150.0", "0.0%"), listOf("3", "200.0", "25.0%")), RideReport.theoreticalGearRows(record.summary()))
        assertFalse(RideReport.theoreticalSpeedMatches(record.gears[0], theoretical[0]))
        assertTrue(RideReport.theoreticalSpeedMatches(record.gears[1], theoretical[1]))
    }

    @Test
    fun amberThresholdIsInclusiveAndUsesUnroundedValues() {
        val historical = GearMaximum("1", 5000.0, 50.0)
        assertTrue(RideReport.theoreticalSpeedMatches(historical, historical.copy(speed = 51.0)))
        assertTrue(RideReport.theoreticalSpeedMatches(historical, historical.copy(speed = 49.0)))
        assertFalse(RideReport.theoreticalSpeedMatches(historical, historical.copy(speed = 51.0001)))
        assertFalse(RideReport.theoreticalSpeedMatches(historical, historical.copy(speed = null)))
    }

    @Test
    fun missingOrZeroInputsDoNotProduceTheoreticalSpeeds() {
        val record = GearRecord("Rider", "Bike", listOf(GearMaximum("1", null, 50.0),
            GearMaximum("2", 0.0, 100.0), GearMaximum("3", 5000.0, null), GearMaximum("4", 5000.0, 0.0)))
        assertTrue(RideReport.theoreticalGears(record.summary()).all { it.speed == null })
        assertTrue(RideReport.theoreticalGearRows(record.summary()).drop(1).all { it[1] == "N/A" && it[2] == "N/A" })
        assertTrue(RideReport.theoreticalGears(record.copy(gears = emptyList()).summary()).isEmpty())
    }
}
