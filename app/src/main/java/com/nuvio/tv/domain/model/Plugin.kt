package com.nuvio.tv.domain.model

import kotlinx.serialization.Serializable

/**
 * A plugin repository: one Nuvio `manifest.json` and the JavaScript scrapers it lists.
 *
 * Only the Nuvio JavaScript repository format is supported. External DEX / CloudStream
 * extensions run native code with the app's privileges and are intentionally not restored.
 */
@Serializable
data class PluginRepository(
    val id: String,
    val name: String,
    /** Canonical manifest URL (ends in `/manifest.json`). */
    val url: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val lastUpdated: Long = 0L,
    val scraperCount: Int = 0,
    /** Manifest `version`, shown on the repository row. */
    val version: String? = null
)

/**
 * A value a scraper asks the user for (a cookie, a key, a mirror host), declared in its manifest entry.
 * This is a KE extension of the Nuvio manifest: other Nuvio apps ignore the unknown `settings` key.
 * The value reaches the script as `SCRAPER_SETTINGS[key]`; it is stored on this TV only, so it never has
 * to be published with the repository.
 */
@Serializable
data class ScraperSettingField(
    val key: String,
    val label: String,
    val description: String? = null,
    val placeholder: String? = null
)

/** `manifest.json` of a plugin repository. Unknown keys are ignored (the format has grown over time). */
@Serializable
data class PluginManifest(
    val name: String,
    val version: String,
    val description: String? = null,
    val author: String? = null,
    val scrapers: List<ScraperManifestInfo> = emptyList()
)

/** One scraper entry of `manifest.json`. */
@Serializable
data class ScraperManifestInfo(
    val id: String,
    val name: String,
    val description: String? = null,
    val version: String,
    val filename: String,
    val supportedTypes: List<String> = listOf("movie", "tv"),
    val enabled: Boolean = true,
    val logo: String? = null,
    val contentLanguage: List<String>? = null,
    val supportedPlatforms: List<String>? = null,
    val disabledPlatforms: List<String>? = null,
    val formats: List<String>? = null,
    val supportedFormats: List<String>? = null,
    val supportsExternalPlayer: Boolean? = null,
    val limited: Boolean? = null,
    val settings: List<ScraperSettingField> = emptyList()
)

/** An installed scraper with its runtime state. */
@Serializable
data class ScraperInfo(
    /** `<repositoryId>:<manifest scraper id>`. */
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val filename: String,
    val supportedTypes: List<String>,
    /** The user's switch. New scrapers start off: third-party code runs only after an explicit enable. */
    val enabled: Boolean,
    /** The repository's own `enabled` flag; a scraper its repository disables cannot be switched on. */
    val manifestEnabled: Boolean,
    val logo: String?,
    val contentLanguage: List<String>,
    val repositoryId: String,
    val formats: List<String>?,
    /** Values this scraper asks the user for; empty when it needs none. */
    val settings: List<ScraperSettingField> = emptyList()
) {
    fun supportsType(type: String): Boolean {
        val normalizedType = normalizePluginType(type)
        return supportedTypes.any { normalizePluginType(it) == normalizedType }
    }
}

private fun normalizePluginType(value: String): String =
    when (value.lowercase()) {
        "series", "show", "other" -> "tv"
        else -> value.lowercase()
    }

/** One stream a scraper returned, before it becomes a [Stream]. */
data class LocalScraperResult(
    val title: String,
    val name: String? = null,
    val url: String,
    val quality: String? = null,
    val size: String? = null,
    val language: String? = null,
    val provider: String? = null,
    val type: String? = null,
    val seeders: Int? = null,
    val peers: Int? = null,
    val infoHash: String? = null,
    val headers: Map<String, String>? = null,
    val subtitles: List<Subtitle> = emptyList()
)

const val PLUGIN_BINGE_GROUP_PREFIX = "local-plugin-"
private const val PLUGIN_BINGE_GROUP_SEPARATOR = "|"

/**
 * Binge group of one plugin stream: `local-plugin-<scraperId>|<quality>|<language>`.
 * Autoplay continues a series with the next episode's stream that has the same group, so a
 * plugin's own quality and language choice carries over while the episode-specific title does not.
 */
fun pluginBingeGroup(scraperId: String, quality: String? = null, language: String? = null): String {
    val parts = listOfNotNull(
        quality?.let(::canonicalPluginToken)?.takeIf { it.isNotEmpty() },
        language?.let(::canonicalPluginToken)?.takeIf { it.isNotEmpty() }
    )
    return if (parts.isEmpty()) {
        "$PLUGIN_BINGE_GROUP_PREFIX$scraperId"
    } else {
        "$PLUGIN_BINGE_GROUP_PREFIX$scraperId$PLUGIN_BINGE_GROUP_SEPARATOR${parts.joinToString(PLUGIN_BINGE_GROUP_SEPARATOR)}"
    }
}

/** "1080p" and "1080" are one resolution; everything else is lower-cased and stripped to letters and digits. */
private fun canonicalPluginToken(raw: String): String {
    val token = raw.lowercase().filter { it.isLetterOrDigit() }
    var digits = 0
    while (digits < token.length && token[digits].isDigit()) digits++
    val numberOnly = digits == token.length
    val numberAndP = digits > 0 && digits == token.length - 1 && token[digits] == 'p'
    return if (digits > 0 && (numberOnly || numberAndP)) token.substring(0, digits) else token
}
