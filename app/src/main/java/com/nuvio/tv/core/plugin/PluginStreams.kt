package com.nuvio.tv.core.plugin

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.pluginBingeGroup

/**
 * Name the stream list shows a scraper's streams under: the scraper's own name, or its repository's
 * when "group streams by repository" is on.
 */
internal fun ScraperInfo.pluginGroupName(
    groupByRepository: Boolean,
    repositoriesById: Map<String, PluginRepository>
): String {
    if (!groupByRepository) return name
    return repositoriesById[repositoryId]?.name?.takeIf { it.isNotBlank() } ?: name
}

/**
 * One scraper's results as a source group of the stream list, ready to merge with the addon groups.
 * [unknownQualityLabel] stands in when a scraper reports no quality.
 */
internal fun pluginAddonStreams(
    scraper: ScraperInfo,
    results: List<LocalScraperResult>,
    groupName: String,
    showScraperLogo: Boolean,
    unknownQualityLabel: String
): AddonStreams {
    val logo = scraper.logo?.takeIf { showScraperLogo && it.isNotBlank() }
    return AddonStreams(
        addonName = groupName,
        addonLogo = logo,
        streams = results.map { it.toPluginStream(scraper, groupName, logo, unknownQualityLabel) }
    )
}

/** A scraper result as a [Stream] the existing selection list, ranking and player already understand. */
internal fun LocalScraperResult.toPluginStream(
    scraper: ScraperInfo,
    addonName: String,
    addonLogo: String?,
    unknownQualityLabel: String
): Stream {
    val baseTitle = title.takeIf { it.isNotBlank() }
    val baseName = name?.takeIf { it.isNotBlank() }
    val qualityText = quality?.takeIf { it.isNotBlank() }
    val qualityLabel = qualityText ?: unknownQualityLabel
    val head = baseName ?: baseTitle ?: scraper.name
    val displayName = if (head.contains(qualityLabel, ignoreCase = true)) head else "$head - $qualityLabel"
    val displayTitle = baseTitle ?: baseName ?: scraper.name
    val isTorrent = !infoHash.isNullOrBlank() || url.startsWith("magnet:", ignoreCase = true)
    val description = listOfNotNull(
        size?.takeIf { it.isNotBlank() },
        language?.takeIf { it.isNotBlank() },
        seeders?.takeIf { isTorrent && it >= 0 }?.let { "\uD83D\uDC64 $it" }
    ).joinToString(" \u2022 ").takeIf { it.isNotEmpty() }

    return Stream(
        name = displayName,
        title = displayTitle,
        description = description,
        url = url,
        ytId = null,
        infoHash = infoHash?.takeIf { it.isNotBlank() },
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = pluginBingeGroup(scraperId = scraper.id, quality = qualityText, language = language),
            countryWhitelist = null,
            proxyHeaders = headers?.let { ProxyHeaders(request = it, response = null) }
        ),
        addonName = addonName,
        addonLogo = addonLogo,
        quality = qualityText,
        qualityValue = parsePluginQualityValue(qualityText),
        subtitles = subtitles
    )
}

/** Vertical resolution a quality label names ("4K", "2160p", "1080p"), or -1. */
internal fun parsePluginQualityValue(quality: String?): Int {
    if (quality == null) return -1
    val lower = quality.lowercase()
    return when {
        lower.contains("4k") || lower.contains("2160") -> 2160
        lower.contains("1440") -> 1440
        lower.contains("1080") -> 1080
        lower.contains("720") -> 720
        lower.contains("480") -> 480
        lower.contains("360") -> 360
        else -> -1
    }
}
