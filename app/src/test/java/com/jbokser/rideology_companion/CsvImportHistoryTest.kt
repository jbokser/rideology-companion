package com.jbokser.rideology_companion

import com.jbokser.rideology_companion.data.CsvImportHistory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CsvImportHistoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun identicalContentMovesToFrontAndSurvivesReopening() {
        val directory = temporary.newFolder()
        val history = CsvImportHistory(directory)
        val original = "Title,日本語\nCSV contents".toByteArray()
        val first = history.record(original, "日本語")
        val second = history.record("Different contents".toByteArray(), "Same title")
        history.record(original.copyOf(), "日本語")

        val reopened = CsvImportHistory(directory)
        assertEquals(listOf(first, second), reopened.entries())
        assertArrayEquals(original, reopened.file(first).readBytes())
    }

    @Test
    fun historyRetainsMoreThanTenImportsAndKeepsSameTitlesDistinct() {
        val directory = temporary.newFolder()
        val history = CsvImportHistory(directory)
        val entries = (1..10).map { history.record("CSV $it".toByteArray(), "Same title") }
        history.record("CSV 1".toByteArray(), "Same title")
        val newest = history.record("CSV 11".toByteArray(), "Same title")

        assertEquals(listOf(newest, entries[0]) + entries.drop(1).reversed(), CsvImportHistory(directory).entries())
        assertTrue(history.file(entries[1]).exists())
        assertTrue(history.file(entries[0]).exists())
        assertEquals(11, directory.listFiles()!!.count { it.extension == "csv" })
    }

    @Test
    fun deletionRemovesOnlySelectedEntryAndItsCopyPermanently() {
        val directory = temporary.newFolder()
        val history = CsvImportHistory(directory)
        val first = history.record("First".toByteArray(), "Same title")
        val second = history.record("Second".toByteArray(), "Same title")
        history.delete(first)

        assertEquals(listOf(second), CsvImportHistory(directory).entries())
        assertFalse(history.file(first).exists())
        assertTrue(history.file(second).exists())
        history.delete(second)
        assertTrue(CsvImportHistory(directory).entries().isEmpty())
        assertFalse(history.file(second).exists())
        history.record("First".toByteArray(), "Same title")
        assertEquals(listOf(first), CsvImportHistory(directory).entries())
    }

    @Test
    fun emptyHistoryAndMissingCopiesDoNotExposeUnopenableEntries() {
        val history = CsvImportHistory(temporary.newFolder())
        assertTrue(history.entries().isEmpty())
        val entry = history.record(byteArrayOf(0, -1, 20), "Ride")
        history.file(entry).delete()
        assertTrue(history.entries().isEmpty())
    }
}
