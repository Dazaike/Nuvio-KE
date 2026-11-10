package com.nuvio.tv.core.plugin

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real QuickJS runtime on a device: it needs the native engine, so it cannot run as a JVM unit
 * test. Scripts are small local scrapers; nothing here depends on a third-party provider.
 */
@RunWith(AndroidJUnit4::class)
class PluginRuntimeInstrumentedTest {
    private val runtime = PluginRuntime()

    private fun run(
        script: String,
        tmdbId: String = "603",
        mediaType: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        settings: Map<String, Any> = emptyMap(),
        diagnostics: TestDiagnostics? = null,
        timeoutMs: Long = 20_000
    ) = runBlocking {
        withContext(Dispatchers.IO) {
            runtime.executePlugin(script, tmdbId, mediaType, season, episode, "test:scraper", settings, diagnostics, timeoutMs)
        }
    }

    @Test
    fun a_valid_scraper_returns_its_streams() {
        val results = run(
            """
            module.exports.getStreams = async function(tmdbId, mediaType, season, episode) {
              return [
                { name: "Local", title: "Movie", url: "https://cdn.example/v.m3u8", quality: "1080p", size: "1 GB",
                  headers: { Referer: "https://site.example/" } },
                { title: "No url" }
              ];
            };
            """.trimIndent()
        )

        val stream = results.single()
        assertEquals("https://cdn.example/v.m3u8", stream.url)
        assertEquals("1080p", stream.quality)
        assertEquals(mapOf("Referer" to "https://site.example/"), stream.headers)
    }

    @Test
    fun a_movie_request_arrives_as_tmdb_id_and_type() {
        val stream = run(
            """
            module.exports.getStreams = async (id, type, s, e) => [{
              title: id + "|" + type + "|" + s + "|" + e, url: "https://cdn.example/a"
            }];
            """.trimIndent(),
            tmdbId = "603", mediaType = "movie"
        ).single()

        assertEquals("603|movie|undefined|undefined", stream.title)
    }

    @Test
    fun an_episode_request_arrives_with_season_and_episode() {
        val stream = run(
            """
            module.exports.getStreams = async (id, type, s, e) => [{
              title: id + "|" + type + "|" + s + "|" + e, url: "https://cdn.example/a"
            }];
            """.trimIndent(),
            tmdbId = "1396", mediaType = "tv", season = 2, episode = 5
        ).single()

        assertEquals("1396|tv|2|5", stream.title)
    }

    @Test
    fun the_scraper_reads_its_own_settings_and_id() {
        val stream = run(
            """
            module.exports.getStreams = async () => [{
              title: SCRAPER_SETTINGS.quality + "|" + SCRAPER_ID, url: "https://cdn.example/a"
            }];
            """.trimIndent(),
            settings = mapOf("quality" to "720")
        ).single()

        assertEquals("720|test:scraper", stream.title)
    }

    @Test
    fun cheerio_and_crypto_js_are_available_and_other_modules_are_not() {
        val results = run(
            """
            const cheerio = require('cheerio');
            const CryptoJS = require('crypto-js');
            module.exports.getStreams = async () => {
              const ${'$'} = cheerio.load('<div><a class="x" href="https://cdn.example/from-html">t</a></div>');
              const md5 = CryptoJS.MD5('abc').toString();
              let blocked = "none";
              try { require('fs'); } catch (e) { blocked = "blocked"; }
              return [{ title: md5 + "|" + blocked, url: ${'$'}('a.x').attr('href') }];
            };
            """.trimIndent()
        )

        assertEquals("https://cdn.example/from-html", results.single().url)
        assertEquals("900150983cd24fb0d6963f7d28e17f72|blocked", results.single().title)
    }

    @Test
    fun a_script_with_a_syntax_error_fails_with_an_error() {
        try {
            run("module.exports.getStreams = async ( => {")
            fail("a syntax error must surface")
        } catch (e: Exception) {
            assertFalse(e is TimeoutCancellationException)
        }
    }

