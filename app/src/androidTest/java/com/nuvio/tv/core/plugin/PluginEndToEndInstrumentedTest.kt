package com.nuvio.tv.core.plugin

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ScraperInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private class InMemoryPluginStore : PluginStore {
    override val repositories = MutableStateFlow<List<PluginRepository>>(emptyList())
    override val scrapers = MutableStateFlow<List<ScraperInfo>>(emptyList())
    override val pluginsEnabled = MutableStateFlow(true)
    override val groupStreamsByRepository = MutableStateFlow(false)
    override val thirdPartyCodeAcknowledged = MutableStateFlow(false)
    // Scripts download in parallel, as they are written to separate files on a device.
    val code: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    override fun isReadOnlyProfile() = false
    override suspend fun saveRepositories(repos: List<PluginRepository>) { repositories.value = repos }
    override suspend fun addRepository(repo: PluginRepository) { repositories.value = repositories.value + repo }
    override suspend fun updateRepository(repo: PluginRepository) {
        repositories.value = repositories.value.map { if (it.id == repo.id) repo else it }
    }
    override suspend fun removeRepository(repoId: String) { repositories.value = repositories.value.filterNot { it.id == repoId } }
    override suspend fun saveScrapers(scrapers: List<ScraperInfo>) { this.scrapers.value = scrapers }
    override suspend fun setPluginsEnabled(enabled: Boolean) { pluginsEnabled.value = enabled }
    override suspend fun setGroupStreamsByRepository(enabled: Boolean) { groupStreamsByRepository.value = enabled }
    override suspend fun setThirdPartyCodeAcknowledged(acknowledged: Boolean) { thirdPartyCodeAcknowledged.value = acknowledged }
    override suspend fun getScraperSettings(scraperId: String): Map<String, String> = emptyMap()
    override suspend fun setScraperSettings(scraperId: String, settings: Map<String, String>) {}
    override suspend fun saveScraperCode(scraperId: String, code: String) { this.code[scraperId] = code }
    override suspend fun getScraperCode(scraperId: String): String? = code[scraperId]
    override suspend fun deleteScraperCode(scraperId: String) { code.remove(scraperId) }
    override suspend fun resetAll() { repositories.value = emptyList(); scrapers.value = emptyList(); code.clear() }
}

/**
 * A whole plugin round trip on a device: a small local repository over HTTP, the real manager, the
 * real QuickJS runtime. The scrapers are written for this test and need no outside service.
 */
@RunWith(AndroidJUnit4::class)
class PluginEndToEndInstrumentedTest {
    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryPluginStore
    private lateinit var manager: PluginManager

    private val manifest = """
        {"name":"Local test repo","version":"1.0.0","scrapers":[
          {"id":"echo","name":"Echo","version":"1.0.0","filename":"echo.js","supportedTypes":["movie","tv"]},
          {"id":"broken","name":"Broken","version":"1.0.0","filename":"broken.js","supportedTypes":["movie","tv"]},
          {"id":"moviesonly","name":"Movies only","version":"1.0.0","filename":"movies.js","supportedTypes":["movie"]}
        ]}
    """.trimIndent()

    private val echo = """
        module.exports.getStreams = async function(tmdbId, mediaType, season, episode) {
          const label = mediaType === "movie" ? "Movie " + tmdbId : "S" + season + "E" + episode + " of " + tmdbId;
          return [
            { name: "Echo 1080p", title: label, url: "https://cdn.example/echo/" + tmdbId + "/" + (season || 0) + "/" + (episode || 0) + ".m3u8", quality: "1080p", size: "2 GB" },
            { name: "Echo 720p", title: label, url: "https://cdn.example/echo-sd/" + tmdbId + ".m3u8", quality: "720p" }
          ];
        };
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/manifest.json" -> MockResponse().setBody(manifest)
                    "/echo.js" -> MockResponse().setBody(echo)
                    "/broken.js" -> MockResponse().setBody("module.exports.getStreams = async ( => {")
                    "/movies.js" -> MockResponse().setBody("module.exports.getStreams = async () => [{title:'m', url:'https://cdn.example/m'}];")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        store = InMemoryPluginStore()
        manager = PluginManager(store, PluginRuntime(), OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun install_enable_test_and_search_with_real_scripts() = runBlocking {
        val installed = manager.addRepository(server.url("/").toString()).getOrThrow()
        assertEquals(3, installed.installed)
        assertTrue("nothing runs before the user enables it", store.scrapers.value.none { it.enabled })
        assertTrue(manager.executeScrapersStreaming("603", "movie").toList().isEmpty())

        val echoId = "${installed.repository.id}:echo"
        val brokenId = "${installed.repository.id}:broken"
        manager.setScraperEnabled(echoId, true)
        manager.setScraperEnabled(brokenId, true)

        // Test: a movie sample through the real engine.
        val report = manager.testScraper(echoId)
        assertNull(report.error)
        assertEquals(2, report.results.size)
        assertEquals("Movie 603", report.results.first().title)
        assertTrue(report.diagnostics.steps.any { it.startsWith("Result: 2 streams") })

        // A broken script reports its error and does not disturb the working one.
        val brokenReport = manager.testScraper(brokenId)
        assertTrue(brokenReport.error != null)
        assertTrue(brokenReport.results.isEmpty())

        // Search for a movie: the working scraper answers, the broken one is skipped.
        val movie = manager.executeScrapersStreaming("603", "movie").toList()
        assertEquals(listOf("Echo"), movie.map { it.first.name })
        assertEquals("https://cdn.example/echo/603/0/0.m3u8", movie.single().second.first().url)
        assertEquals("Echo", movie.single().second.first().provider)

        // Search for an episode: season and episode reach the script.
        val episode = manager.executeScrapersStreaming("1396", "tv", season = 2, episode = 5).toList()
        assertEquals("S2E5 of 1396", episode.single().second.first().title)
        assertEquals("https://cdn.example/echo/1396/2/5.m3u8", episode.single().second.first().url)

        // Disabling stops it; so does the global switch.
        manager.setScraperEnabled(echoId, false)
        assertTrue(manager.executeScrapersStreaming("603", "movie").toList().isEmpty())
        manager.setScraperEnabled(echoId, true)
        manager.setPluginsEnabled(false)
        assertTrue(manager.executeScrapersStreaming("603", "movie").toList().isEmpty())
        manager.setPluginsEnabled(true)

        // The movies-only scraper is not asked for an episode.
        manager.setScraperEnabled("${installed.repository.id}:moviesonly", true)
        val names = manager.executeScrapersStreaming("1396", "tv", season = 1, episode = 1).toList().map { it.first.name }
        assertTrue("Movies only" !in names)

        // Removing the repository removes everything it installed.
        manager.removeRepository(installed.repository.id)
        assertTrue(store.scrapers.value.isEmpty())
        assertTrue(store.code.isEmpty())
        assertTrue(manager.executeScrapersStreaming("603", "movie").toList().isEmpty())
    }
}
