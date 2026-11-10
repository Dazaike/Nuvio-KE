package com.nuvio.tv.core.plugin

import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ScraperInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** In-memory [PluginStore]: the manager's behaviour without Android storage. */
internal class FakePluginStore(var readOnly: Boolean = false) : PluginStore {
    override val repositories = MutableStateFlow<List<PluginRepository>>(emptyList())
    override val scrapers = MutableStateFlow<List<ScraperInfo>>(emptyList())
    override val pluginsEnabled = MutableStateFlow(true)
    override val groupStreamsByRepository = MutableStateFlow(false)
    override val thirdPartyCodeAcknowledged = MutableStateFlow(false)
    // Downloads run in parallel: the maps are shared between threads, as the files on disk are.
    val code: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    val settings: MutableMap<String, Map<String, String>> = java.util.concurrent.ConcurrentHashMap()

    override fun isReadOnlyProfile() = readOnly
    override suspend fun saveRepositories(repos: List<PluginRepository>) { repositories.value = repos }
    override suspend fun addRepository(repo: PluginRepository) { repositories.value = repositories.value.filterNot { it.id == repo.id } + repo }
    override suspend fun updateRepository(repo: PluginRepository) {
        repositories.value = repositories.value.map { if (it.id == repo.id) repo else it }
    }
    override suspend fun removeRepository(repoId: String) { repositories.value = repositories.value.filterNot { it.id == repoId } }
    override suspend fun saveScrapers(scrapers: List<ScraperInfo>) { this.scrapers.value = scrapers }
    override suspend fun setPluginsEnabled(enabled: Boolean) { pluginsEnabled.value = enabled }
    override suspend fun setGroupStreamsByRepository(enabled: Boolean) { groupStreamsByRepository.value = enabled }
    override suspend fun setThirdPartyCodeAcknowledged(acknowledged: Boolean) { thirdPartyCodeAcknowledged.value = acknowledged }
    override suspend fun getScraperSettings(scraperId: String): Map<String, String> = settings[scraperId].orEmpty()
    override suspend fun setScraperSettings(scraperId: String, settings: Map<String, String>) {
        if (settings.isEmpty()) this.settings.remove(scraperId) else this.settings[scraperId] = settings
    }
    override suspend fun saveScraperCode(scraperId: String, code: String) { this.code[scraperId] = code }
    override suspend fun getScraperCode(scraperId: String): String? = code[scraperId]
    override suspend fun deleteScraperCode(scraperId: String) { code.remove(scraperId) }
    override suspend fun resetAll() {
        repositories.value = emptyList(); scrapers.value = emptyList(); code.clear(); settings.clear()
    }
}

/** One request a [FakeRunner] saw. */
internal data class RunnerCall(
    val scraperId: String,
    val code: String,
    val tmdbId: String,
    val mediaType: String,
    val season: Int?,
    val episode: Int?,
    val settings: Map<String, Any>
)

/** A script runner whose answer per scraper id the test chooses; no JavaScript involved. */
internal class FakeRunner(
    private val answer: suspend (RunnerCall) -> List<LocalScraperResult>
) : PluginScriptRunner {
    val calls = java.util.Collections.synchronizedList(mutableListOf<RunnerCall>())

    override suspend fun executePlugin(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
        scraperSettings: Map<String, Any>,
        diagnostics: TestDiagnostics?,
        timeoutMs: Long
    ): List<LocalScraperResult> {
        val call = RunnerCall(scraperId, code, tmdbId, mediaType, season, episode, scraperSettings)
        calls += call
        return answer(call)
    }
}

internal fun result(url: String, title: String = "Title", quality: String? = "1080p") =
    LocalScraperResult(title = title, url = url, quality = quality)

internal suspend fun PluginStore.scraperList(): List<ScraperInfo> = scrapers.first()
