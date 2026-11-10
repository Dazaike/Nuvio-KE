package com.nuvio.tv.ui.screens.plugin

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.plugin.PluginInstallResult
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.plugin.ScraperTestReport
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ScraperInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the user has to confirm before third-party code runs for the first time. One confirmation
 * covers every later scraper: [PluginManager.acknowledgeThirdPartyCode] remembers it.
 */
sealed interface PluginConsent {
    data class EnableScraper(val scraperId: String, val scraperName: String) : PluginConsent
    data class EnableAll(val repositoryId: String, val repositoryName: String) : PluginConsent
    data class Test(val scraperId: String, val scraperName: String) : PluginConsent
}

/** The settings panel of one scraper, opened over the screen with the values saved so far. */
data class ScraperSettingsEditor(val scraper: ScraperInfo, val values: Map<String, String>)

data class PluginsUiState(
    /** True until the first read of the stores: the screen shows no rows before it. */
    val isLoading: Boolean = true,
    val pluginsEnabled: Boolean = true,
    val groupStreamsByRepository: Boolean = false,
    val thirdPartyCodeAcknowledged: Boolean = false,
    val repositories: List<PluginRepository> = emptyList(),
    val scrapers: List<ScraperInfo> = emptyList(),
    /** This profile uses the primary profile's plugins: it can look but not change. */
    val readOnly: Boolean = false,
    /** Scrapers look titles up by TMDB id: with no key none of them can run. */
    val tmdbKeyMissing: Boolean = false,
    val installUrl: String = "",
    val isInstalling: Boolean = false,
    val busyRepositoryIds: Set<String> = emptySet(),
    val testingScraperId: String? = null,
    val testReport: ScraperTestReport? = null,
    val pendingConsent: PluginConsent? = null,
    val settingsEditor: ScraperSettingsEditor? = null,
    val transientMessage: String? = null
) {
    fun scrapersOf(repositoryId: String): List<ScraperInfo> = scrapers.filter { it.repositoryId == repositoryId }

    val enabledScraperCount: Int get() = scrapers.count { it.enabled }
}

