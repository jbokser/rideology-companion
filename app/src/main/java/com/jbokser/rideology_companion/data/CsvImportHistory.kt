package com.jbokser.rideology_companion.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

data class CsvHistoryEntry(val id: String, val title: String)

fun CsvHistoryEntry.exportFileName(): String =
        title.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").trim().take(100).ifEmpty { "Ride log" } + ".csv"

/** Stores private CSV copies in most-recently-imported order. */
class CsvImportHistory(private val directory: File) {
    private val index get() = File(directory, "index")

    @Synchronized
    fun entries(): List<CsvHistoryEntry> {
        if (!index.exists()) return emptyList()
        return DataInputStream(index.inputStream().buffered()).use { input ->
            val count = input.readInt()
            require(count >= 0) { "Invalid CSV history index." }
            List(count) {
                val id = input.readUTF()
                require(id.matches(Regex("[0-9a-f]{64}"))) { "Invalid CSV history entry." }
                CsvHistoryEntry(id, input.readUTF())
            }.filter { file(it).isFile }
        }
    }

    fun file(entry: CsvHistoryEntry): File {
        require(entry.id.matches(Regex("[0-9a-f]{64}")))
        return File(directory, "${entry.id}.csv")
    }

    @Synchronized
    fun record(bytes: ByteArray, title: String): CsvHistoryEntry {
        check(directory.isDirectory || directory.mkdirs()) { "Could not create CSV history." }
        val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val entry = CsvHistoryEntry(id, title)
        val previous = entries()
        val retained = listOf(entry) + previous.filter { it.id != id }
        val csv = file(entry)
        if (!csv.exists()) writeAtomically(csv) { it.write(bytes) }
        writeIndex(retained)
        val retainedFiles = retained.map { file(it).name }.toSet()
        directory.listFiles()?.filter { it.extension == "csv" && it.name !in retainedFiles }?.forEach { it.delete() }
        return entry
    }

    @Synchronized
    fun delete(entry: CsvHistoryEntry) {
        val retained = entries().filter { it.id != entry.id }
        val csv = file(entry)
        check(!csv.exists() || csv.delete()) { "Could not delete the saved CSV." }
        writeIndex(retained)
    }

    private fun writeIndex(entries: List<CsvHistoryEntry>) {
        writeAtomically(index) { output ->
            val data = DataOutputStream(output)
            data.writeInt(entries.size)
            entries.forEach { data.writeUTF(it.id); data.writeUTF(it.title) }
            data.flush()
        }
    }

    private fun writeAtomically(target: File, write: (java.io.OutputStream) -> Unit) {
        val temporary = File(directory, "${target.name}.tmp")
        try {
            temporary.outputStream().use { output ->
                write(output)
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "Could not save CSV history." }
        } finally {
            temporary.delete()
        }
    }

}
