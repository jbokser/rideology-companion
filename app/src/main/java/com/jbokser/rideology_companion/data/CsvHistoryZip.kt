package com.jbokser.rideology_companion.data

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class CsvZipImportResult(val imported: Int, val skipped: Int)

object CsvHistoryZip {
    private const val maximumCsvBytes = 32 * 1024 * 1024
    private const val maximumTotalBytes = 512L * 1024 * 1024

    fun export(output: OutputStream, history: CsvImportHistory) {
        val entries = history.entries()
        val usedNames = mutableSetOf<String>()
        ZipOutputStream(output.buffered()).use { zip ->
            entries.asReversed().forEach { entry ->
                val baseName = entry.exportFileName()
                var name = baseName
                var suffix = 2
                while (!usedNames.add(name.lowercase(java.util.Locale.ROOT))) {
                    name = "${baseName.dropLast(4)} (${suffix++}).csv"
                }
                zip.putNextEntry(ZipEntry(name))
                history.file(entry).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun import(input: InputStream, history: CsvImportHistory, onRide: (RideSummary) -> Unit = {}): CsvZipImportResult {
        var imported = 0
        var skipped = 0
        var totalBytes = 0L
        var count = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 5000) { "The ZIP contains too many entries (maximum 5,000)." }
                if (entry.isDirectory) continue
                val isCsv = entry.name.endsWith(".csv", ignoreCase = true) &&
                    '/' !in entry.name && '\\' !in entry.name
                val limit = maximumCsvBytes
                val data = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val length = zip.read(buffer)
                    if (length < 0) break
                    totalBytes += length
                    require(data.size().toLong() + length <= limit && totalBytes <= maximumTotalBytes) {
                        "The ZIP exceeds the import size limits (32 MB per CSV, 512 MB total)."
                    }
                    if (isCsv) data.write(buffer, 0, length)
                }
                if (!isCsv) continue
                val bytes = data.toByteArray()
                val name = entry.name
                val ride = try {
                    RideAnalyzer.analyze(RideCsvParser.parse(bytes.inputStream(), name,
                        savedTitle = name.dropLast(4)))
                } catch (_: IllegalArgumentException) {
                    skipped++
                    continue
                }
                onRide(ride)
                history.record(bytes, ride.title)
                imported++
            }
        }
        require(imported + skipped > 0) { "No CSV files were found at the root of the ZIP." }
        return CsvZipImportResult(imported, skipped)
    }
}
