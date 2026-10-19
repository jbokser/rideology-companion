package com.jbokser.rideology_companion

import com.jbokser.rideology_companion.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GearRecordStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = GearRecordStore(File(temporary.root, "records"))
    private fun ride(rider: String? = "jbokser", bike: String? = "Z900", vararg gears: GearMaximum): RideSummary =
        RideAnalyzer.analyze(RideCsvParser.parse("elapsed_msec,wheel_speed(km/h)\n0,0\n1000,0".reader()))
            .copy(rider = rider, bike = bike, gears = gears.toList())

    @Test
    fun independentMaximaPersistAndOnlyStrictImprovementsNotify() {
        val store = store()
        val baseline = ride(gears = arrayOf(GearMaximum("1", 5000.0, 60.0), GearMaximum("2", 6000.0, 90.0)))
        assertTrue(store.record(baseline).broken.isEmpty())
        val update = store.record(ride(gears = arrayOf(GearMaximum("1", 5500.0, 50.0), GearMaximum("2", null, 100.0))))
        assertEquals(2, update.broken.size)
        assertEquals(listOf(GearMaximum("1", 5500.0, 60.0), GearMaximum("2", 6000.0, 100.0)), update.records.single().gears)
        assertEquals(update.records, store().entries())
        assertTrue(store.record(baseline).broken.isEmpty())
        assertTrue(store.record(update.records.single().summary()).broken.isEmpty())
    }

    @Test
    fun pairsAreIndependentAndDeletionEstablishesANewBaseline() {
        val store = store()
        val first = ride(gears = arrayOf(GearMaximum("1", 5000.0, 60.0)))
        store.record(first)
        store.record(ride(bike = "Z650", gears = arrayOf(GearMaximum("1", 7000.0, 80.0))))
        store.record(ride(rider = "other", gears = arrayOf(GearMaximum("1", 8000.0, 90.0))))
        assertEquals(3, store.entries().size)
        val deleted = store.entries().first { it.rider == "jbokser" && it.bike == "Z900" }
        assertEquals(2, store.delete(deleted).size)
        assertFalse(store().entries().contains(deleted))
        assertTrue(store.record(first).broken.isEmpty())
        store.entries().forEach { store.delete(it) }
        assertTrue(store().entries().isEmpty())
    }

    @Test
    fun missingIdentityUnknownGearsAndUnavailableValuesDoNotCreateRecords() {
        val store = store()
        for ((rider, bike) in listOf(null to "Z900", " " to "Z900", "jbokser" to null)) {
            assertTrue(store.record(ride(rider, bike, GearMaximum("1", 5000.0, 60.0))).records.isEmpty())
        }
        assertTrue(store.record(ride(gears = arrayOf(GearMaximum("N", 1200.0, 0.0), GearMaximum("7", 5000.0, 60.0),
            GearMaximum("Error", 6000.0, 70.0), GearMaximum("1", null, null)))).records.isEmpty())
        val partial = store.record(ride(gears = arrayOf(GearMaximum("1", null, 50.0))))
        assertNull(partial.records.single().gears.single().rpm)
        val filled = store.record(ride(gears = arrayOf(GearMaximum("1", 5000.0, null))))
        assertTrue(filled.broken.isEmpty())
        assertEquals(GearMaximum("1", 5000.0, 50.0), filled.records.single().gears.single())
    }
}
