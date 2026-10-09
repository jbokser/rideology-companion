package com.jbokser.rideology_companion

import android.content.Intent
import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicReference
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Rule
import org.junit.Test

class RideScreenTest {
    @get:Rule
    // Keep the share action consistent so ActivityScenario can track new-intent lifecycle events.
    val rule = AndroidComposeTestRule(
        ActivityScenarioRule<MainActivity>(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/csv"
            }
        )
    ) { scenarioRule ->
        var activity: MainActivity? = null
        scenarioRule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test
    fun importedRideDisplaysTelemetryAndDistanceDistribution() {
        rule.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("android.resource://${activity.packageName}/${R.raw.sample_ride}"))
            })
        }
        rule.waitUntil(10000) { rule.onAllNodesWithText("Sample ride").fetchSemanticsNodes().isNotEmpty() }
        for (tag in listOf("telemetry_speed", "telemetry_rpm", "telemetry_gear")) {
            rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            chartScreenshot(tag)
        }
        rule.onNodeWithTag("telemetry_speed").assertHeightIsEqualTo(242.dp)
        rule.onNodeWithTag("telemetry_rpm").assertHeightIsEqualTo(198.dp)
        rule.onNodeWithTag("telemetry_gear").assertHeightIsEqualTo((220f / 3f * 1.1f).dp)
        rule.onNodeWithTag("speed_distribution_section").performScrollTo().assertIsDisplayed()
        chartScreenshot("speed_distribution")
        rule.onNodeWithTag("speed_distribution").assertIsDisplayed()
        rule.onNodeWithTag("telemetry_speed").assert(hasContentDescription("First maximum 72 km/h", substring = true))
        rule.onNodeWithTag("telemetry_rpm").assert(hasContentDescription("First maximum 3000 rpm", substring = true))
        rule.onNodeWithTag("speed_distribution").assert(hasContentDescription("Distance traveled by speed range", substring = true))
        rule.onNodeWithText("Engine RPM").assertExists()
        rule.onNodeWithText("Engine RPM (rpm)").assertDoesNotExist()
        rule.onAllNodes(hasText("First maximum:", substring = true)).assertCountEquals(0)
        rule.onAllNodes(hasText("GPS distance:", substring = true)).assertCountEquals(0)
    }

    @Test
    fun chartsShareReadableCompleteJpgAttachments() {
        rule.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("android.resource://${activity.packageName}/${R.raw.sample_ride}"))
            })
        }
        rule.waitUntil(10000) { rule.onAllNodesWithText("Sample ride").fetchSemanticsNodes().isNotEmpty() }
        val chooser = AtomicReference<Intent>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                chooser.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            for (label in listOf("Ride summary", "Telemetry", "Speed distribution", "Max for each gear")) {
                chooser.set(null)
                rule.onNodeWithContentDescription(if (label == "Ride summary") "Share ride summary" else if (label == "Max for each gear") "Share max for each gear" else "Share $label chart").performScrollTo().performClick()
                rule.onNodeWithText("Share JPG").performClick()
                rule.waitUntil(15000) { chooser.get() != null }
                @Suppress("DEPRECATION")
                val send = chooser.get().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                @Suppress("DEPRECATION")
                val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
                assertEquals("image/jpeg", send.type)
                assertEquals("content", uri.scheme)
                assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertEquals(uri, send.clipData!!.getItemAt(0).uri)
                val context = ApplicationProvider.getApplicationContext<android.content.Context>()
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                assertEquals(0xff, bytes[0].toInt() and 0xff)
                assertEquals(0xd8, bytes[1].toInt() and 0xff)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertNotNull(bitmap)
                assertTrue(bitmap.width >= 1080)
                assertTrue(bitmap.height >= when (label) { "Telemetry" -> 1900; "Max for each gear" -> 300; else -> 900 })
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                if (label == "Telemetry" || label == "Speed distribution") assertTrue(pixels.any { android.graphics.Color.blue(it) > 180 && android.graphics.Color.red(it) < 130 })
                else assertTrue(pixels.any { android.graphics.Color.green(it) > 180 && android.graphics.Color.red(it) < 160 && android.graphics.Color.blue(it) < 80 })
                if (label == "Telemetry") {
                    assertTrue(pixels.any { android.graphics.Color.red(it) > 130 && android.graphics.Color.blue(it) > 200 && android.graphics.Color.green(it) < 180 })
                    assertTrue(pixels.any { android.graphics.Color.green(it) > 180 && android.graphics.Color.red(it) < 160 && android.graphics.Color.blue(it) < 80 })
                }
                bitmap.recycle()
                java.io.File(context.cacheDir, when (label) { "Telemetry" -> "export_telemetry.jpg"; "Ride summary" -> "export_summary.jpg"; "Max for each gear" -> "export_gears.jpg"; else -> "export_distribution.jpg" }).writeBytes(bytes)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun zoomChartsOpenInLandscapeRestoreAndShareSinglePanelJpgs() {
        rule.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("android.resource://${activity.packageName}/${R.raw.sample_ride}"))
            })
        }
        rule.waitUntil(10000) { rule.onAllNodesWithText("Sample ride").fetchSemanticsNodes().isNotEmpty() }
        val chooser = AtomicReference<Intent>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                chooser.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            for ((title, tag) in listOf("Speed" to "speed", "Engine RPM" to "rpm")) {
                rule.onNodeWithContentDescription("Zoom $title").performScrollTo().performClick()
                rule.waitUntil(15000) { rule.onAllNodesWithTag("telemetry_zoom").fetchSemanticsNodes().isNotEmpty() }
                rule.waitUntil(10000) { rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                rule.onNodeWithTag("telemetry_zoom").assertIsDisplayed()
                rule.onNodeWithTag("telemetry_$tag").assertIsDisplayed()
                rule.onNodeWithTag(if (tag == "speed") "telemetry_rpm" else "telemetry_speed").assertDoesNotExist()
                rule.activityRule.scenario.recreate()
                rule.waitUntil(15000) { rule.onAllNodesWithTag("telemetry_zoom").fetchSemanticsNodes().isNotEmpty() }
                chartScreenshot("zoom_$tag")
                chooser.set(null)
                rule.onNodeWithContentDescription("Share $title chart").performClick()
                rule.onNodeWithText("Share JPG").performClick()
                rule.waitUntil(15000) { chooser.get() != null }
                @Suppress("DEPRECATION")
                val send = chooser.get().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                @Suppress("DEPRECATION")
                val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
                assertEquals("image/jpeg", send.type)
                assertEquals("Sample ride · $title", send.getStringExtra(Intent.EXTRA_SUBJECT))
                assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                val bytes = rule.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertEquals(1920, bitmap.width)
                assertTrue(bitmap.height >= 1080)
                assertTrue(bitmap.width > bitmap.height)
                bitmap.recycle()
                java.io.File(rule.activity.cacheDir, "export_zoom_$tag.jpg").writeBytes(bytes)
                rule.onNodeWithContentDescription("Back").performClick()
                rule.waitUntil(15000) { rule.onAllNodesWithTag("telemetry_zoom").fetchSemanticsNodes().isEmpty() }
                rule.waitUntil(10000) { rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun zoomSpeedCanBeSavedAsLandscapeGalleryImage() = verifyGalleryExport("Share Speed chart", "speed", "Zoom Speed")

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun distributionCanBeSavedAsPublishedGalleryImage() = verifyGalleryExport("Share Speed distribution chart", "speed_distribution")

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun summaryCanBeSavedAsPublishedGalleryImage() = verifyGalleryExport("Share ride summary", "summary")

    private fun verifyGalleryExport(description: String, prefix: String, zoom: String? = null) {
        rule.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("android.resource://${activity.packageName}/${R.raw.sample_ride}"))
            })
        }
        rule.waitUntil(10000) { rule.onAllNodesWithText("Sample ride").fetchSemanticsNodes().isNotEmpty() }
        zoom?.let {
            rule.onNodeWithContentDescription(it).performScrollTo().performClick()
            rule.waitUntil(15000) { rule.onAllNodesWithTag("telemetry_zoom").fetchSemanticsNodes().isNotEmpty() }
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resolver = context.contentResolver
        val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun savedIds(): Set<Long> = resolver.query(collection, arrayOf("_id"),
            "relative_path = ? AND _display_name LIKE ? AND is_pending = 0",
            arrayOf("Pictures/Rideology Companion/", "${prefix}_%.jpg"), null)!!.use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        val before = savedIds()
        var created = emptySet<Long>()
        try {
            if (zoom == null) rule.onNodeWithContentDescription(description).performScrollTo()
            rule.onNodeWithContentDescription(description).performClick()
            rule.onNodeWithText("Save to gallery").performClick()
            rule.waitUntil(15000) { created = savedIds() - before; created.isNotEmpty() }
            val uri = android.content.ContentUris.withAppendedId(collection, created.single())
            assertEquals("image/jpeg", resolver.getType(uri))
            val bitmap = resolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
            assertNotNull(bitmap)
            assertTrue(bitmap.width >= 1080)
            if (zoom != null) assertTrue(bitmap.width > bitmap.height)
            bitmap.recycle()
        } finally {
            (savedIds() - before).forEach { resolver.delete(android.content.ContentUris.withAppendedId(collection, it), null, null) }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun gearMaximaCanBeSavedAsPublishedGalleryImage() = verifyGalleryExport("Share max for each gear", "gears")

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun locationsCanBeCopiedAndSharedWithoutSummaryMetrics() {
        rule.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("android.resource://${activity.packageName}/${R.raw.sample_ride}"))
            })
        }
        rule.waitUntil(10000) { rule.onAllNodesWithText("Sample ride").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Share locations").performScrollTo().performClick()
        rule.onNodeWithText("Copy text").performClick()
        var copied = ""
        rule.waitUntil(10000) {
            rule.activityRule.scenario.onActivity { activity ->
                copied = activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            }
            copied.contains("Locations")
        }
        assertTrue(copied.contains("Starting point:"))
        assertTrue(copied.contains("Maximum speed location:"))
        assertTrue(copied.contains("query=-34.5082,-58.47964"))
        assertFalse(copied.contains("Max engine speed"))
        assertFalse(copied.contains("Max for each gear"))
        val chooser = AtomicReference<Intent>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                chooser.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            rule.onNodeWithContentDescription("Share locations").performClick()
            rule.onNodeWithText("Share as message").performClick()
            rule.waitUntil(10000) { chooser.get() != null }
            @Suppress("DEPRECATION")
            val send = chooser.get().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals("text/plain", send.type)
            val text = send.getStringExtra(Intent.EXTRA_TEXT)!!
            assertTrue(text.contains("*Locations*"))
            assertTrue(text.contains("query=-34.5082,-58.47964"))
            assertFalse(text.contains("Max engine speed"))
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun chartScreenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("chart_screenshots") != "true") return
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(rule.activity.cacheDir, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun openCsvOffersCompatibleFileBrowsers() {
        val chooser = AtomicReference<Intent>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                chooser.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            rule.onNodeWithText("Ride log analysis by @jbokser · v0.1b").assertExists()
            rule.onNodeWithText("Open Rideology app").assertIsDisplayed()
            rule.onNodeWithTag("app_icon").assertIsDisplayed()
            rule.onNodeWithText("Open CSV").performClick()
            rule.waitUntil(10000) { chooser.get() != null }
            @Suppress("DEPRECATION")
            val request = chooser.get().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_GET_CONTENT, request.action)
            assertEquals("*/*", request.type)
            assertTrue(request.hasCategory(Intent.CATEGORY_OPENABLE))
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun sharedCsvDisplaysSummaryAndSurvivesRecreation() {
        rule.onNodeWithText("Open CSV").assertIsDisplayed()
        rule.activityRule.scenario.onActivity { activity ->
            val uri = Uri.parse("android.resource://${activity.packageName}/${R.raw.sample_ride}")
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
            })
        }
        rule.waitUntil(10000) { rule.onAllNodesWithText("Sample ride").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Sample ride").assertIsDisplayed()
        rule.onNodeWithText("Average moving speed").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("54.0 km/h").assertCountEquals(2)
        rule.onNodeWithText("Moving time").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("0:00:05").assertExists()
        rule.onNodeWithText("Max for each gear").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Starting point").performScrollTo().assertExists()
        rule.onAllNodes(hasContentDescription("in maps", substring = true)).assertCountEquals(3)
        rule.onNodeWithText("Maximum speed location").assertExists()
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10000) { rule.onAllNodesWithText("Sample ride").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Sample ride").assertExists()
    }

    @Test
    fun scrollbarSupportsFingerDraggingAndTrackTaps() {
        rule.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("android.resource://${activity.packageName}/${R.raw.sample_ride}"))
            })
        }
        rule.waitUntil(10000) { rule.onAllNodesWithText("Sample ride").fetchSemanticsNodes().isNotEmpty() }
        val scrollbar = rule.onNodeWithTag("ride_scrollbar")
        scrollbar.assertWidthIsEqualTo(48.dp)
        // Touch the left side of the target, outside the narrower visible green track.
        scrollbar.performTouchInput {
            swipe(Offset(6f, height * 0.02f), Offset(6f, height * 0.95f), 700)
        }
        rule.waitUntil(10000) {
            val position = scrollbar.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            position.current >= position.range.endInclusive * 0.8f
        }
        rule.onNodeWithText("Calculation notes").assertDoesNotExist()
        scrollbar.performTouchInput { click(Offset(6f, 1f)) }
        rule.waitUntil(10000) {
            scrollbar.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current == 0f
        }
        rule.onNodeWithText("Sample ride").assertIsDisplayed()
        scrollbar.performTouchInput { click(Offset(6f, height - 1f)) }
        rule.waitUntil(10000) {
            val position = scrollbar.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            position.current >= position.range.endInclusive * 0.8f
        }
        scrollbar.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        rule.waitUntil(10000) {
            scrollbar.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current == 0f
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun summaryCanBeCopiedAndSharedAsPlainText() {
        rule.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("android.resource://${activity.packageName}/${R.raw.sample_ride}"))
            })
        }
        rule.waitUntil(10000) { rule.onAllNodesWithText("Sample ride").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("ride_scrollbar").assertIsDisplayed()
        rule.onNodeWithContentDescription("Share ride summary").performScrollTo().performClick()
        rule.onNodeWithText("Copy text").performClick()
        var copiedText = ""
        rule.waitUntil(10000) {
            rule.activityRule.scenario.onActivity { activity ->
                if (activity.hasWindowFocus()) {
                    copiedText = activity.getSystemService(ClipboardManager::class.java)
                        .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                }
            }
            copiedText.isNotEmpty()
        }
        assertTrue(copiedText.startsWith("Sample ride\n"))
        assertTrue(copiedText.contains("Max engine speed: 3000 rpm (for 3.0 s / 60 m)"))
        assertTrue(copiedText.contains("Moving time: 0:00:05"))
        assertFalse(copiedText.contains("maps"))
        assertFalse(copiedText.contains("Max for each gear"))
        assertFalse(copiedText.contains("Maximum speed location:"))

        val chooser = AtomicReference<Intent>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                chooser.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            rule.onNodeWithContentDescription("Share ride summary").performClick()
            rule.onNodeWithText("Share as message").performClick()
            rule.waitUntil(10000) { chooser.get() != null }
            @Suppress("DEPRECATION")
            val sendIntent = chooser.get().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, sendIntent.action)
            assertEquals("text/plain", sendIntent.type)
            val message = sendIntent.getStringExtra(Intent.EXTRA_TEXT)!!
            assertTrue(message.startsWith("*Sample ride*\n"))
            assertTrue(message.contains("*Ride summary*"))
            assertTrue(message.contains("*Max engine speed*: 3000 rpm"))
            assertFalse(message.contains("Max for each gear"))
            assertFalse(message.contains("maps"))
            assertEquals("Sample ride", sendIntent.getStringExtra(Intent.EXTRA_SUBJECT))
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun missingSharedAttachmentShowsRecoverableError() {
        rule.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/csv"
            })
        }
        rule.waitUntil(10000) { rule.onAllNodesWithText("Import error").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Import error").assertIsDisplayed()
        rule.onNodeWithText("Open CSV").assertIsDisplayed()
    }
}
