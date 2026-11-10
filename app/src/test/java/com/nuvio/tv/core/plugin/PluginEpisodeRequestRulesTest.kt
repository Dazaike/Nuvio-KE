package com.nuvio.tv.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginEpisodeRequestRulesTest {
    @Test
    fun `tmdb-style ids keep the season and episode`() {
        assertEquals(2 to 10, resolvePluginSeasonEpisode("tt2098220:2:10", 2, 10))
    }

    @Test
    fun `anime tracker ids use the absolute episode and no season`() {
        assertEquals(null to 68, resolvePluginSeasonEpisode("kitsu:6448:68", 2, 10))
        assertEquals(null to 12, resolvePluginSeasonEpisode("mal:63375:12", 1, 12))
        assertEquals(68, absoluteAnimeEpisodeNumber("anilist:12345:68"))
        assertNull(absoluteAnimeEpisodeNumber("mal::12"))
        assertNull(absoluteAnimeEpisodeNumber("tt1:2:3"))
    }

    @Test
    fun `a season-bearing request matches only the exact pair`() {
        val entries = listOf<Pair<Int?, Int?>>(1 to 10, 2 to 10, null to 68)
        assertEquals(1, matchPluginEpisodeIndex(entries, season = 2, episode = 10))
        assertNull(matchPluginEpisodeIndex(entries, season = 3, episode = 10))
        assertEquals(2, matchPluginEpisodeIndex(entries, season = null, episode = 68))
    }

    @Test
    fun `series declared by a scraper reach it as series, others as tv`() {
        assertEquals("series", jsPluginMediaType("tv", listOf("movie", "series"), 1, 1))
        assertEquals("tv", jsPluginMediaType("tv", listOf("movie", "tv"), 1, 1))
        assertEquals("tv", jsPluginMediaType("tv", listOf("series"), null, null))
        assertEquals("movie", jsPluginMediaType("movie", listOf("movie", "series"), null, null))
    }
}
