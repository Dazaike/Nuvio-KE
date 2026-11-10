package com.nuvio.tv.core.plugin

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.Subtitle
import java.util.Locale

private const val MAX_RESULT_FIELD_CHARS = 2048
private const val MAX_RESULT_URL_CHARS = 8192
private const val MAX_HEADERS_PER_RESULT = 30
private const val MAX_SUBTITLES_PER_RESULT = 30

/**
 * Turns the JSON a scraper's `getStreams` returned into [LocalScraperResult]s.
 *
 * The scraper is third-party code, so every field is untrusted: unusable entries are dropped
 * rather than repaired, text is bounded, and the URL has to be one the player can open
 * (http(s), or a magnet link for torrent results). Request headers are limited to valid header
 * names and single-line values, so a hostile value cannot split a request.
 */
internal object PluginResultParser {
    private val gson = GsonBuilder().create()
    private val listType = object : TypeToken<List<Map<String, Any?>>>() {}.type
    private val headerName = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    fun parse(json: String): List<LocalScraperResult> {
        val items: List<Map<String, Any?>> = try {
            gson.fromJson<List<Map<String, Any?>>?>(json, listType)
        } catch (_: Exception) {
            null
        }.orEmpty()
        return items.asSequence()
            .mapNotNull(::toResult)
            .take(MAX_RESULTS_PER_SCRAPER)
            .toList()
    }

    private fun toResult(item: Map<String, Any?>?): LocalScraperResult? {
        if (item == null) return null
        // The url may be a string or an object with a url property.
        val url = when (val raw = item["url"]) {
            is String -> raw
            is Map<*, *> -> raw["url"] as? String
            else -> null
        }?.trim()?.takeIf { isPlayableUrl(it) } ?: return null

        val name = text(item["name"])
        return LocalScraperResult(
            title = text(item["title"]) ?: name ?: "Unknown",
            name = name,
            url = url,
            quality = text(item["quality"]),
            size = text(item["size"]),
            language = text(item["language"]),
            provider = text(item["provider"]),
            type = text(item["type"]),
            seeders = (item["seeders"] as? Number)?.toInt(),
            peers = (item["peers"] as? Number)?.toInt(),
            infoHash = text(item["infoHash"])?.takeIf { it.length <= 64 },
            headers = headers(item["headers"]),
            subtitles = subtitles(item["subtitles"])
        )
    }

    /** What the player can open from a scraper: web streams, and magnet links for the torrent path. */
    internal fun isPlayableUrl(url: String): Boolean {
        if (url.isBlank() || url.length > MAX_RESULT_URL_CHARS || url.contains("[object")) return false
        if (url.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
        val scheme = url.substringBefore(':', "").lowercase(Locale.ROOT)
        return scheme == "http" || scheme == "https" || scheme == "magnet"
    }

    private fun text(value: Any?): String? =
        value?.toString()?.trim()?.takeIf { it.isNotEmpty() && !it.contains("[object") }
            ?.take(MAX_RESULT_FIELD_CHARS)

    private fun headers(raw: Any?): Map<String, String>? {
        val map = raw as? Map<*, *> ?: return null
        return map.entries.asSequence()
            .mapNotNull { (k, v) ->
                val name = (k as? String)?.trim()?.takeIf { headerName.matches(it) } ?: return@mapNotNull null
                val value = (v as? String)?.takeIf { cleanHeaderValue(it) } ?: return@mapNotNull null
                name to value
            }
            .take(MAX_HEADERS_PER_RESULT)
            .toMap()
            .takeIf { it.isNotEmpty() }
    }

    private fun cleanHeaderValue(value: String): Boolean =
        value.length <= MAX_RESULT_FIELD_CHARS && value.none { it == '\n' || it == '\r' || it == '\u0000' }

    private fun subtitles(raw: Any?): List<Subtitle> {
        val list = raw as? List<*> ?: return emptyList()
        return list.asSequence().mapNotNull { entry ->
            val obj = entry as? Map<*, *> ?: return@mapNotNull null
            val url = text(obj["url"])?.takeIf {
                it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
            } ?: return@mapNotNull null
            Subtitle(
                id = text(obj["id"]) ?: url,
                url = url,
                lang = text(obj["language"]) ?: text(obj["lang"]) ?: "Unknown",
                addonName = text(obj["name"]) ?: "Plugin",
                addonLogo = null,
                headers = headers(obj["headers"])
            )
        }.take(MAX_SUBTITLES_PER_RESULT).toList()
    }
}
