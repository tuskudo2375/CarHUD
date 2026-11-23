package com.mapsbridge.headunit

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object UrlResolver {
    private const val TAG = "MapsBridgeUrl"
    private val urlPattern = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val coordinatePatterns = listOf(
        Regex("@(-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)"),
        Regex("!3d(-?\\d+(?:\\.\\d+)?)!4d(-?\\d+(?:\\.\\d+)?)"),
        Regex("[?&]q=(-?\\d+(?:\\.\\d+)?)(?:%2C|,)(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
        Regex("(?:^|\\s)(-?\\d{1,3}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)(?:$|\\s)")
    )

    fun extractDestination(rawPayload: String): String {
        val raw = rawPayload.trim()
        if (raw.isEmpty()) return ""
        extractCoordinates(raw)?.let { return it }

        val sharedUrl = urlPattern.find(raw)?.value?.trimEnd('.', ',', ')', ']', '}', '"', '\'')
        if (sharedUrl != null) {
            val finalUrl = followRedirects(sharedUrl)
            extractCoordinates(finalUrl)?.let { return it }
        }

        val cleanedPlace = raw.replace(urlPattern, " ").replace(Regex("\\s+"), " ").trim()
        return cleanedPlace.ifBlank { raw }
    }

    private fun followRedirects(address: String): String {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.requestMethod = "GET"
            connection.connect()
            connection.url.toString()
        } catch (e: Exception) {
            Log.i(TAG, "Could not resolve shared map URL; using the original text", e)
            address
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractCoordinates(candidate: String): String? {
        val decoded = runCatching { URLDecoder.decode(candidate, StandardCharsets.UTF_8.name()) }.getOrDefault(candidate)
        for (pattern in coordinatePatterns) {
            val match = pattern.find(decoded) ?: continue
            val latitude = match.groupValues[1].toDoubleOrNull() ?: continue
            val longitude = match.groupValues[2].toDoubleOrNull() ?: continue
            if (latitude in -90.0..90.0 && longitude in -180.0..180.0) return "$latitude,$longitude"
        }
        return null
    }
}
