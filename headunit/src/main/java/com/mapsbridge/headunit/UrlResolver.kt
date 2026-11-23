package com.mapsbridge.headunit

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object UrlResolver {
    private const val TAG = "MapsBridgeUrl"
    private const val MAX_REDIRECT_HOPS = 6
    private const val MAX_BODY_BYTES = 50 * 1024
    private val urlPattern = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val coordinatePatterns = listOf(
        Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)"),
        Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)"),
        Regex("[?&](?:q|ll|destination)=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)", RegexOption.IGNORE_CASE)
    )
    private val bodyCoordinatePatterns = listOf(
        Regex("center=(-?\\d+\\.\\d+)(?:%2C|,)(-?\\d+\\.\\d+)", RegexOption.IGNORE_CASE),
        Regex("\\[null,null,(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)\\]")
    )
    private val placePathPattern = Regex("/maps/place/([^/]+)/", RegexOption.IGNORE_CASE)

    data class ResolvedDestination(val query: String?, val fallbackUrl: String?)

    fun extractDestination(rawPayload: String): ResolvedDestination {
        val raw = rawPayload.trim()
        if (raw.isEmpty()) return ResolvedDestination(query = null, fallbackUrl = null)

        val sharedUrl = urlPattern.find(raw)?.value?.trimEnd('.', ',', ')', ']', '}', '"', '\'')
        if (sharedUrl == null) {
            return ResolvedDestination(query = extractCoordinates(raw) ?: raw, fallbackUrl = null)
        }

        val resolution = followRedirects(sharedUrl)
        resolution.coordinates?.let { return ResolvedDestination(query = it, fallbackUrl = null) }
        resolution.placeName?.let { return ResolvedDestination(query = it, fallbackUrl = null) }

        val cleanedPlace = raw.replace(urlPattern, " ").replace(Regex("\\s+"), " ").trim()
        if (cleanedPlace.isNotBlank()) return ResolvedDestination(query = cleanedPlace, fallbackUrl = null)
        return ResolvedDestination(query = null, fallbackUrl = sharedUrl)
    }

    private data class LinkResolution(val coordinates: String?, val placeName: String?)

    private fun followRedirects(address: String): LinkResolution {
        var current = runCatching { URL(address) }.getOrElse {
            Log.i(TAG, "Invalid shared URL", it)
            return LinkResolution(null, null)
        }

        for (hop in 0..MAX_REDIRECT_HOPS) {
            extractCoordinates(current.toString())?.let { return LinkResolution(it, null) }
            var connection: HttpURLConnection? = null
            try {
                connection = current.openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                )
                val responseCode = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (responseCode in 300..399 && !location.isNullOrBlank() && hop < MAX_REDIRECT_HOPS) {
                    val next = URL(current, location)
                    extractCoordinates(next.toString())?.let { return LinkResolution(it, null) }
                    current = next
                    continue
                }

                val body = readBodyPrefix(connection, MAX_BODY_BYTES)
                for (pattern in bodyCoordinatePatterns) {
                    extractCoordinates(body, listOf(pattern))?.let { return LinkResolution(it, null) }
                }
                val place = placePathPattern.find(current.toString())?.groupValues?.getOrNull(1)
                    ?.let(::decodePlaceName)
                return LinkResolution(coordinates = null, placeName = place)
            } catch (e: Exception) {
                Log.i(TAG, "Could not resolve shared map URL", e)
                return LinkResolution(null, null)
            } finally {
                connection?.disconnect()
            }
        }
        return LinkResolution(coordinates = null, placeName = null)
    }

    private fun readBodyPrefix(connection: HttpURLConnection, maxBytes: Int): String {
        val stream = runCatching {
            if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        }.getOrNull() ?: return ""
        return stream.use { input ->
            val buffer = ByteArray(4_096)
            val output = java.io.ByteArrayOutputStream(maxBytes)
            while (output.size() < maxBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
                if (count <= 0) break
                output.write(buffer, 0, count)
            }
            output.toString(StandardCharsets.UTF_8.name())
        }
    }

    private fun decodePlaceName(encoded: String): String? {
        val decoded = runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()) }
            .getOrDefault(encoded).replace('+', ' ').trim()
        return decoded.takeIf { it.isNotBlank() }
    }

    private fun extractCoordinates(candidate: String, patterns: List<Regex> = coordinatePatterns): String? {
        val decoded = runCatching { URLDecoder.decode(candidate, StandardCharsets.UTF_8.name()) }.getOrDefault(candidate)
        for (pattern in patterns) {
            val match = pattern.find(decoded) ?: continue
            val latitude = match.groupValues[1].toDoubleOrNull() ?: continue
            val longitude = match.groupValues[2].toDoubleOrNull() ?: continue
            if (latitude in -90.0..90.0 && longitude in -180.0..180.0) return "$latitude,$longitude"
        }
        return null
    }
}