    @Test
    fun a_getStreams_that_throws_gives_no_streams_and_its_message_is_captured() {
        val diagnostics = TestDiagnostics()

        val results = run(
            "module.exports.getStreams = async () => { throw new Error('upstream layout changed'); };",
            diagnostics = diagnostics
        )

        assertTrue(results.isEmpty())
        assertTrue(diagnostics.scriptLines.any { it.contains("upstream layout changed") })
    }

    @Test
    fun a_script_without_getStreams_gives_no_streams() {
        assertTrue(run("var unrelated = 1;").isEmpty())
    }

    @Test
    fun console_output_is_captured_and_bounded() {
        val diagnostics = TestDiagnostics()

        run(
            """
            module.exports.getStreams = async () => {
              for (let i = 0; i < 500; i++) console.log("line " + i);
              return [];
            };
            """.trimIndent(),
            diagnostics = diagnostics
        )

        assertTrue(diagnostics.scriptLines.size in 1..40)
        assertTrue(diagnostics.scriptLines.contains("line 0"))
    }

    @Test
    fun a_script_that_never_returns_is_stopped_at_its_time_budget() {
        val started = System.nanoTime()
        try {
            run("module.exports.getStreams = async () => { while (true) {} };", timeoutMs = 1_500)
            fail("an endless loop must be stopped")
        } catch (e: Exception) {
            // Timed out or interrupted: either way it ended.
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("stopped after ${elapsedMs}ms", elapsedMs < 10_000)
    }

    @Test
    fun a_promise_that_never_settles_ends_the_run_with_no_streams_and_does_not_hang() {
        val started = System.nanoTime()

        val results = run("module.exports.getStreams = () => new Promise(() => {});", timeoutMs = 5_000)

        assertTrue(results.isEmpty())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
    }

    @Test
    fun a_script_waiting_on_a_timer_longer_than_its_budget_is_stopped() {
        val started = System.nanoTime()
        try {
            run(
                "module.exports.getStreams = () => new Promise(resolve => setTimeout(() => resolve([]), 60000));",
                timeoutMs = 1_500
            )
        } catch (e: Exception) {
            // Timed out: expected.
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
    }

    @Test
    fun a_script_cannot_grow_the_heap_without_bound() {
        try {
            val results = run(
                """
                module.exports.getStreams = async () => {
                  const hog = [];
                  for (;;) hog.push(new Array(1000000).fill("x".repeat(100)));
                };
                """.trimIndent(),
                timeoutMs = 30_000
            )
            assertTrue(results.isEmpty())
        } catch (e: Exception) {
            // The engine refused the allocation: the runtime survived, which is the point.
        }
        // The runtime is still usable afterwards.
        assertEquals(1, run("module.exports.getStreams = async () => [{title:'t',url:'https://a.example/v'}];").size)
    }

    @Test
    fun fetch_cannot_reach_the_device_or_the_home_network() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("secret"))
            server.start()
            val local = server.url("/secret").toString()

            val results = run(
                """
                module.exports.getStreams = async () => {
                  const out = [];
                  for (const target of ["$local", "http://127.0.0.1:1/", "http://192.168.1.1/", "http://169.254.169.254/",
                                        "file:///proc/self/environ", "content://settings/system", "ftp://x.example/"]) {
                    let r;
                    try { r = await fetch(target); } catch (e) { r = { ok: false, status: -1 }; }
                    out.push({ title: target + "=" + r.ok, url: "https://result.example/" + out.length });
                  }
                  return out;
                };
                """.trimIndent()
            )

            assertEquals(7, results.size)
            assertTrue(results.joinToString { it.title }, results.none { it.title.endsWith("=true") })
            assertEquals("the local server must never see a request", 0, server.requestCount)
        }
    }

    @Test
    fun results_with_unplayable_urls_are_dropped_before_they_leave_the_runtime() {
        val results = run(
            """
            module.exports.getStreams = async () => [
              { title: "file", url: "file:///data/x" },
              { title: "js", url: "javascript:alert(1)" },
              { title: "ok", url: "https://cdn.example/ok.m3u8" }
            ];
            """.trimIndent()
        )

        assertEquals(listOf("https://cdn.example/ok.m3u8"), results.map { it.url })
        assertNull(results.single().headers)
    }
}
