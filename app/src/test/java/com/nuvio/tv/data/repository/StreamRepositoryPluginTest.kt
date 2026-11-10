package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.plugin.PluginSnapshot
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Plugin scrapers join the addon fan-out: their streams arrive as source groups beside the addons',
 * and nothing a plugin does (no TMDB id, a throwing scraper, no plugins at all) costs the addon streams.
 */
class StreamRepositoryPluginTest {

    private val scraper = ScraperInfo(
        id = "repo1:s1", name = "Provider", description = "", version = "1.0", filename = "s.js",
        supportedTypes = listOf("movie", "tv"), enabled = true, manifestEnabled = true, logo = null,
        contentLanguage = emptyList(), repositoryId = "repo1", formats = null
    )
    private val repository = PluginRepository("repo1", "My Repo", "https://r.example/manifest.json")
    private val withPlugin = PluginSnapshot(listOf(scraper), groupByRepository = false, repositories = listOf(repository))
    private val noPlugins = PluginSnapshot(emptyList(), groupByRepository = false, repositories = emptyList())

    private class Harness(
        val repository: StreamRepositoryImpl,
        val api: AddonApi,
        val plugins: PluginManager,
        val tmdb: TmdbService,
        val snapshot: MutableStateFlow<PluginSnapshot>
    )

    private fun harness(
        addons: List<Addon>,
        snapshot: PluginSnapshot,
        tmdbId: String? = "603",
        pluginFlow: (tmdbId: String, mediaType: String, season: Int?, episode: Int?) -> Flow<Pair<ScraperInfo, List<LocalScraperResult>>> =
            { _, _, _, _ -> flowOf(scraper to listOf(LocalScraperResult(title = "Plugin movie", url = "https://plugin.example/v.m3u8", quality = "1080p"))) }
    ): Harness {
        val api = mockk<AddonApi>()
        coEvery { api.getStreams(any(), any()) } returns Response.success(
            StreamResponseDto(streams = listOf(StreamDto(name = "Addon stream", url = "https://addon.example/v.m3u8")))
        )
        val addonRepository = mockk<AddonRepository>()
        every { addonRepository.getInstalledAddons() } returns flowOf(addons)
        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val current = MutableStateFlow(snapshot)
        val plugins = mockk<PluginManager>()
        coEvery { plugins.snapshot() } answers { current.value }
        every { plugins.executeScrapersStreaming(any(), any(), any(), any()) } answers {
            pluginFlow(firstArg(), secondArg(), thirdArg(), arg(3))
        }
        val tmdb = mockk<TmdbService>()
        coEvery { tmdb.ensureTmdbId(any(), any()) } returns tmdbId
        return Harness(
            repository = StreamRepositoryImpl(
                context = mockk<Context>(relaxed = true),
                api = api,
                addonRepository = addonRepository,
                profileManager = profileManager,
                healthStore = mockk(relaxed = true),
                pluginManager = plugins,
                tmdbService = tmdb
            ),
            api = api,
            plugins = plugins,
            tmdb = tmdb,
            snapshot = current
        )
    }

    private fun streamAddon(name: String = "Addon A", types: List<String> = listOf("movie")) = Addon(
        id = name, name = name, version = "1.0.0", description = null, logo = null,
        baseUrl = "https://${name.replace(' ', '-').lowercase()}.example",
        catalogs = emptyList(), types = emptyList(),
        resources = listOf(AddonResource(name = "stream", types = types, idPrefixes = listOf("tt")))
    )

    private suspend fun Harness.search(
        type: String = "movie",
        videoId: String = "tt1341338",
        season: Int? = null,
        episode: Int? = null
    ): List<NetworkResult<List<AddonStreams>>> = withTimeout(10_000) {
        repository.getStreamsFromAllAddons(type = type, videoId = videoId, season = season, episode = episode).toList()
    }

    private fun List<NetworkResult<List<AddonStreams>>>.finalGroups(): List<AddonStreams> =
        (last() as NetworkResult.Success).data

