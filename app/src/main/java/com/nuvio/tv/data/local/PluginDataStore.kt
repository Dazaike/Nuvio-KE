package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.plugin.PluginStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ScraperInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Profile of the plugin list [activeProfileId] sees: a profile that uses the primary profile's
 * plugins reads and writes profile 1's list (the flag the Nuvio account already carries).
 */
internal fun effectivePluginProfileId(usesPrimaryPlugins: Boolean, activeProfileId: Int): Int =
    if (usesPrimaryPlugins) 1 else activeProfileId

/** Directory (under `filesDir`) of the primary profile's downloaded scraper code; other profiles add `_p<id>`. */
internal const val PLUGIN_CODE_DIR = "plugin_code"

/** Directory name (under `filesDir`) holding one profile's downloaded scraper code. */
internal fun pluginCodeDirName(profileId: Int): String =
    if (profileId == 1) PLUGIN_CODE_DIR else "${PLUGIN_CODE_DIR}_p$profileId"

/**
 * File name of a scraper's code. The scraper id comes from a third-party manifest, so it never
 * becomes a path: a digest of it does (no separators, no `..`, no collisions between ids).
 */
internal fun pluginCodeFileName(scraperId: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(scraperId.toByteArray(Charsets.UTF_8))
    return digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".js"
}

/**
 * Plugin repositories, scrapers, per-scraper settings and global switches, per profile, plus the
 * downloaded scraper code on disk. Local to the device: plugin data is not part of the profile
 * settings sync, so the account's addon and library data is never touched by it.
 */
@Singleton
class PluginDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) : PluginStore {
    companion object {
        internal const val FEATURE = "plugin_settings"
    }

    private val repositoriesKey = stringPreferencesKey("repositories")
    private val scrapersKey = stringPreferencesKey("scrapers")
    private val scraperSettingsKey = stringPreferencesKey("scraper_settings")
    private val pluginsEnabledKey = booleanPreferencesKey("plugins_enabled")
    private val groupStreamsByRepositoryKey = booleanPreferencesKey("group_streams_by_repository")
    private val thirdPartyCodeAcknowledgedKey = booleanPreferencesKey("third_party_code_acknowledged")

