package com.jbokser.rideology_companion

import com.jbokser.rideology_companion.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.nio.charset.Charset

class RideLogTest {
    private fun analyze(csv: String) = RideAnalyzer.analyze(RideCsvParser.parse(StringReader(csv.trimIndent())))

    @Test
    fun sharedMessageUsesBoldHeadingsAndAlignedMonospaceTable() {
        val ride = analyze("Title,Message ride\nelapsed_msec,engine_RPM,wheel_speed(km/h),gear_position\n0,12000,179,1\n1000,900,9,2")
        val message = RideReport.messageText(ride)
        assertTrue(message.startsWith("*Message ride*\n"))
        assertTrue(message.contains("*Max engine speed*: 12000 rpm"))
        assertTrue(message.contains("*Max for each gear*\n```\n"))
        val table = message.substringAfter("```\n").substringBefore("\n```").lines()
        assertEquals(3, table.size)
        assertEquals(1, table.map { it.length }.distinct().size)
        assertTrue(table.contains("1     12000  179.0"))
        val copiedText = RideReport.plainText(ride)
        assertEquals(2, copiedText.lines().count { it == "```" })
        assertEquals(2, message.lines().count { it == "```" })
        assertEquals(table, copiedText.substringAfter("```\n").substringBefore("\n```").lines())
        assertFalse(copiedText.contains("*Max engine speed*"))
        assertTrue(copiedText.startsWith("Message ride\n"))
    }

    @Test
    fun maximumSpeedLocationUsesFirstMaximumSampleWithoutSubstitutingGps() {
        val csv = "elapsed_msec,wheel_speed(km/h),gps_latitude,gps_longitude\n0,10,-34,-58\n1000,60,-35,-59\n2000,60,-36,-60"
        assertEquals(Coordinate(-35.0, -59.0), analyze(csv).maxSpeedLocation)
        assertNull(analyze(csv.replace("60,-35,-59", "60,invalid,-59")).maxSpeedLocation)
        assertNull(analyze(csv.replace("1000,60", "1000,invalid")).maxSpeedLocation)
    }

    @Test
    fun malformedShiftJisTitleDoesNotRejectValidMeasurements() {
        val bytes = "Title,Burger 54 ".toByteArray() + byteArrayOf(0xfc.toByte(), 0xfc.toByte()) +
            "\nelapsed_msec,water_temperature(℃),wheel_speed(km/h),engine_RPM,gear_position\n0,80,36,2000,1\n1000,90,72,3000,2"
                .toByteArray(Charset.forName("windows-31j"))
        val ride = RideAnalyzer.analyze(RideCsvParser.parse(bytes.inputStream()))
        assertEquals("Burger 54", ride.title)
        assertEquals(90.0, ride.temperature!!.value, 0.001)
        assertEquals(72.0, ride.speed!!.value, 0.001)
        assertEquals(listOf("1", "2"), ride.gears.map { it.gear })
    }

    @Test
    fun malformedMeasurementIsUnavailableRatherThanSilentlyRepaired() {
        val bytes = "elapsed_msec,wheel_speed(km/h)\n0,1".toByteArray() + byteArrayOf(0xfc.toByte()) + "\n1000,20".toByteArray()
        val ride = RideAnalyzer.analyze(RideCsvParser.parse(bytes.inputStream()))
        assertNull(ride.speed)
        assertNull(ride.average)
        assertNull(ride.distanceMeters)
    }

    @Test
    fun japaneseExportEncodingPreservesTitleAndTemperatureColumn() {
        val csv = "Title,\"Ride, Café 日本\"\nelapsed_msec,water_temperature(℃),wheel_speed(km/h),engine_RPM,gear_position\n0,80,36,2000,1\n1000,90,72,3000,Error\n2000,85,20,1500,2"
        val ride = RideAnalyzer.analyze(RideCsvParser.parse(csv.toByteArray(Charset.forName("windows-31j")).inputStream()))
        // Windows-31J cannot represent é; preserve the exporter's replacement rather than inventing text.
        assertEquals("Ride, Caf? 日本", ride.title)
        assertEquals(90.0, ride.temperature!!.value, 0.001)
        assertEquals(listOf("1", "2"), ride.gears.map { it.gear })
        assertFalse(ride.warnings.any { it.contains("gear", ignoreCase = true) })
        assertFalse(ride.warnings.any { it.contains("Source encoding") })
        assertFalse(RideReport.plainText(ride).contains("Invalid gear"))
    }

