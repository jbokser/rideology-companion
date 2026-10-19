package com.jbokser.rideology_companion.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

data class GearRecord(val rider: String, val bike: String, val gears: List<GearMaximum>) {
    val identity: String get() = "$rider@$bike"

    fun summary() = RideSummary(identity, null, null, null, null, null, null, null, null,
        0.0, null, null, null, null, null, null, gears, emptyList(), rider = rider, bike = bike)
}

data class GearRecordUpdate(val records: List<GearRecord>, val broken: List<String>)

/** Persists independent RPM and wheel-speed maxima for each exact rider/bike pair. */
class GearRecordStore(private val file: File) {
    @Synchronized
    fun entries(): List<GearRecord> {
        if (!file.exists()) return emptyList()
        return DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == 1) { "Unsupported gear records format." }
            List(input.readInt()) {
                val rider = input.readUTF()
                val bike = input.readUTF()
                val gears = List(input.readInt()) {
                    val gear = input.readUTF()
                    val rpm = if (input.readBoolean()) input.readDouble() else null
                    val speed = if (input.readBoolean()) input.readDouble() else null
                    GearMaximum(gear, rpm, speed)
                }
                GearRecord(rider, bike, gears)
            }
        }
    }

    @Synchronized
    fun record(ride: RideSummary): GearRecordUpdate {
        val records = entries()
        val rider = ride.rider?.takeIf { it.isNotBlank() } ?: return GearRecordUpdate(records, emptyList())
        val bike = ride.bike?.takeIf { it.isNotBlank() } ?: return GearRecordUpdate(records, emptyList())
        val eligible = ride.gears.filter { it.gear.toIntOrNull() in 1..6 && (it.rpm != null || it.speed != null) }
        if (eligible.isEmpty()) return GearRecordUpdate(records, emptyList())
        val previous = records.find { it.rider == rider && it.bike == bike }
        val merged = previous?.gears.orEmpty().associateBy { it.gear }.toMutableMap()
        val broken = mutableListOf<String>()
        for (gear in eligible) {
            val old = merged[gear.gear]
            if (old?.rpm != null && gear.rpm != null && gear.rpm > old.rpm)
                broken += "Gear ${gear.gear}: ${RideReport.format(old.rpm, 0)} → ${RideReport.format(gear.rpm, 0)} rpm"
            if (old?.speed != null && gear.speed != null && gear.speed > old.speed)
                broken += "Gear ${gear.gear}: ${RideReport.format(old.speed, 1)} → ${RideReport.format(gear.speed, 1)} km/h"
            merged[gear.gear] = GearMaximum(gear.gear, maximum(old?.rpm, gear.rpm), maximum(old?.speed, gear.speed))
        }
        val updated = GearRecord(rider, bike, merged.values.sortedBy { it.gear.toInt() })
        val result = (records.filterNot { it.rider == rider && it.bike == bike } + updated)
            .sortedWith(compareBy({ it.rider }, { it.bike }))
        if (result != records) write(result)
        return GearRecordUpdate(result, broken)
    }

    @Synchronized
    fun delete(record: GearRecord): List<GearRecord> {
        val retained = entries().filterNot { it.rider == record.rider && it.bike == record.bike }
        write(retained)
        return retained
    }

    private fun maximum(a: Double?, b: Double?): Double? = listOfNotNull(a, b).maxOrNull()

    private fun write(records: List<GearRecord>) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.outputStream().use { output ->
                val data = DataOutputStream(output)
                data.writeInt(1)
                data.writeInt(records.size)
                records.forEach { record ->
                    data.writeUTF(record.rider)
                    data.writeUTF(record.bike)
                    data.writeInt(record.gears.size)
                    record.gears.forEach {
                        data.writeUTF(it.gear)
                        data.writeBoolean(it.rpm != null)
                        it.rpm?.let(data::writeDouble)
                        data.writeBoolean(it.speed != null)
                        it.speed?.let(data::writeDouble)
                    }
                }
                data.flush()
                output.fd.sync()
            }
            check(temporary.renameTo(file)) { "Could not save gear records." }
        } finally {
            temporary.delete()
        }
    }
}
