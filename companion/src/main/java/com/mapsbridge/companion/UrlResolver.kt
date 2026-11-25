package com.mapsbridge.companion

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Resolves shared Google Maps links on the primary phone while its data connection is available. */
object UrlResolver {
    private const val TAG = "MapsBridgeUrl"
    private const val MAX_REDIRECT_HOPS = 6
    private const val MAX_BODY_BYTES = 96 * 1024
    private val urlPattern = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
    private val coordinatePatterns = listOf(
        Regex("@(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)"),
        Regex("!3d(-?\\d+(?:\\.\\d+)?)!4d(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
        Regex("[?&](?:q|ll|destination|center)=(-?\\d+(?:\\.\\d+)?)(?:%2C|,|%252C)(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
        Regex("(?:^|[?&])(?:q|ll|destination|center)=(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    )
    private val placePathPattern = Regex("/maps/place/([^/?#]+)", RegexOption.IGNORE_CASE)
    private val ogImagePattern = Regex("<meta[^>]+(?:property|name)=[\\\"']og:image[\\\"'][^>]+content=[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE)
    private val ogImageReversedPattern = Regex("<meta[^>]+content=[\\\"']([^\\\"']+)[\\\"'][^>]+(?:property|name)=[\\\"']og:image[\\\"']", RegexOption.IGNORE_CASE)

    data class ResolvedDestination(val query: String?, val fallbackUrl: String?)

    fun extractDestination(rawPayload: String): ResolvedDestination {
        val raw = rawPayload.trim()
        if (raw.isEmpty()) return ResolvedDestination(null, null)
        val sharedUrl = urlPattern.find(raw)?.value?.trimEnd('.', ',', ')', ']', '}', '"', '\'')
        if (sharedUrl == null) return ResolvedDestination(extractCoordinates(raw) ?: raw, null)

        val resolution = resolveUrl(sharedUrl)
        resolution.coordinates?.let { return ResolvedDestination(it, null) }
        resolution.query?.let { return ResolvedDestination(it, null) }
        resolution.placeName?.let { return ResolvedDestination(it, null) }
        val accompanyingText = raw.replace(urlPattern, " ").replace(Regex("\\s+"), " ").trim()
        if (accompanyingText.isNotBlank()) return ResolvedDestination(accompanyingText, sharedUrl)
        // Never send a URL as the navigation query. It remains available only as a Maps fallback.
        return ResolvedDestination(null, sharedUrl)
    }

    private data class LinkResolution(val coordinates: String? = null, val query: String? = null, val placeName: String? = null)

    private fun resolveUrl(address: String): LinkResolution {
        var current = runCatching { URL(address) }.getOrElse { return LinkResolution() }
        for (hop in 0..MAX_REDIRECT_HOPS) {
            extractCoordinates(current.toString())?.let { return LinkResolution(coordinates = it) }
            queryFromUrl(current)?.let { return LinkResolution(query = it) }
            var connection: HttpURLConnection? = null
            try {
                connection = current.openConnection() as? HttpURLConnection ?: return LinkResolution()
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
                val status = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (status in 300..399 && !location.isNullOrBlank() && hop < MAX_REDIRECT_HOPS) {
                    current = URL(current, location)
                    continue
                }
                val body = readBody(connection)
                extractCoordinates(body)?.let { return LinkResolution(coordinates = it) }
                val imageUrl = (ogImagePattern.find(body) ?: ogImageReversedPattern.find(body))?.groupValues?.getOrNull(1)
                if (!imageUrl.isNullOrBlank()) {
                    val decodedImage = decodeRepeatedly(imageUrl)
                    extractCoordinates(decodedImage)?.let { return LinkResolution(coordinates = it) }
                    queryFromUrl(runCatching { URL(decodedImage) }.getOrNull())?.let { return LinkResolution(query = it) }
                }
                val place = placePathPattern.find(current.path)?.groupValues?.getOrNull(1)?.let(::decode)
                    ?.replace('+', ' ')?.replace(Regex("\\s+"), " ")?.trim()
                return LinkResolution(placeName = place?.takeIf(String::isNotBlank))
            } catch (e: Exception) {
                Log.i(TAG, "Could not resolve shared map URL", e)
                return LinkResolution()
            } finally {
                connection?.disconnect()
            }
        }
        return LinkResolution()
    }

    private fun queryFromUrl(url: URL?): String? {
        url ?: return null
        val params = url.query.orEmpty().split('&').mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size != 2) null else decode(parts[0]).lowercase() to decode(parts[1])
        }
        for ((key, value) in params) {
            if (key !in setOf("q", "query", "destination", "center", "ll")) continue
            extractCoordinates(value)?.let { return it }
            if (key in setOf("q", "query") && value.isNotBlank() && !value.startsWith("http", true)) return value
        }
        return null
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = runCatching { if (connection.responseCode >= 400) connection.errorStream else connection.inputStream }.getOrNull() ?: return ""
        return stream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (output.size() < MAX_BODY_BYTES) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BODY_BYTES - output.size()))
                if (count <= 0) break
                output.write(buffer, 0, count)
            }
            output.toString(StandardCharsets.UTF_8.name())
        }
    }

    private fun extractCoordinates(candidate: String): String? {
        val decoded = decodeRepeatedly(candidate)
        for (pattern in coordinatePatterns) {
            val match = pattern.find(decoded) ?: continue
            val lat = match.groupValues[1].toDoubleOrNull() ?: continue
            val lng = match.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lng in -180.0..180.0) return "$lat,$lng"
        }
        return null
    }

    private fun decodeRepeatedly(value: String): String {
        var decoded = value
        repeat(2) {
            val next = runCatching { URLDecoder.decode(decoded, StandardCharsets.UTF_8.name()) }.getOrDefault(decoded)
            if (next == decoded) return decoded
            decoded = next
        }
        return decoded
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}
