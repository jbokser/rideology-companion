package com.jbokser.rideology_companion.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Resolves available address components without substituting other address levels. */
object LocationLookup {
    private val mutex = Mutex()
    private var lastRequest = 0L

    suspend fun lookup(context: Context, coordinate: Coordinate): LocationDetails? = mutex.withLock {
        withContext(Dispatchers.IO) {
            // A separate cache schema refreshes earlier neighborhood-only results.
            val cache = context.getSharedPreferences("location_details_v1", Context.MODE_PRIVATE)
            val key = "${coordinate.latitude},${coordinate.longitude}"
            val now = System.currentTimeMillis()
            val cachedAt = cache.getLong("${key}_time", 0)
            val cached = cache.getString(key, "").orEmpty()
            val lifetime = if (cached.isEmpty()) 600_000L else 30L * 24 * 60 * 60 * 1000
            if (cachedAt > 0 && now - cachedAt in 0 until lifetime) {
                if (cached.isEmpty()) return@withContext null
                runCatching { detailsFromProperties(JSONObject(cached)) }.getOrNull()?.let { return@withContext it }
            }
            delay((1100 - (SystemClock.elapsedRealtime() - lastRequest)).coerceAtLeast(0))
            lastRequest = SystemClock.elapsedRealtime()
            val connection = URL("https://photon.komoot.io/reverse?lat=${coordinate.latitude}&lon=${coordinate.longitude}&limit=1&radius=0.5")
                .openConnection() as HttpURLConnection
            val details = try {
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", "RideologyCompanion/${com.jbokser.rideology_companion.BuildConfig.VERSION_NAME} (https://github.com/jbokser/rideology-companion)")
                if (connection.responseCode == 200) {
                    val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    detailsFromResponse(body)
                } else null
            } catch (_: Exception) {
                null
            } finally {
                connection.disconnect()
            }
            cache.edit().putString(key, details?.let(::cacheJson).orEmpty()).putLong("${key}_time", now).apply()
            details
        }
    }

    internal fun detailsFromResponse(body: String): LocationDetails? {
        val properties = JSONObject(body).optJSONArray("features")?.optJSONObject(0)?.optJSONObject("properties") ?: return null
        return detailsFromProperties(properties)
    }

    internal fun detailsFromProperties(properties: JSONObject): LocationDetails? {
        fun field(key: String): String? {
            val value = properties.opt(key)
            if (value !is String && value !is Number) return null
            return value.toString().trim().takeIf { it.isNotEmpty() && it != "null" }
        }
        return LocationDetails(field("street"), field("housenumber"), field("district"), field("city"),
            field("state")).takeIf { it.lines().isNotEmpty() }
    }

    internal fun cacheJson(details: LocationDetails): String = JSONObject().apply {
        put("street", details.street)
        put("housenumber", details.houseNumber)
        put("district", details.neighborhood)
        put("city", details.city)
        put("state", details.state)
    }.toString()
}
