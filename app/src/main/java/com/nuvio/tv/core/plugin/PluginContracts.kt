package com.nuvio.tv.core.plugin

import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ScraperInfo
import kotlinx.coroutines.flow.Flow

/** Persistence the [PluginManager] needs; [com.nuvio.tv.data.local.PluginDataStore] is the on-device one. */
interface PluginStore {
    val repositories: Flow<List<PluginRepository>>
    val scrapers: Flow<List<ScraperInfo>>
    val pluginsEnabled: Flow<Boolean>
    val groupStreamsByRepository: Flow<Boolean>
    val thirdPartyCodeAcknowledged: Flow<Boolean>

    /** A profile that shares another profile's plugins can use them but not change them. */
    fun isReadOnlyProfile(): Boolean

    suspend fun saveRepositories(repos: List<PluginRepository>)
    suspend fun addRepository(repo: PluginRepository)
    suspend fun updateRepository(repo: PluginRepository)
    suspend fun removeRepository(repoId: String)
    suspend fun saveScrapers(scrapers: List<ScraperInfo>)
    suspend fun setPluginsEnabled(enabled: Boolean)
    suspend fun setGroupStreamsByRepository(enabled: Boolean)
    suspend fun setThirdPartyCodeAcknowledged(acknowledged: Boolean)
    suspend fun getScraperSettings(scraperId: String): Map<String, String>
    suspend fun setScraperSettings(scraperId: String, settings: Map<String, String>)
    suspend fun saveScraperCode(scraperId: String, code: String)
    suspend fun getScraperCode(scraperId: String): String?
    suspend fun deleteScraperCode(scraperId: String)

    /** Removes every repository, scraper, setting and script of the effective profile; addons are not touched. */
    suspend fun resetAll()
}

/** Runs one scraper script for one title. [PluginRuntime] is the QuickJS implementation. */
interface PluginScriptRunner {
    suspend fun executePlugin(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
        scraperSettings: Map<String, Any>,
        diagnostics: TestDiagnostics?,
        timeoutMs: Long
    ): List<LocalScraperResult>
}

/** DI qualifier of the client that downloads plugin manifests and scripts (see [com.nuvio.tv.core.di.PluginHttpModule]). */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PluginRepositoryHttp