@HiltViewModel
class PluginsViewModel @Inject constructor(
    private val pluginManager: PluginManager,
    tmdbKeys: TmdbApiKeyProvider,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val local = MutableStateFlow(PluginsUiState())

    private var testJob: Job? = null

    val uiState: StateFlow<PluginsUiState> = combine(
        local,
        combine(
            pluginManager.pluginsEnabled,
            pluginManager.groupStreamsByRepository,
            pluginManager.thirdPartyCodeAcknowledged,
            pluginManager.repositories,
            pluginManager.scrapers
        ) { enabled, group, acknowledged, repos, scrapers ->
            StoreSnapshot(enabled, group, acknowledged, repos, scrapers)
        },
        tmdbKeys.state
    ) { ui, snapshot, keyState ->
        ui.copy(
            isLoading = false,
            pluginsEnabled = snapshot.pluginsEnabled,
            groupStreamsByRepository = snapshot.group,
            thirdPartyCodeAcknowledged = snapshot.acknowledged,
            repositories = snapshot.repositories,
            scrapers = snapshot.scrapers,
            readOnly = pluginManager.isReadOnly,
            tmdbKeyMissing = keyState != null && !keyState.hasKey
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PluginsUiState())

    private data class StoreSnapshot(
        val pluginsEnabled: Boolean,
        val group: Boolean,
        val acknowledged: Boolean,
        val repositories: List<PluginRepository>,
        val scrapers: List<ScraperInfo>
    )

    fun onInstallUrlChange(value: String) = local.update { it.copy(installUrl = value.take(MAX_URL_CHARS)) }

    fun clearTransientMessage() = local.update { it.copy(transientMessage = null) }

    fun installRepository() {
        val input = local.value.installUrl
        if (local.value.isInstalling) return
        if (input.isBlank()) {
            message(R.string.plugins_error_enter_url)
            return
        }
        local.update { it.copy(isInstalling = true) }
        viewModelScope.launch {
            pluginManager.addRepository(input).fold(
                onSuccess = { result ->
                    local.update { it.copy(isInstalling = false, installUrl = "") }
                    message(installedMessage(result))
                },
                onFailure = { error ->
                    local.update { it.copy(isInstalling = false) }
                    message(context.getString(R.string.plugins_error_install, error.message.orEmpty()))
                }
            )
        }
    }

    fun refreshRepository(repositoryId: String) {
        if (repositoryId in local.value.busyRepositoryIds) return
        local.update { it.copy(busyRepositoryIds = it.busyRepositoryIds + repositoryId) }
        viewModelScope.launch {
            val result = pluginManager.refreshRepository(repositoryId)
            local.update { it.copy(busyRepositoryIds = it.busyRepositoryIds - repositoryId) }
            result.fold(
                onSuccess = { message(refreshedMessage(it)) },
                onFailure = { message(context.getString(R.string.plugins_error_refresh, it.message.orEmpty())) }
            )
        }
    }

    fun removeRepository(repositoryId: String) {
        viewModelScope.launch {
            // A test result of a scraper that no longer exists must not linger on screen.
            if (local.value.testReport?.scraper?.repositoryId == repositoryId) clearTestReport()
            pluginManager.removeRepository(repositoryId)
            message(R.string.plugins_repository_removed)
        }
    }

    fun setRepositoryEnabled(repositoryId: String, enabled: Boolean) {
        viewModelScope.launch { pluginManager.setRepositoryEnabled(repositoryId, enabled) }
    }

    fun setPluginsEnabled(enabled: Boolean) {
        viewModelScope.launch { pluginManager.setPluginsEnabled(enabled) }
    }

    fun setGroupStreamsByRepository(enabled: Boolean) {
        viewModelScope.launch { pluginManager.setGroupStreamsByRepository(enabled) }
    }

    /** Switching a scraper off is always immediate; switching one on goes through the consent check. */
    fun setScraperEnabled(scraper: ScraperInfo, enabled: Boolean) {
        if (!enabled) {
            viewModelScope.launch { pluginManager.setScraperEnabled(scraper.id, false) }
            return
        }
        requireConsent(PluginConsent.EnableScraper(scraper.id, scraper.name))
    }

    fun setAllScrapersEnabled(repository: PluginRepository, enabled: Boolean) {
        if (!enabled) {
            viewModelScope.launch { pluginManager.setAllScrapersEnabled(repository.id, false) }
            return
        }
        requireConsent(PluginConsent.EnableAll(repository.id, repository.name))
    }

    fun testScraper(scraper: ScraperInfo) {
        requireConsent(PluginConsent.Test(scraper.id, scraper.name))
    }

    fun openSettings(scraper: ScraperInfo) {
        if (scraper.settings.isEmpty() || pluginManager.isReadOnly) return
        viewModelScope.launch {
            val values = pluginManager.getScraperSettings(scraper.id)
            local.update { it.copy(settingsEditor = ScraperSettingsEditor(scraper, values)) }
        }
    }

    fun dismissSettings() = local.update { it.copy(settingsEditor = null) }

    fun saveSettings(values: Map<String, String>) {
        val editor = local.value.settingsEditor ?: return
        local.update { it.copy(settingsEditor = null) }
        viewModelScope.launch { pluginManager.saveScraperSettings(editor.scraper.id, values) }
    }

    fun clearTestReport() {
        testJob?.cancel()
        local.update { it.copy(testReport = null, testingScraperId = null) }
    }

    fun dismissConsent() = local.update { it.copy(pendingConsent = null) }

    fun confirmConsent() {
        val consent = local.value.pendingConsent ?: return
        local.update { it.copy(pendingConsent = null) }
        viewModelScope.launch {
            pluginManager.acknowledgeThirdPartyCode()
            run(consent)
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            clearTestReport()
            pluginManager.resetAll()
            message(R.string.plugins_reset_done)
        }
    }

    private fun requireConsent(consent: PluginConsent) {
        if (pluginManager.isReadOnly) return
        viewModelScope.launch {
            if (pluginManager.thirdPartyCodeAcknowledged.first()) {
                run(consent)
            } else {
                local.update { it.copy(pendingConsent = consent) }
            }
        }
    }

    private fun run(consent: PluginConsent) {
        when (consent) {
            is PluginConsent.EnableScraper -> viewModelScope.launch { pluginManager.setScraperEnabled(consent.scraperId, true) }
            is PluginConsent.EnableAll -> viewModelScope.launch { pluginManager.setAllScrapersEnabled(consent.repositoryId, true) }
            is PluginConsent.Test -> startTest(consent.scraperId)
        }
    }

    private fun startTest(scraperId: String) {
        testJob?.cancel()
        local.update { it.copy(testingScraperId = scraperId, testReport = null) }
        testJob = viewModelScope.launch {
            val report = pluginManager.testScraper(scraperId)
            local.update { it.copy(testingScraperId = null, testReport = report) }
        }
    }

    private fun installedMessage(result: PluginInstallResult): String =
        if (result.failed == 0) {
            context.getString(R.string.plugins_installed, result.repository.name, result.installed)
        } else {
            context.getString(R.string.plugins_installed_partial, result.repository.name, result.installed, result.failed)
        }

    private fun refreshedMessage(result: PluginInstallResult): String =
        if (result.failed == 0) {
            context.getString(R.string.plugins_refreshed, result.repository.name, result.installed)
        } else {
            context.getString(R.string.plugins_refreshed_partial, result.repository.name, result.installed, result.failed)
        }

    private fun message(resId: Int) = message(context.getString(resId))

    private fun message(text: String) = local.update { it.copy(transientMessage = text) }

    private companion object {
        const val MAX_URL_CHARS = 2048
    }
}