    @Test
    fun `plugin streams arrive as a source group beside the addon streams`() = runBlocking {
        val h = harness(listOf(streamAddon()), withPlugin)

        val groups = h.search().finalGroups()

        assertEquals(setOf("Addon A", "Provider"), groups.map { it.addonName }.toSet())
        val plugin = groups.first { it.addonName == "Provider" }.streams.single()
        assertEquals("https://plugin.example/v.m3u8", plugin.url)
        assertEquals("Provider", plugin.addonName)
        assertEquals(1080, plugin.qualityValue)
        assertEquals("https://addon.example/v.m3u8", groups.first { it.addonName == "Addon A" }.streams.single().url)
    }

    @Test
    fun `a movie request hands the scrapers the TMDB id with no season or episode`() = runBlocking {
        val h = harness(listOf(streamAddon()), withPlugin)

        h.search()

        verify(exactly = 1) { h.plugins.executeScrapersStreaming("603", "movie", null, null) }
        coVerify { h.tmdb.ensureTmdbId("tt1341338", "movie") }
    }

    @Test
    fun `an episode request hands the scrapers the series as tv with its season and episode`() = runBlocking {
        val h = harness(listOf(streamAddon(types = listOf("series"))), withPlugin, tmdbId = "1396")

        h.search(type = "series", videoId = "tt0903747:2:5", season = 2, episode = 5)

        verify(exactly = 1) { h.plugins.executeScrapersStreaming("1396", "tv", 2, 5) }
    }

    @Test
    fun `an anime tracker id sends its absolute episode number and no season`() = runBlocking {
        val h = harness(emptyList(), withPlugin, tmdbId = "99")

        h.search(type = "series", videoId = "kitsu:6448:68", season = 1, episode = 68)

        verify(exactly = 1) { h.plugins.executeScrapersStreaming("99", "tv", null, 68) }
    }

    @Test
    fun `with no plugins installed the scrapers are never asked and addons answer alone`() = runBlocking {
        val h = harness(listOf(streamAddon()), noPlugins)

        val groups = h.search().finalGroups()

        assertEquals(listOf("Addon A"), groups.map { it.addonName })
        verify(exactly = 0) { h.plugins.executeScrapersStreaming(any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.tmdb.ensureTmdbId(any(), any()) }
    }

    @Test
    fun `a title with no TMDB id skips the plugins and keeps the addon streams`() = runBlocking {
        val h = harness(listOf(streamAddon()), withPlugin, tmdbId = null)

        val groups = h.search().finalGroups()

        assertEquals(listOf("Addon A"), groups.map { it.addonName })
        verify(exactly = 0) { h.plugins.executeScrapersStreaming(any(), any(), any(), any()) }
    }

    @Test
    fun `a plugin run that throws never costs the addon streams`() = runBlocking {
        val h = harness(listOf(streamAddon()), withPlugin, pluginFlow = { _, _, _, _ -> flow { throw IllegalStateException("scraper bug") } })

        val results = h.search()

        assertEquals(listOf("Addon A"), results.finalGroups().map { it.addonName })
    }

    @Test
    fun `plugins alone can answer a search when no addon supports the type`() = runBlocking {
        val h = harness(emptyList(), withPlugin)

        val groups = h.search().finalGroups()

        assertEquals(listOf("Provider"), groups.map { it.addonName })
    }

    @Test
    fun `plugins that find nothing end the search with the not-found answer instead of hanging`() = runBlocking {
        val h = harness(emptyList(), withPlugin, pluginFlow = { _, _, _, _ -> flowOf() })

        val results = h.search()

        assertTrue(results.last() is NetworkResult.Error)
    }

    @Test
    fun `grouping by repository shows one group named after the repository`() = runBlocking {
        val grouped = withPlugin.copy(groupByRepository = true)
        val h = harness(emptyList(), grouped)

        val groups = h.search().finalGroups()

        assertEquals(listOf("My Repo"), groups.map { it.addonName })
        assertEquals("My Repo", groups.single().streams.single().addonName)
    }

    @Test
    fun `enabling a plugin after a search does not reuse the cached search without it`() = runBlocking {
        val h = harness(listOf(streamAddon()), noPlugins)
        assertEquals(listOf("Addon A"), h.search().finalGroups().map { it.addonName })

        h.snapshot.value = withPlugin
        val groups = h.search().finalGroups()

        assertEquals(setOf("Addon A", "Provider"), groups.map { it.addonName }.toSet())
    }
}
