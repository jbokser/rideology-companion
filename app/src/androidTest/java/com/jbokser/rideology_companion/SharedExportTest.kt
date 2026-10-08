package com.jbokser.rideology_companion

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SharedExportTest {
    @get:Rule
    val rule = AndroidComposeTestRule(
        ActivityScenarioRule<MainActivity>(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
                action = Intent.ACTION_SEND_MULTIPLE
                type = "text/plain"
            }
        )
    ) { scenarioRule ->
        var activity: MainActivity? = null
        scenarioRule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test
    fun rideologyShareResolvesAndPreservesEmojiTitle() {
        rule.activityRule.scenario.onActivity { activity ->
            val matches = activity.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain"), PackageManager.MATCH_DEFAULT_ONLY
            )
            assertTrue(matches.any { it.activityInfo.packageName == activity.packageName })
            val uri = Uri.parse("android.resource://${activity.packageName}/${R.raw.emoji_ride}")
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND_MULTIPLE
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))
                clipData = ClipData.newRawUri("Ride log", uri)
            })
        }
        waitForTitle("Ride 🏍️ 🇦🇷 👩🏽‍🔧 Café")
        rule.activityRule.scenario.recreate()
        waitForTitle("Ride 🏍️ 🇦🇷 👩🏽‍🔧 Café")
    }

    @Test
    fun shiftJisExportKeepsTemperatureAndValidGearData() {
        rule.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND_MULTIPLE
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(
                    Uri.parse("android.resource://${activity.packageName}/${R.raw.shift_jis_ride}")
                ))
            })
        }
        waitForTitle("Ride 日本")
        rule.onNodeWithText("Data notes").assertDoesNotExist()
        rule.onNodeWithText("Max water temperature").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("90.0 °C").assertExists()
        rule.onNodeWithText("Max for each gear").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Invalid gear values. These samples are excluded from per-gear metrics.").assertDoesNotExist()
    }

    @Test
    fun clipDataFilesCanBeSelectedAfterRecreation() {
        rule.activityRule.scenario.onActivity { activity ->
            val clip = ClipData.newRawUri("Ride logs", Uri.parse("android.resource://${activity.packageName}/${R.raw.sample_ride}"))
            clip.addItem(ClipData.Item(Uri.parse("android.resource://${activity.packageName}/raw/emoji_ride")))
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND_MULTIPLE
                type = "text/plain"
                clipData = clip
            })
        }
        rule.waitUntil(10000) { rule.onAllNodesWithText("Choose a ride log").fetchSemanticsNodes().isNotEmpty() }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10000) { rule.onAllNodesWithText("Choose a ride log").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasClickAction() and hasText("emoji", substring = true)).onFirst().performClick()
        waitForTitle("Ride 🏍️ 🇦🇷 👩🏽‍🔧 Café")
    }

    private fun waitForTitle(title: String) {
        rule.waitUntil(10000) { rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(title).assertIsDisplayed()
    }
}
