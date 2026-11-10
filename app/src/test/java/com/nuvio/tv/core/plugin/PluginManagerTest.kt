package com.nuvio.tv.core.plugin

import com.nuvio.tv.data.local.pluginCodeFileName
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The manager against a real HTTP repository (MockWebServer on loopback, which the repository rules
 * allow over plain http) with an in-memory store and a scripted runner.
 */
class PluginManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var store: FakePluginStore
    private val files = mutableMapOf<String, String>()
    private val status = mutableMapOf<String, Int>()

    @Before
    fun setUp() {
        store = FakePluginStore()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    status[path]?.let { return MockResponse().setResponseCode(it) }
                    val body = files[path] ?: return MockResponse().setResponseCode(404)
                    return MockResponse().setBody(body)
                }
            }
            start()
        }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun manager(runner: PluginScriptRunner = FakeRunner { emptyList() }) =
        PluginManager(store, runner, OkHttpClient())

    private fun base() = server.url("/repo").toString()

    private fun manifest(vararg scrapers: String, name: String = "Test Repo", version: String = "1.2.0") =
        """{"name":"$name","version":"$version","description":"d","scrapers":[${scrapers.joinToString(",")}]}"""

    private fun scraper(
        id: String,
        file: String = "$id.js",
        types: String = """["movie","tv"]""",
        enabled: Boolean = true,
        version: String = "1.0.0",
        settings: String = "[]"
    ) = """{"id":"$id","name":"Scraper $id","version":"$version","filename":"$file","supportedTypes":$types,"enabled":$enabled,"formats":["mp4"],"settings":$settings}"""

    private fun publish(vararg scrapers: String, version: String = "1.2.0") {
        files["/repo/manifest.json"] = manifest(*scrapers, version = version)
    }

    @Test
    fun `adding a repository downloads every script and leaves the scrapers switched off`() = runBlocking {
        publish(scraper("a"), scraper("b", types = """["movie"]"""))
        files["/repo/a.js"] = "module.exports.getStreams = async () => [];"
        files["/repo/b.js"] = "// b"

        val result = manager().addRepository(base()).getOrThrow()

        assertEquals(2, result.installed)
        assertEquals(0, result.failed)
        assertEquals("Test Repo", result.repository.name)
        assertEquals("1.2.0", result.repository.version)
        assertTrue(result.repository.url.endsWith("/repo/manifest.json"))
        val scrapers = store.scraperList()
        assertEquals(2, scrapers.size)
        assertTrue("third-party code needs an explicit enable", scrapers.none { it.enabled })
        assertEquals("module.exports.getStreams = async () => [];", store.code["${result.repository.id}:a"])
        assertEquals(listOf("movie"), scrapers.first { it.id.endsWith(":b") }.supportedTypes)
    }

    @Test
    fun `a url that already names the manifest is accepted as is, and a duplicate is refused`() = runBlocking {
        publish(scraper("a"))
        files["/repo/a.js"] = "// a"
        val manager = manager()

        assertTrue(manager.addRepository(base() + "/manifest.json").isSuccess)
        val duplicate = manager.addRepository(base() + "/")

        assertTrue(duplicate.isFailure)
        assertEquals("This repository is already installed", duplicate.exceptionOrNull()?.message)
        assertEquals(1, store.repositories.value.size)
    }

    @Test
    fun `what the user types becomes the manifest url`() {
        val manager = manager()
        fun canon(input: String) = manager.canonicalManifestUrl(input).toString()

        assertEquals("https://r.example/repo/manifest.json", canon("https://r.example/repo"))
        assertEquals("https://r.example/repo/manifest.json", canon("r.example/repo/"))
        assertEquals("https://r.example/manifest.json", canon("https://r.example"))
        assertEquals("https://r.example/repo/manifest.json?token=abc", canon("https://r.example/repo/manifest.json?token=abc#frag"))
        assertEquals("https://r.example/manifest.json?k=v", canon("https://r.example/?k=v"))
        assertEquals("https://r.example/a/manifest.json", canon("  https://r.example/a/Manifest.json  ").lowercase().replace("manifest.json", "manifest.json"))
        assertEquals("https://raw.githubusercontent.com/o/r/main/manifest.json", canon("https://raw.githubusercontent.com/o/r/main/"))
    }

    @Test
    fun `declared settings are kept in the manifest's own words, bounded and well-formed`() = runBlocking {
        val long = "x".repeat(500)
        publish(
            scraper(
                "a",
                settings = """[
                    {"key":"vg_cookie","label":"Cookie","description":"$long","placeholder":"paste"},
                    {"key":"bad key!","label":"Dropped"},
                    {"key":"vg_cookie","label":"Duplicate"},
                    {"key":"nolabel","label":"  "},
                    {"key":"../etc","label":"Dropped too"}
                ]"""
            )
        )
        files["/repo/a.js"] = "// a"

        manager().addRepository(base()).getOrThrow()

        val fields = store.scraperList().single().settings
        assertEquals(listOf("vg_cookie"), fields.map { it.key })
        assertEquals("Cookie", fields.single().label)
        assertEquals(200, fields.single().description!!.length)
        assertEquals("paste", fields.single().placeholder)
    }

    @Test
    fun `a manifest that declares more settings than allowed keeps the first few`() = runBlocking {
        val many = (1..25).joinToString(",", "[", "]") { """{"key":"k$it","label":"L$it"}""" }
        publish(scraper("a", settings = many))
        files["/repo/a.js"] = "// a"

        manager().addRepository(base()).getOrThrow()

        assertEquals(10, store.scraperList().single().settings.size)
    }

    @Test
    fun `saved settings keep only declared keys, trimmed, and blank removes one`() = runBlocking {
        publish(scraper("a", settings = """[{"key":"vg","label":"Cookie"},{"key":"ua","label":"UA"}]"""))
        files["/repo/a.js"] = "// a"
        val manager = manager()
        val repoId = manager.addRepository(base()).getOrThrow().repository.id
        val id = "$repoId:a"

        manager.saveScraperSettings(id, mapOf("vg" to "  secret  ", "ua" to "UA/1", "undeclared" to "x"))
        assertEquals(mapOf("vg" to "secret", "ua" to "UA/1"), manager.getScraperSettings(id))

        manager.saveScraperSettings(id, mapOf("vg" to "   ", "ua" to "UA/2"))
        assertEquals(mapOf("ua" to "UA/2"), manager.getScraperSettings(id))

        manager.saveScraperSettings(id, mapOf("ua" to "line1\nline2"))
        assertTrue("a value that could break a header is refused", manager.getScraperSettings(id).isEmpty())

        manager.saveScraperSettings(id, mapOf("ua" to "y".repeat(MAX_SETTING_VALUE_CHARS + 50)))
        assertEquals(MAX_SETTING_VALUE_CHARS, manager.getScraperSettings(id).getValue("ua").length)
    }

    @Test
    fun `a script only receives settings its manifest declares`() = runBlocking {
        val runner = FakeRunner { listOf(result("https://cdn.example/x.m3u8")) }
        val manager = manager(runner)
        val repoId = installAndEnable(manager, scraper("a", settings = """[{"key":"vg","label":"Cookie"}]"""))
        // An older version of the scraper declared "legacy"; its value is still on disk.
        store.settings["$repoId:a"] = mapOf("vg" to "now", "legacy" to "old")

        manager.executeScrapersStreaming("603", "movie").toList()

        assertEquals(mapOf<String, Any>("vg" to "now"), runner.calls.single().settings)
    }

    @Test
    fun `a response that is not a manifest installs nothing`() = runBlocking {
        files["/repo/manifest.json"] = "<html>not json</html>"

        val result = manager().addRepository(base())

        assertTrue(result.isFailure)
        assertEquals("This is not a Nuvio plugin manifest", result.exceptionOrNull()?.message)
        assertTrue(store.repositories.value.isEmpty())
        assertTrue(store.scrapers.value.isEmpty())
    }

    @Test
    fun `an unreachable manifest reports the http status`() = runBlocking {
        status["/repo/manifest.json"] = 503

        val result = manager().addRepository(base())

        assertEquals("The server answered HTTP 503", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a script that cannot be downloaded or is too large is counted, the rest install`() = runBlocking {
        publish(scraper("ok"), scraper("missing"), scraper("huge"))
        files["/repo/ok.js"] = "// ok"
        files["/repo/huge.js"] = "x".repeat((MAX_SCRIPT_BYTES + 1).toInt())

        val result = manager().addRepository(base()).getOrThrow()

        assertEquals(1, result.installed)
        assertEquals(2, result.failed)
        assertEquals(listOf("${result.repository.id}:ok"), store.scraperList().map { it.id })
        assertEquals(1, result.repository.scraperCount)
    }

    @Test
    fun `a repository none of whose scripts download is not installed`() = runBlocking {
        publish(scraper("gone"))

        val result = manager().addRepository(base())

        assertTrue(result.isFailure)
        assertTrue(store.repositories.value.isEmpty())
    }

    @Test
    fun `entries without id, name or file are dropped and a hostile id never becomes a path`() = runBlocking {
        files["/repo/manifest.json"] = manifest(
            scraper("../../escape", file = "evil.js"),
            """{"id":"","name":"x","version":"1","filename":"x.js"}""",
            """{"id":"noname","name":"","version":"1","filename":"x.js"}"""
        )
        files["/repo/evil.js"] = "// evil"

        val result = manager().addRepository(base()).getOrThrow()

        assertEquals(1, result.installed)
        val id = store.scraperList().single().id
        assertTrue(id.endsWith(":../../escape"))
        // The store keys code by id; on disk the name is a digest (see PluginDataStoreTest).
        assertFalse(pluginCodeFileName(id).contains(".."))
        assertFalse(pluginCodeFileName(id).contains("/"))
    }

    @Test
    fun `a manifest listing too many scrapers is refused`() = runBlocking {
        files["/repo/manifest.json"] = manifest(*Array(MAX_SCRAPERS_PER_REPOSITORY + 1) { scraper("s$it") })

        val result = manager().addRepository(base())

        assertTrue(result.isFailure)
        assertTrue(store.repositories.value.isEmpty())
    }

    @Test
    fun `plain http is refused for a public host and a non-http scheme is refused`() = runBlocking {
        val manager = manager()

        assertEquals(
            "Repositories must use https (http only on your own network)",
            manager.addRepository("http://plugins.example.com/m").exceptionOrNull()?.message
        )
        assertTrue(manager.addRepository("ftp://plugins.example.com/m").isFailure)
        assertEquals("Enter a repository URL", manager.addRepository("  ").exceptionOrNull()?.message)
    }

    @Test
    fun `refresh keeps the user's switches, adds new scrapers off and drops removed ones`() = runBlocking {
        publish(scraper("keep"), scraper("gone"))
        files["/repo/keep.js"] = "// v1"
        files["/repo/gone.js"] = "// gone"
        files["/repo/new.js"] = "// new"
        val manager = manager()
        val repo = manager.addRepository(base()).getOrThrow().repository
        manager.setScraperEnabled("${repo.id}:keep", true)
        store.settings["${repo.id}:gone"] = mapOf("k" to "v")

        publish(scraper("keep", version = "2.0.0"), scraper("new"), version = "1.3.0")
        files["/repo/keep.js"] = "// v2"
        val refreshed = manager.refreshRepository(repo.id).getOrThrow()

        assertEquals(2, refreshed.installed)
        assertEquals(1, refreshed.removed)
        assertEquals("1.3.0", refreshed.repository.version)
        val byId = store.scraperList().associateBy { it.id }
        assertEquals(setOf("${repo.id}:keep", "${repo.id}:new"), byId.keys)
        assertTrue(byId.getValue("${repo.id}:keep").enabled)
        assertEquals("2.0.0", byId.getValue("${repo.id}:keep").version)
        assertFalse(byId.getValue("${repo.id}:new").enabled)
        assertEquals("// v2", store.code["${repo.id}:keep"])
        assertNull(store.code["${repo.id}:gone"])
        assertTrue(store.settings.isEmpty())
    }

    @Test
    fun `a scraper whose refresh download fails keeps its previous copy`() = runBlocking {
        publish(scraper("a"))
        files["/repo/a.js"] = "// v1"
        val manager = manager()
        val repo = manager.addRepository(base()).getOrThrow().repository
        publish(scraper("a"), scraper("b"))
        files.remove("/repo/a.js")
        files["/repo/b.js"] = "// b"

        val refreshed = manager.refreshRepository(repo.id).getOrThrow()

        assertEquals(1, refreshed.installed)
        assertEquals(1, refreshed.failed)
        assertEquals("// v1", store.code["${repo.id}:a"])
        assertEquals(2, store.scraperList().size)
    }

    @Test
    fun `removing a repository deletes its scrapers, scripts and settings only`() = runBlocking {
        publish(scraper("a"))
        files["/repo/a.js"] = "// a"
        val manager = manager()
        val first = manager.addRepository(base()).getOrThrow().repository
        files["/other/manifest.json"] = manifest(scraper("z", file = "z.js"), name = "Other")
        files["/other/z.js"] = "// z"
        val second = manager.addRepository(server.url("/other").toString()).getOrThrow().repository
        store.settings["${first.id}:a"] = mapOf("k" to "v")

        manager.removeRepository(first.id)

        assertEquals(listOf(second.id), store.repositories.value.map { it.id })
        assertEquals(listOf("${second.id}:z"), store.scraperList().map { it.id })
        assertEquals(setOf("${second.id}:z"), store.code.keys)
        assertTrue(store.settings.isEmpty())
    }

    @Test
    fun `a scraper its manifest disables cannot be switched on`() = runBlocking {
        publish(scraper("off", enabled = false), scraper("on"))
        files["/repo/off.js"] = "// off"
        files["/repo/on.js"] = "// on"
        val manager = manager()
        val repo = manager.addRepository(base()).getOrThrow().repository

        manager.setScraperEnabled("${repo.id}:off", true)
        manager.setAllScrapersEnabled(repo.id, true)

        val byId = store.scraperList().associateBy { it.id }
        assertFalse(byId.getValue("${repo.id}:off").enabled)
        assertTrue(byId.getValue("${repo.id}:on").enabled)

        manager.setAllScrapersEnabled(repo.id, false)
        assertTrue(store.scraperList().none { it.enabled })
    }

    @Test
    fun `a profile that shares the primary's plugins cannot change them`() = runBlocking {
        publish(scraper("a"))
        files["/repo/a.js"] = "// a"
        store.readOnly = true

        val result = manager().addRepository(base())

        assertTrue(result.isFailure)
        assertTrue(store.repositories.value.isEmpty())
    }

    // ---- execution ----

    private suspend fun installAndEnable(manager: PluginManager, vararg scrapers: String): String {
        publish(*scrapers)
        scrapers.forEach { text ->
            val id = Regex(""""id":"([^"]+)"""").find(text)!!.groupValues[1]
            files["/repo/$id.js"] = "// $id"
        }
        val repo = manager.addRepository(base()).getOrThrow().repository
        manager.setAllScrapersEnabled(repo.id, true)
        return repo.id
    }

    @Test
    fun `only enabled scrapers of an enabled repository that support the type run`() = runBlocking {
        val runner = FakeRunner { listOf(result("https://cdn.example/${it.scraperId.substringAfter(':')}.m3u8")) }
        val manager = manager(runner)
        val repoId = installAndEnable(
            manager,
            scraper("movies", types = """["movie"]"""),
            scraper("shows", types = """["tv"]"""),
            scraper("both"),
            scraper("switchedoff")
        )
        manager.setScraperEnabled("$repoId:switchedoff", false)

        val movie = manager.executeScrapersStreaming("603", "movie").toList()

        assertEquals(setOf("movies", "both"), movie.map { it.first.id.substringAfter(':') }.toSet())
        assertEquals(setOf("movies", "both"), runner.calls.map { it.scraperId.substringAfter(':') }.toSet())

        runner.calls.clear()
        manager.setRepositoryEnabled(repoId, false)
        assertTrue(manager.executeScrapersStreaming("603", "movie").toList().isEmpty())
        assertTrue(runner.calls.isEmpty())

        manager.setRepositoryEnabled(repoId, true)
        manager.setPluginsEnabled(false)
        assertTrue(manager.executeScrapersStreaming("603", "movie").toList().isEmpty())
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `episode requests carry season and episode and series scrapers get the series type`() = runBlocking {
        val runner = FakeRunner { listOf(result("https://cdn.example/x.m3u8")) }
        val manager = manager(runner)
        installAndEnable(
            manager,
            scraper("plain", types = """["movie","tv"]"""),
            scraper("declaresSeries", types = """["movie","series"]""")
        )

        manager.executeScrapersStreaming("1396", "tv", season = 2, episode = 5).toList()

        val byScraper = runner.calls.associateBy { it.scraperId.substringAfter(':') }
        byScraper.getValue("plain").let {
            assertEquals("1396", it.tmdbId); assertEquals("tv", it.mediaType)
            assertEquals(2, it.season); assertEquals(5, it.episode)
        }
        byScraper.getValue("declaresSeries").let {
            assertEquals("series", it.mediaType); assertEquals(2, it.season); assertEquals(5, it.episode)
        }
    }

    @Test
    fun `movie requests pass no season or episode and the stored settings`() = runBlocking {
        val runner = FakeRunner { listOf(result("https://cdn.example/x.m3u8")) }
        val manager = manager(runner)
        val repoId = installAndEnable(
            manager,
            scraper("a", settings = """[{"key":"quality","label":"Quality"}]""")
        )
        manager.saveScraperSettings("$repoId:a", mapOf("quality" to "1080"))

        manager.executeScrapersStreaming("603", "movie").toList()

        val call = runner.calls.single()
        assertEquals("movie", call.mediaType)
        assertNull(call.season); assertNull(call.episode)
        assertEquals(mapOf<String, Any>("quality" to "1080"), call.settings)
        assertEquals("// a", call.code)
    }

    @Test
    fun `a failing scraper does not stop the others, and results are labelled, deduplicated and capped`() = runBlocking {
        val runner = FakeRunner { call ->
            when (call.scraperId.substringAfter(':')) {
                "boom" -> throw IllegalStateException("script exploded")
                "dupes" -> listOf(result("https://cdn.example/1"), result("https://cdn.example/1"), result("https://cdn.example/2"))
                "many" -> List(400) { result("https://cdn.example/m$it") }
                else -> emptyList()
            }
        }
        val manager = manager(runner)
        installAndEnable(manager, scraper("boom"), scraper("dupes"), scraper("many"), scraper("empty"))

        val emitted = manager.executeScrapersStreaming("603", "movie").toList()
            .associate { it.first.id.substringAfter(':') to it.second }

        assertEquals(setOf("dupes", "many"), emitted.keys)
        assertEquals(listOf("https://cdn.example/1", "https://cdn.example/2"), emitted.getValue("dupes").map { it.url })
        assertTrue(emitted.getValue("dupes").all { it.provider == "Scraper dupes" })
        assertEquals(150, emitted.getValue("many").size)
    }

    @Test
    fun `no enabled scraper means no runner call and an empty flow`() = runBlocking {
        val runner = FakeRunner { error("must not run") }
        val manager = manager(runner)
        publish(scraper("a"))
        files["/repo/a.js"] = "// a"
        manager.addRepository(base()).getOrThrow()

        assertTrue(manager.executeScrapersStreaming("603", "movie").toList().isEmpty())
        assertTrue(runner.calls.isEmpty())
        assertTrue(manager.snapshot().enabledScrapers.isEmpty())
    }

    @Test
    fun `snapshot is empty when plugins are off and lists runnable scrapers when on`() = runBlocking {
        val manager = manager()
        val repoId = installAndEnable(manager, scraper("a"), scraper("b"))
        manager.setScraperEnabled("$repoId:b", false)

        assertEquals(listOf("$repoId:a"), manager.snapshot().enabledScrapers.map { it.id })
        manager.setPluginsEnabled(false)
        assertTrue(manager.snapshot().enabledScrapers.isEmpty())
        assertTrue(manager.enabledScrapers.first().isEmpty())
    }

    // ---- testing a scraper ----

    @Test
    fun `testing a scraper runs a movie sample and reports streams and diagnostics`() = runBlocking {
        val runner = FakeRunner { listOf(result("https://cdn.example/a.m3u8")) }
        val manager = manager(runner)
        val repoId = installAndEnable(manager, scraper("a"))

        val report = manager.testScraper("$repoId:a")

        assertNull(report.error)
        assertEquals(1, report.results.size)
        assertEquals("603", runner.calls.single().tmdbId)
        assertEquals("movie", runner.calls.single().mediaType)
        assertTrue(report.diagnostics.steps.any { it.startsWith("Result: 1 stream") })
    }

    @Test
    fun `testing a series-only scraper uses the first episode of a known series`() = runBlocking {
        val runner = FakeRunner { emptyList() }
        val manager = manager(runner)
        val repoId = installAndEnable(manager, scraper("shows", types = """["tv"]"""))

        val report = manager.testScraper("$repoId:shows")

        assertNull(report.error)
        assertTrue(report.results.isEmpty())
        runner.calls.single().let {
            assertEquals("1396", it.tmdbId); assertEquals(1, it.season); assertEquals(1, it.episode)
        }
    }

    @Test
    fun `a script that throws becomes a report with the error and never an exception`() = runBlocking {
        val runner = FakeRunner { throw IllegalStateException("ReferenceError: nope is not defined") }
        val manager = manager(runner)
        val repoId = installAndEnable(manager, scraper("a"))

        val report = manager.testScraper("$repoId:a")

        assertNotNull(report.error)
        assertTrue(report.error!!.contains("ReferenceError"))
        assertTrue(report.results.isEmpty())
        assertTrue(report.diagnostics.steps.any { it.startsWith("Failed:") })
    }

    @Test
    fun `testing an unknown scraper reports it as not found`() = runBlocking {
        val report = manager().testScraper("nope")

        assertNull(report.scraper)
        assertEquals("Scraper not found", report.error)
    }

    @Test
    fun `testing works on a switched-off scraper, since the user asked for it`() = runBlocking {
        val runner = FakeRunner { listOf(result("https://cdn.example/a.m3u8")) }
        val manager = manager(runner)
        publish(scraper("a"))
        files["/repo/a.js"] = "// a"
        val repo = manager.addRepository(base()).getOrThrow().repository

        val report = manager.testScraper("${repo.id}:a")

        assertEquals(1, report.results.size)
        assertFalse(store.scraperList().single().enabled)
    }
}