    private val repoListSerializer = ListSerializer(PluginRepository.serializer())
    private val scraperListSerializer = ListSerializer(ScraperInfo.serializer())
    private val settingsSerializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))

    private fun effectiveProfileId(): Int {
        val active = profileManager.activeProfile
        return effectivePluginProfileId(
            usesPrimaryPlugins = active?.usesPrimaryPlugins == true,
            activeProfileId = profileManager.activeProfileId.value
        )
    }

    private fun store(profileId: Int = effectiveProfileId()) = factory.get(profileId, FEATURE)

    /** A profile that shares the primary's plugins may use them but not change them. */
    override fun isReadOnlyProfile(): Boolean {
        val active = profileManager.activeProfile ?: return false
        return !active.isPrimary && active.usesPrimaryPlugins
    }

    private val effectiveProfileIdFlow: Flow<Int> = combine(
        profileManager.activeProfileId,
        profileManager.profiles
    ) { activeProfileId, profiles ->
        val active = profiles.firstOrNull { it.id == activeProfileId }
        effectivePluginProfileId(active?.usesPrimaryPlugins == true, activeProfileId)
    }.distinctUntilChanged()

    private fun <T> profileState(read: (Int) -> Flow<T>): Flow<T> = flow {
        emitAll(effectiveProfileIdFlow.flatMapLatest(read))
    }

    override val repositories: Flow<List<PluginRepository>> = profileState { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> decodeRepositories(prefs[repositoriesKey]) }
    }

    override val scrapers: Flow<List<ScraperInfo>> = profileState { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> decodeScrapers(prefs[scrapersKey]) }
    }

    /** Global switch: off, no scraper runs. */
    override val pluginsEnabled: Flow<Boolean> = profileState { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> prefs[pluginsEnabledKey] ?: true }
    }

    override val groupStreamsByRepository: Flow<Boolean> = profileState { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> prefs[groupStreamsByRepositoryKey] ?: false }
    }

    /** Whether the user has confirmed once that scrapers run code downloaded from their repository. */
    override val thirdPartyCodeAcknowledged: Flow<Boolean> = profileState { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> prefs[thirdPartyCodeAcknowledgedKey] ?: false }
    }

    override suspend fun saveRepositories(repos: List<PluginRepository>) {
        if (isReadOnlyProfile()) return
        val json = localStoreJson.encodeToString(repoListSerializer, repos)
        store().edit { prefs -> prefs[repositoriesKey] = json }
    }

    override suspend fun addRepository(repo: PluginRepository) {
        val current = repositories.first().filterNot { it.id == repo.id }
        saveRepositories(current + repo)
    }

    override suspend fun updateRepository(repo: PluginRepository) {
        val current = repositories.first()
        if (current.none { it.id == repo.id }) return
        saveRepositories(current.map { if (it.id == repo.id) repo else it })
    }

    override suspend fun removeRepository(repoId: String) {
        saveRepositories(repositories.first().filterNot { it.id == repoId })
    }

    override suspend fun saveScrapers(scrapers: List<ScraperInfo>) {
        if (isReadOnlyProfile()) return
        val json = localStoreJson.encodeToString(scraperListSerializer, scrapers)
        store().edit { prefs -> prefs[scrapersKey] = json }
    }

    override suspend fun setPluginsEnabled(enabled: Boolean) {
        if (isReadOnlyProfile()) return
        store().edit { prefs -> prefs[pluginsEnabledKey] = enabled }
    }

    override suspend fun setGroupStreamsByRepository(enabled: Boolean) {
        if (isReadOnlyProfile()) return
        store().edit { prefs -> prefs[groupStreamsByRepositoryKey] = enabled }
    }

    override suspend fun setThirdPartyCodeAcknowledged(acknowledged: Boolean) {
        if (isReadOnlyProfile()) return
        store().edit { prefs -> prefs[thirdPartyCodeAcknowledgedKey] = acknowledged }
    }

    /**
     * Settings values a scraper reads back as `SCRAPER_SETTINGS`. Values are stored as strings:
     * the scraper decides how to interpret them.
     */
    override suspend fun getScraperSettings(scraperId: String): Map<String, String> =
        decodeSettings(store().data.first()[scraperSettingsKey])[scraperId].orEmpty()

    override suspend fun setScraperSettings(scraperId: String, settings: Map<String, String>) {
        if (isReadOnlyProfile()) return
        store().edit { prefs ->
            val all = decodeSettings(prefs[scraperSettingsKey]).toMutableMap()
            if (settings.isEmpty()) all.remove(scraperId) else all[scraperId] = settings
            prefs[scraperSettingsKey] = localStoreJson.encodeToString(settingsSerializer, all)
        }
    }

    /**
     * Drops every repository, scraper, scraper setting and downloaded script of the effective
     * profile, and resets the global switches. Addons and the account's other data are not touched.
     */
    override suspend fun resetAll() {
        if (isReadOnlyProfile()) return
        store().edit { prefs -> prefs.clear() }
        clearAllScraperCode()
    }

    // ---- scraper code on disk ----

    private fun codeDir(profileId: Int = effectiveProfileId()): File = File(context.filesDir, pluginCodeDirName(profileId))

    override suspend fun saveScraperCode(scraperId: String, code: String) = withContext(Dispatchers.IO) {
        if (isReadOnlyProfile()) return@withContext
        val dir = codeDir().also { it.mkdirs() }
        val target = File(dir, pluginCodeFileName(scraperId))
        // Write beside, then rename: a kill mid-write never leaves a half script that would be run.
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(code)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    override suspend fun getScraperCode(scraperId: String): String? = withContext(Dispatchers.IO) {
        val file = File(codeDir(), pluginCodeFileName(scraperId))
        if (file.isFile) file.readText() else null
    }

    override suspend fun deleteScraperCode(scraperId: String) {
        withContext(Dispatchers.IO) {
            if (isReadOnlyProfile()) return@withContext
            File(codeDir(), pluginCodeFileName(scraperId)).delete()
        }
    }

    private suspend fun clearAllScraperCode() {
        withContext(Dispatchers.IO) { codeDir().deleteRecursively() }
    }

    private fun decodeRepositories(json: String?): List<PluginRepository> =
        json?.let { runCatching { localStoreJson.decodeFromString(repoListSerializer, it) }.getOrNull() }.orEmpty()

    private fun decodeScrapers(json: String?): List<ScraperInfo> =
        json?.let { runCatching { localStoreJson.decodeFromString(scraperListSerializer, it) }.getOrNull() }.orEmpty()

    private fun decodeSettings(json: String?): Map<String, Map<String, String>> =
        json?.let { runCatching { localStoreJson.decodeFromString(settingsSerializer, it) }.getOrNull() }.orEmpty()
}