    @Test
    fun utf8AndBomMarkedUtf16PreserveEmojiSequences() {
        val title = "Ride 🏍️ 🇦🇷 👩🏽‍🔧 Café"
        val csv = "Title,\"$title\"\nelapsed_msec,wheel_speed(km/h)\n0,0\n1000,36"
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16)) {
            assertEquals(title, RideCsvParser.parse(csv.toByteArray(charset).inputStream()).title)
        }
    }


    @Test
    fun reorderedColumnsAndMetadataPreserveSelectedFields() {
        val ride = analyze("""
            Export version,2
            Title,"Ride, with stops"
            Extra metadata,ignored
            ignored,gear_position,wheel_speed(km/h),engine_RPM,elapsed_msec,gps_longitude,water_temperature(℃),gps_latitude
            anything,N,0,1000,940,-58.48,80,-34.50
            anything,1,36,2000,1940,-58.47,90,-34.49
            anything,2,72,3000,3940,-58.46,95,-34.48
            anything,N,0,0,6940,-58.45,90,-34.47
        """)
        assertEquals("Ride, with stops", ride.title)
        assertEquals(6.0, ride.totalSeconds, 0.00001)
        assertEquals(5.0, ride.movingSeconds!!, 0.00001)
        assertEquals(80.0, ride.distanceMeters!!, 0.00001)
        assertEquals(500.0, ride.idle!!, 0.00001)
        assertEquals(54.0, ride.average!!, 0.00001)
        assertEquals(54.0, ride.median!!, 0.00001)
        assertEquals(72.0, ride.speed!!.value, 0.00001)
        assertEquals(3.0, ride.speed.seconds, 0.00001)
        assertEquals(60.0, ride.speed.meters!!, 0.00001)
        assertEquals(10 / 9.80665, ride.acceleration!!.value, 0.00001)
        assertEquals((20.0 / 3) / 9.80665, ride.braking!!.value, 0.00001)
        assertEquals(listOf("1", "2"), ride.gears.map { it.gear })
        assertEquals(-34.50, ride.start!!.latitude, 0.000001)
        assertNotNull(ride.straightMeters)
        assertTrue(ride.warnings.any { it.contains("gaps") })
    }

    @Test
    fun missingColumnsLeaveOnlyAffectedMetricsUnavailable() {
        val ride = analyze("""
            elapsed_msec,wheel_speed(km/h)
            0,10
            1000,20
        """)
        assertNull(ride.engine)
        assertNull(ride.temperature)
        assertNull(ride.start)
        assertEquals(15.0, ride.average!!, 0.00001)
        assertEquals(1.0, ride.movingSeconds!!, 0.00001)
        assertTrue(ride.warnings.any { it.contains("engine_RPM") })
    }

    @Test
    fun duplicateUnusedColumnsDoNotPreventImport() {
        val ride = analyze("elapsed_msec,unused,unused,wheel_speed(km/h)\n0,a,b,10\n1000,c,d,20")
        assertEquals(15.0, ride.average!!, 0.00001)
    }

    @Test
    fun invalidSpeedIsNotConvertedToZero() {
        val ride = analyze("""
            elapsed_msec,wheel_speed(km/h),engine_RPM
            0,invalid,1000
            1000,20,2000
        """)
        assertNull(ride.average)
        assertNull(ride.movingSeconds)
        assertNull(ride.distanceMeters)
        assertNull(ride.acceleration)
        assertEquals(2000.0, ride.engine!!.value, 0.00001)
        assertEquals(0.0, ride.engine.seconds, 0.00001)
        assertNull(ride.engine.meters)
    }

    @Test
    fun stoppedRideHasZeroMovingTimeAndNoMovingSpeedStatistics() {
        val ride = analyze("""
            elapsed_msec,wheel_speed(km/h),engine_RPM,gear_position
            0,0,1000,1
            5000,0,1200,N
        """)
        assertEquals(1100.0, ride.idle!!, 0.00001)
        assertEquals(0.0, ride.movingSeconds!!, 0.00001)
        assertNull(ride.average)
        assertNull(ride.median)
        assertEquals(0.0, ride.acceleration!!.value, 0.00001)
    }

    @Test
    fun quotedMultilineTitleAndBomAreAccepted() {
        val csv = "\uFEFFTitle,\"First line\nSecond \"\"quoted\"\" line\"\r\nelapsed_msec,wheel_speed(km/h)\r\n0,0\r\n1000,10"
        val ride = RideCsvParser.parse(StringReader(csv))
        assertEquals("First line\nSecond \"quoted\" line", ride.title)
        assertEquals(2, ride.samples.size)
    }

    @Test
    fun repeatedMaximumIntervalsAreSummed() {
        val ride = analyze("""
            elapsed_msec,wheel_speed(km/h)
            0,36
            1000,0
            3000,36
            6000,0
        """)
        assertEquals(4.0, ride.speed!!.seconds, 0.00001)
        assertEquals(40.0, ride.speed.meters!!, 0.00001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonIncreasingTimestampsAreRejected() {
        analyze("elapsed_msec,wheel_speed(km/h)\n1000,10\n1000,20")
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingTimestampHeaderIsRejected() {
        analyze("engine_RPM,wheel_speed(km/h)\n1000,10\n2000,20")
    }

    @Test(expected = IllegalArgumentException::class)
    fun unterminatedQuotedFieldIsRejected() {
        analyze("elapsed_msec,wheel_speed(km/h)\n0,10\n1000,\"20")
    }

    @Test
    fun coordinateFormattingHandlesRoundingCarry() {
        assertEquals("S035°00′00.00″ W058°00′00.00″", Coordinate(-34.99999999, -58.0).display())
    }
}
