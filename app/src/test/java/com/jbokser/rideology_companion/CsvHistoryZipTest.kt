package com.jbokser.rideology_companion

import com.jbokser.rideology_companion.data.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CsvHistoryZipTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun history(name: String) = CsvImportHistory(temporary.newFolder(name))
    private fun csv(title: String) = "Title,$title\nUserAccount Name,Rider\nVehicle Nickname,Bike\nelapsed_msec,wheel_speed(km/h),engine_RPM,gear_position\n0,30,3000,1\n1000,50,5000,2".toByteArray()
    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun roundTripPreservesOriginalBytesRecoveredUnicodeTitlesAndOrder() {
        val source = history("source")
        val original = "Title,Caf".toByteArray() + byteArrayOf(0xfc.toByte(), 0xfc.toByte()) +
            " ".toByteArray() + byteArrayOf(0xfc.toByte(), 0xfc.toByte()) +
            "\nelapsed_msec,wheel_speed(km/h)\n0,0\n1000,30".toByteArray()
        source.record(original, "Café ☕")
        source.record(csv("Second ride"), "Second ride")
        val output = ByteArrayOutputStream()
        CsvHistoryZip.export(output, source)
        val names = mutableListOf<String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        assertEquals(source.entries().asReversed().map { it.exportFileName() }, names)
        assertTrue(names.all { '/' !in it && it.endsWith(".csv") })
        val restored = history("restored")
        val result = CsvHistoryZip.import(output.toByteArray().inputStream(), restored)
        assertEquals(2, result.imported)
        assertEquals(0, result.skipped)
        assertEquals(source.entries(), restored.entries())
        source.entries().forEach { assertArrayEquals(source.file(it).readBytes(), restored.file(it).readBytes()) }
        CsvHistoryZip.import(output.toByteArray().inputStream(), restored)
        assertEquals(2, restored.entries().size)
    }

    @Test
    fun importsRootCsvsIgnoresNestedFilesAndInvokesSharedAnalysis() {
        val target = history("target")
        val rides = mutableListOf<RideSummary>()
        val archive = zip("ride.CSV" to csv("Ride"), "nested/ignored.csv" to csv("Nested"), "bad.csv" to "invalid".toByteArray(),
            "readme.txt" to "ignored".toByteArray(), "../other.csv" to csv("Other"))
        val result = CsvHistoryZip.import(archive.inputStream(), target, rides::add)
        assertEquals(CsvZipImportResult(1, 1), result)
        assertEquals(1, target.entries().size)
        assertEquals("Rider", rides.first().rider)
        assertEquals(listOf("1", "2"), rides.first().gears.map { it.gear })
        assertFalse(java.io.File(temporary.root, "other.csv").exists())
    }

    @Test
    fun duplicateFilenamesAreDisambiguatedWithoutLosingCsvs() {
        val source = history("duplicates")
        source.record(csv("First"), "Same title")
        source.record(csv("Second"), "Same title")
        val output = ByteArrayOutputStream()
        CsvHistoryZip.export(output, source)
        val names = mutableListOf<String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        assertEquals(listOf("Same title.csv", "Same title (2).csv"), names)
        val restored = history("duplicates-restored")
        assertEquals(2, CsvHistoryZip.import(output.toByteArray().inputStream(), restored).imported)
        assertEquals(setOf("First", "Second"), restored.entries().map { it.title }.toSet())
    }

    @Test
    fun archiveWithoutCsvsReportsAnError() {
        val target = history("empty")
        try {
            CsvHistoryZip.import(zip("notes.txt" to byteArrayOf(1)).inputStream(), target)
            fail("Expected an error for an archive without CSV files")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("No CSV"))
        }
        assertTrue(target.entries().isEmpty())
    }
}
