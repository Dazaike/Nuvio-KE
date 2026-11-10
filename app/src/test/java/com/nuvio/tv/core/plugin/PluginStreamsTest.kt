package com.nuvio.tv.core.plugin

import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.pluginBingeGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginStreamsTest {
    private fun scraper(id: String = "repo1:s1", name: String = "Provider", logo: String? = "https://l.example/p.png", type: List<String> = listOf("movie")) =
        ScraperInfo(id, name, "", "1.0", "s.js", type, true, true, logo, emptyList(), "repo1", null)

    @Test
    fun `a result becomes a stream the list, ranking and player understand`() {
        val stream = LocalScraperResult(
            title = "Movie (2020)", name = "Server A", url = "https://cdn.example/v.m3u8", quality = "1080p",
            size = "2.1 GB", language = "English", headers = mapOf("Referer" to "https://site.example/")
        ).toPluginStream(scraper(), addonName = "Provider", addonLogo = "https://l.example/p.png", unknownQualityLabel = "Unknown")

        assertEquals("Server A - 1080p", stream.name)
        assertEquals("Movie (2020)", stream.title)
        assertEquals("2.1 GB \u2022 English", stream.description)
        assertEquals("https://cdn.example/v.m3u8", stream.url)
        assertEquals("1080p", stream.quality)
        assertEquals(1080, stream.qualityValue)
        assertEquals("Provider", stream.addonName)
        assertEquals(mapOf("Referer" to "https://site.example/"), stream.behaviorHints?.proxyHeaders?.request)
        assertEquals("local-plugin-repo1:s1|1080|english", stream.behaviorHints?.bingeGroup)
    }

    @Test
    fun `a missing quality shows the unknown label and a torrent shows seeders`() {
        val stream = LocalScraperResult(
            title = "T", url = "magnet:?xt=urn:btih:abc", infoHash = "abc", seeders = 42
        ).toPluginStream(scraper(), "Provider", null, "Unknown")

        assertEquals("T - Unknown", stream.name)
        assertEquals(-1, stream.qualityValue)
        assertNull(stream.quality)
        assertEquals("abc", stream.infoHash)
        assertTrue(stream.description!!.contains("42"))
    }

    @Test
    fun `a name that already carries the quality is not repeated`() {
        val stream = LocalScraperResult(title = "T", name = "Server 4K", url = "https://a.example/v", quality = "4K")
            .toPluginStream(scraper(), "P", null, "Unknown")
        assertEquals("Server 4K", stream.name)
        assertEquals(2160, stream.qualityValue)
    }

    @Test
    fun `quality labels map to a resolution`() {
        assertEquals(2160, parsePluginQualityValue("4K"))
        assertEquals(2160, parsePluginQualityValue("2160p"))
        assertEquals(1440, parsePluginQualityValue("1440p"))
        assertEquals(1080, parsePluginQualityValue("Full HD 1080p"))
        assertEquals(720, parsePluginQualityValue("720p"))
        assertEquals(480, parsePluginQualityValue("480p"))
        assertEquals(-1, parsePluginQualityValue("CAM"))
        assertEquals(-1, parsePluginQualityValue(null))
    }

    @Test
    fun `the binge group carries a scraper's quality across episodes but not its titles`() {
        assertEquals("local-plugin-r:s|1080", pluginBingeGroup("r:s", quality = "1080p"))
        assertEquals(pluginBingeGroup("r:s", quality = "1080p"), pluginBingeGroup("r:s", quality = "1080"))
        assertEquals("local-plugin-r:s", pluginBingeGroup("r:s"))
    }

    @Test
    fun `groups are named by scraper, or by repository when grouping is on`() {
        val repos = mapOf("repo1" to PluginRepository("repo1", "My Repo", "https://r.example/manifest.json"))
        assertEquals("Provider", scraper().pluginGroupName(false, repos))
        assertEquals("My Repo", scraper().pluginGroupName(true, repos))
        assertEquals("Provider", scraper().pluginGroupName(true, emptyMap()))
    }

    @Test
    fun `the group carries every result and the logo only when it names the scraper`() {
        val results = listOf(
            LocalScraperResult(title = "A", url = "https://a.example/1"),
            LocalScraperResult(title = "B", url = "https://a.example/2")
        )
        val own = pluginAddonStreams(scraper(), results, "Provider", showScraperLogo = true, unknownQualityLabel = "Unknown")
        assertEquals("Provider", own.addonName)
        assertEquals("https://l.example/p.png", own.addonLogo)
        assertEquals(2, own.streams.size)
        assertTrue(own.streams.all { it.addonName == "Provider" && it.addonLogo == "https://l.example/p.png" })

        val grouped = pluginAddonStreams(scraper(), results, "My Repo", showScraperLogo = false, unknownQualityLabel = "Unknown")
        assertNull(grouped.addonLogo)
        assertEquals("My Repo", grouped.streams.first().addonName)
    }
}
