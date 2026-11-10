package com.nuvio.tv.ui.screens.plugin

import android.content.Context
import com.nuvio.tv.core.plugin.FakePluginStore
import com.nuvio.tv.core.plugin.FakeRunner
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.plugin.result
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.ScraperSettingField
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Third-party code starts only after a confirmation; switching it off never asks. */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginsViewModelTest {
    private val repository = PluginRepository("r1", "Repo", "https://r.example/manifest.json", scraperCount = 2)
    private lateinit var store: FakePluginStore
    private lateinit var runner: FakeRunner
    private lateinit var viewModel: PluginsViewModel

    private fun scraper(id: String, enabled: Boolean = false, manifestEnabled: Boolean = true) = ScraperInfo(
        id = "r1:$id", name = "Scraper $id", description = "", version = "1.0", filename = "$id.js",
        supportedTypes = listOf("movie", "tv"), enabled = enabled, manifestEnabled = manifestEnabled,
        logo = null, contentLanguage = emptyList(), repositoryId = "r1", formats = null
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = FakePluginStore()
        store.repositories.value = listOf(repository)
        store.scrapers.value = listOf(scraper("a"), scraper("b"), scraper("c", enabled = true))
        store.code["r1:a"] = "// a"
        runner = FakeRunner { listOf(result("https://cdn.example/a.m3u8")) }
        val context = mockk<Context>()
        every { context.getString(any()) } returns "text"
        every { context.getString(any(), *anyVararg()) } returns "text"
        val keys = TmdbApiKeyProvider(TestPreferencesDataStore(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), "built-in")
        viewModel = PluginsViewModel(PluginManager(store, runner, OkHttpClient()), keys, context)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun state() = viewModel.uiState.first { !it.isLoading }

    private fun enabledIds() = store.scrapers.value.filter { it.enabled }.map { it.id }.toSet()

    @Test
    fun `the first enable asks for consent and changes nothing until it is given`() = runTest {
        val job = launch { viewModel.uiState.collect {} }
        val scraperA = state().scrapers.first { it.id == "r1:a" }

        viewModel.setScraperEnabled(scraperA, true)

        val pending = state().pendingConsent
        assertEquals(PluginConsent.EnableScraper("r1:a", "Scraper a"), pending)
        assertEquals(setOf("r1:c"), enabledIds())
        assertFalse(store.thirdPartyCodeAcknowledged.value)

        viewModel.confirmConsent()

        assertEquals(setOf("r1:a", "r1:c"), enabledIds())
        assertTrue(store.thirdPartyCodeAcknowledged.value)
        assertNull(state().pendingConsent)
        job.cancel()
    }

    @Test
    fun `declining leaves the scraper off and asks again next time`() = runTest {
        val job = launch { viewModel.uiState.collect {} }
        val scraperA = state().scrapers.first { it.id == "r1:a" }

        viewModel.setScraperEnabled(scraperA, true)
        viewModel.dismissConsent()

        assertEquals(setOf("r1:c"), enabledIds())
        assertFalse(store.thirdPartyCodeAcknowledged.value)
        viewModel.setScraperEnabled(scraperA, true)
        assertNotNull(state().pendingConsent)
        job.cancel()
    }

    @Test
    fun `after one confirmation later enables go straight through`() = runTest {
        store.thirdPartyCodeAcknowledged.value = true
        val job = launch { viewModel.uiState.collect {} }

        viewModel.setScraperEnabled(state().scrapers.first { it.id == "r1:a" }, true)
        viewModel.setAllScrapersEnabled(repository, true)

        assertNull(state().pendingConsent)
        assertEquals(setOf("r1:a", "r1:b", "r1:c"), enabledIds())
        job.cancel()
    }

    @Test
    fun `switching a scraper or a repository off never asks`() = runTest {
        val job = launch { viewModel.uiState.collect {} }

        viewModel.setScraperEnabled(state().scrapers.first { it.id == "r1:c" }, false)
        assertNull(state().pendingConsent)
        assertTrue(enabledIds().isEmpty())

        store.scrapers.value = store.scrapers.value.map { it.copy(enabled = true) }
        viewModel.setAllScrapersEnabled(repository, false)
        assertTrue(enabledIds().isEmpty())
        job.cancel()
    }

    @Test
    fun `enable all asks once for the repository`() = runTest {
        val job = launch { viewModel.uiState.collect {} }

        viewModel.setAllScrapersEnabled(repository, true)
        assertEquals(PluginConsent.EnableAll("r1", "Repo"), state().pendingConsent)
        assertEquals(setOf("r1:c"), enabledIds())

        viewModel.confirmConsent()
        assertEquals(setOf("r1:a", "r1:b", "r1:c"), enabledIds())
        job.cancel()
    }

    @Test
    fun `testing needs the same consent, then shows the report and can hide it`() = runTest {
        val job = launch { viewModel.uiState.collect {} }
        val scraperA = state().scrapers.first { it.id == "r1:a" }

        viewModel.testScraper(scraperA)
        assertEquals(PluginConsent.Test("r1:a", "Scraper a"), state().pendingConsent)
        assertTrue(runner.calls.isEmpty())

        viewModel.confirmConsent()
        // The scraper runs on the plugin pool, in real time.
        val report = withContext(Dispatchers.Default) {
            withTimeout(10_000) { viewModel.uiState.first { it.testReport != null }.testReport }
        }
        assertNotNull(report)
        assertEquals("error=${report!!.error} steps=${report.diagnostics.steps}", 1, report.results.size)
        assertNull(state().testingScraperId)
        assertEquals(1, runner.calls.size)

        viewModel.clearTestReport()
        assertNull(state().testReport)
        job.cancel()
    }

    @Test
    fun `a profile that shares the primary's plugins is never asked and changes nothing`() = runTest {
        store.readOnly = true
        val job = launch { viewModel.uiState.collect {} }
        val scraperA = state().scrapers.first { it.id == "r1:a" }

        viewModel.setScraperEnabled(scraperA, true)

        assertNull(state().pendingConsent)
        assertEquals(setOf("r1:c"), enabledIds())
        assertTrue(state().readOnly)
        job.cancel()
    }

    private fun withSettingsField() {
        store.scrapers.value = store.scrapers.value.map {
            if (it.id == "r1:a") it.copy(settings = listOf(ScraperSettingField("vg_cookie", "vg cookie"))) else it
        }
    }

    @Test
    fun `a scraper that declares settings opens an editor with the saved values and saves only what was typed`() = runTest {
        withSettingsField()
        store.settings["r1:a"] = mapOf("vg_cookie" to "old")
        val job = launch { viewModel.uiState.collect {} }
        val scraperA = state().scrapers.first { it.id == "r1:a" }

        viewModel.openSettings(scraperA)
        val editor = state().settingsEditor
        assertNotNull(editor)
        assertEquals(mapOf("vg_cookie" to "old"), editor!!.values)

        viewModel.saveSettings(mapOf("vg_cookie" to "  new  ", "other" to "x"))

        assertNull(state().settingsEditor)
        assertEquals(mapOf("vg_cookie" to "new"), store.settings["r1:a"])
        job.cancel()
    }

    @Test
    fun `dismissing the editor saves nothing and a scraper without settings opens none`() = runTest {
        withSettingsField()
        val job = launch { viewModel.uiState.collect {} }

        viewModel.openSettings(state().scrapers.first { it.id == "r1:a" })
        viewModel.dismissSettings()
        assertNull(state().settingsEditor)
        assertTrue(store.settings.isEmpty())

        viewModel.openSettings(state().scrapers.first { it.id == "r1:b" })
        assertNull(state().settingsEditor)
        job.cancel()
    }

    @Test
    fun `a read-only profile cannot open the editor`() = runTest {
        withSettingsField()
        store.readOnly = true
        val job = launch { viewModel.uiState.collect {} }

        viewModel.openSettings(state().scrapers.first { it.id == "r1:a" })

        assertNull(state().settingsEditor)
        job.cancel()
    }

    @Test
    fun `resetting drops repositories and scrapers`() = runTest {
        val job = launch { viewModel.uiState.collect {} }

        viewModel.resetAll()

        assertTrue(state().repositories.isEmpty())
        assertTrue(state().scrapers.isEmpty())
        assertTrue(store.code.isEmpty())
        job.cancel()
    }

    @Test
    fun `a blank install url is refused without touching the network`() = runTest {
        val job = launch { viewModel.uiState.collect {} }

        viewModel.installRepository()

        assertEquals("text", state().transientMessage)
        assertFalse(state().isInstalling)
        assertEquals(1, store.repositories.value.size)
        job.cancel()
    }
}
