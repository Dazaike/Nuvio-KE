package com.nuvio.tv.core.plugin

import android.util.Log
import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.PluginManifest
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.ScraperManifestInfo
import com.nuvio.tv.domain.model.ScraperSettingField
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "PluginManager"
private const val MANIFEST_FILE = "manifest.json"
private const val USER_AGENT = "NuvioTV/1.0"
private val SETTING_KEY = Regex("^[A-Za-z][A-Za-z0-9_]{0,39}$")

/** Largest manifest and largest scraper script taken from a repository. */
internal const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024
internal const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024
internal const val MAX_SCRAPERS_PER_REPOSITORY = 200
internal const val MAX_REPOSITORIES = 30
internal const val MAX_SETTING_VALUE_CHARS = 4096
private const val MAX_SETTING_FIELDS = 10

/**
 * At most this many scrapers run at once. Each runs its own QuickJS context and HTTP fan-out; on a
 * small TV box a wider fan-out slows the player and the UI more than it speeds the search up.
 */
internal const val MAX_CONCURRENT_SCRAPERS = 3
private const val MAX_PARALLEL_DOWNLOADS = 4
private const val MAX_RESULT_ITEMS = 150
private const val SCRAPER_STAGGER_MS = 60L

/** Test requests: a well-known movie and a well-known first episode, TMDB ids. */
private const val TEST_MOVIE_TMDB_ID = "603"
private const val TEST_SERIES_TMDB_ID = "1396"

@OptIn(ExperimentalSerializationApi::class)
private val manifestJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Why a repository could not be installed or refreshed; the message is shown to the user as is. */
class PluginRepositoryException(message: String) : Exception(message)

/** Outcome of installing or refreshing a repository. */
data class PluginInstallResult(
    val repository: PluginRepository,
    /** Scrapers whose script was downloaded and is installed. */
    val installed: Int,
    /** Manifest entries whose script could not be downloaded (too large, HTTP error, unreachable). */
    val failed: Int,
    /** Scrapers the manifest no longer lists, removed by a refresh. */
    val removed: Int = 0
)

/** What one manual scraper test produced. */
data class ScraperTestReport(
    /** Null when the id names no installed scraper. */
    val scraper: ScraperInfo?,
    val results: List<LocalScraperResult>,
    val diagnostics: TestDiagnostics,
    val durationMs: Long,
    /** Why the run failed, null when it completed (with or without streams). */
    val error: String?
)

/** Plugin configuration captured at the start of a stream search. */
data class PluginSnapshot(
    val enabledScrapers: List<ScraperInfo>,
    val groupByRepository: Boolean,
    val repositories: List<PluginRepository>
)

/**
 * Installs Nuvio JavaScript plugin repositories and runs their scrapers.
 *
 * Third-party code is opt-in at every step: a repository only downloads scripts; a scraper starts
 * switched off and runs only after the user enables it. Everything a repository supplies is bounded
 * (manifest size, script size, scraper count) and validated before it is stored, and scripts live
 * under digest-derived file names, never under a name the manifest chose.
 */
@Singleton
class PluginManager @Inject constructor(
    private val store: PluginStore,
    private val runner: PluginScriptRunner,
    @PluginRepositoryHttp private val http: OkHttpClient
) {
    private val scraperSemaphore = Semaphore(MAX_CONCURRENT_SCRAPERS)

    // One low-priority pool for every script, so scraper CPU never preempts the player or the UI.
    private val pluginDispatcher: CoroutineDispatcher =
        Executors.newFixedThreadPool(MAX_CONCURRENT_SCRAPERS) { runnable ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "plugin-worker").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    val repositories: Flow<List<PluginRepository>> = store.repositories
    val scrapers: Flow<List<ScraperInfo>> = store.scrapers
    val pluginsEnabled: Flow<Boolean> = store.pluginsEnabled
    val groupStreamsByRepository: Flow<Boolean> = store.groupStreamsByRepository
    val thirdPartyCodeAcknowledged: Flow<Boolean> = store.thirdPartyCodeAcknowledged

    /** True when this profile shares another profile's plugins and so cannot change them. */
    val isReadOnly: Boolean get() = store.isReadOnlyProfile()

    /** Scrapers that may run now: switched on, in an enabled repository, with plugins enabled globally. */
    val enabledScrapers: Flow<List<ScraperInfo>> = combine(
        store.scrapers,
        store.repositories,
        store.pluginsEnabled
    ) { scraperList, repoList, enabled ->
        if (!enabled) emptyList() else runnableScrapers(scraperList, repoList)
    }

    suspend fun snapshot(): PluginSnapshot {
        val enabled = pluginsEnabled.first()
        if (!enabled) return PluginSnapshot(emptyList(), groupByRepository = false, repositories = emptyList())
        val repos = repositories.first()
        return PluginSnapshot(
            enabledScrapers = runnableScrapers(scrapers.first(), repos),
            groupByRepository = groupStreamsByRepository.first(),
            repositories = repos
        )
    }

    // ---- repositories ----

    /** Downloads the manifest at [input] and every script it lists. Scripts are installed switched off. */
    suspend fun addRepository(input: String): Result<PluginInstallResult> = withContext(Dispatchers.IO) {
        try {
            requireWritable()
            val manifestUrl = canonicalManifestUrl(input)
            val existing = store.repositories.first()
            if (existing.any { normalizeKey(it.url) == normalizeKey(manifestUrl.toString()) }) {
                throw PluginRepositoryException("This repository is already installed")
            }
            if (existing.size >= MAX_REPOSITORIES) {
                throw PluginRepositoryException("At most $MAX_REPOSITORIES repositories can be installed")
            }
            val manifest = fetchManifest(manifestUrl)
            val repoId = UUID.randomUUID().toString()
            val download = downloadScrapers(repoId, manifestUrl, manifest.scrapers, previous = emptyList())
            if (manifest.scrapers.isNotEmpty() && download.scrapers.isEmpty()) {
                throw PluginRepositoryException("None of the ${manifest.scrapers.size} scrapers could be downloaded")
            }
            val repo = PluginRepository(
                id = repoId,
                name = manifest.name.trim(),
                url = manifestUrl.toString(),
                description = manifest.description?.trim()?.takeIf { it.isNotEmpty() },
                enabled = true,
                lastUpdated = System.currentTimeMillis(),
                scraperCount = download.scrapers.size,
                version = manifest.version.trim().takeIf { it.isNotEmpty() }
            )
            // Scrapers first: a repository row never exists without the scripts it counts.
            store.saveScrapers(store.scrapers.first() + download.scrapers)
            store.addRepository(repo)
            Log.d(TAG, "Repository added: ${repo.name} (${download.scrapers.size} scrapers, ${download.failed} failed)")
            Result.success(PluginInstallResult(repo, installed = download.scrapers.size, failed = download.failed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add repository: ${e.javaClass.simpleName}")
            Result.failure(e.asRepositoryException())
        }
    }

    /**
     * Re-reads the manifest and re-downloads every script. Switches the user set are kept, scrapers the
     * manifest still lists keep their settings, new ones start off, and ones it dropped are removed.
     */
    suspend fun refreshRepository(repoId: String): Result<PluginInstallResult> = withContext(Dispatchers.IO) {
        try {
            requireWritable()
            val repo = store.repositories.first().firstOrNull { it.id == repoId }
                ?: throw PluginRepositoryException("Repository not found")
            val manifestUrl = repo.url.toHttpUrlOrNull()
                ?.takeIf { PluginNetworkGuard.isAllowedRepositoryUrl(it) }
                ?: throw PluginRepositoryException("The repository URL is no longer valid")
            val manifest = fetchManifest(manifestUrl)
            val allScrapers = store.scrapers.first()
            val previous = allScrapers.filter { it.repositoryId == repoId }
            val download = downloadScrapers(repoId, manifestUrl, manifest.scrapers, previous)
            if (manifest.scrapers.isNotEmpty() && download.scrapers.isEmpty()) {
                throw PluginRepositoryException("None of the ${manifest.scrapers.size} scrapers could be downloaded")
            }

            // A scraper whose download failed keeps its previous copy; only ones the manifest dropped go.
            val listedIds = manifest.scrapers.mapTo(HashSet()) { "$repoId:${it.id}" }
            val failedButKept = previous.filter { it.id in listedIds && download.scrapers.none { s -> s.id == it.id } }
            val dropped = previous.filter { it.id !in listedIds }
            dropped.forEach { store.deleteScraperCode(it.id); store.setScraperSettings(it.id, emptyMap()) }
            val repoScrapers = download.scrapers + failedButKept
            store.saveScrapers(allScrapers.filter { it.repositoryId != repoId } + repoScrapers)

            val updated = repo.copy(
                name = manifest.name.trim(),
                description = manifest.description?.trim()?.takeIf { it.isNotEmpty() },
                lastUpdated = System.currentTimeMillis(),
                scraperCount = repoScrapers.size,
                version = manifest.version.trim().takeIf { it.isNotEmpty() }
            )
            store.updateRepository(updated)
            Result.success(PluginInstallResult(updated, installed = download.scrapers.size, failed = download.failed, removed = dropped.size))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh repository: ${e.javaClass.simpleName}")
            Result.failure(e.asRepositoryException())
        }
    }

    /** Removes the repository, its scrapers, their settings and their downloaded scripts. */
    suspend fun removeRepository(repoId: String) {
        if (store.isReadOnlyProfile()) return
        val all = store.scrapers.first()
        all.filter { it.repositoryId == repoId }.forEach {
            store.deleteScraperCode(it.id)
            store.setScraperSettings(it.id, emptyMap())
        }
        store.saveScrapers(all.filter { it.repositoryId != repoId })
        store.removeRepository(repoId)
    }

    /** A disabled repository keeps its scrapers installed but none of them runs. */
    suspend fun setRepositoryEnabled(repoId: String, enabled: Boolean) {
        val repo = store.repositories.first().firstOrNull { it.id == repoId } ?: return
        if (repo.enabled != enabled) store.updateRepository(repo.copy(enabled = enabled))
    }

    // ---- scrapers ----

    /** A scraper its repository's manifest marks disabled cannot be switched on. */
    suspend fun setScraperEnabled(scraperId: String, enabled: Boolean) {
        val all = store.scrapers.first()
        store.saveScrapers(all.map { scraper ->
            if (scraper.id == scraperId && (!enabled || scraper.manifestEnabled)) scraper.copy(enabled = enabled) else scraper
        })
    }

    suspend fun setAllScrapersEnabled(repoId: String, enabled: Boolean) {
        val all = store.scrapers.first()
        store.saveScrapers(all.map { scraper ->
            if (scraper.repositoryId == repoId && (!enabled || scraper.manifestEnabled)) scraper.copy(enabled = enabled) else scraper
        })
    }

    suspend fun getScraperSettings(scraperId: String): Map<String, String> = store.getScraperSettings(scraperId)

    /**
     * Saves the values the user typed for [scraperId]. Only keys the scraper declared are kept, values are
     * trimmed, and a blank one removes the setting, so a script never sees a key its manifest did not ask for.
     */
    suspend fun saveScraperSettings(scraperId: String, values: Map<String, String>) {
        val declared = store.scrapers.first().firstOrNull { it.id == scraperId }?.settings.orEmpty()
            .mapTo(HashSet()) { it.key }
        val clean = values.entries
            .filter { it.key in declared }
            .associate { it.key to it.value.trim().take(MAX_SETTING_VALUE_CHARS) }
            .filterValues { it.isNotEmpty() && it.none { c -> c == '\n' || c == '\r' || c == '\u0000' } }
        store.setScraperSettings(scraperId, clean)
    }

    // ---- global settings ----

    suspend fun setPluginsEnabled(enabled: Boolean) = store.setPluginsEnabled(enabled)

    suspend fun setGroupStreamsByRepository(enabled: Boolean) = store.setGroupStreamsByRepository(enabled)

    suspend fun acknowledgeThirdPartyCode() = store.setThirdPartyCodeAcknowledged(true)

    /** Drops all plugin repositories, scrapers, settings and scripts of this profile. Addons stay. */
    suspend fun resetAll() = store.resetAll()

    // ---- execution ----

    /**
     * Runs every enabled scraper that supports [mediaType] and emits each one's streams as it finishes.
     * Collection is cancellable: cancelling stops the scrapers not yet started, interrupts the running
     * ones, and cancels their in-flight HTTP calls. A failing scraper is skipped, never fatal.
     */
    fun executeScrapersStreaming(
        tmdbId: String,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null
    ): Flow<Pair<ScraperInfo, List<LocalScraperResult>>> = channelFlow {
        val runnable = enabledScrapers.first().filter { it.supportsType(mediaType) }
        if (runnable.isEmpty()) return@channelFlow
        Log.d(TAG, "Running ${runnable.size} scrapers for $mediaType:$tmdbId")
        runnable.forEachIndexed { index, scraper ->
            launch {
                if (index > 0) delay(index * SCRAPER_STAGGER_MS)
                try {
                    val results = scraperSemaphore.withPermit {
                        runScraper(scraper, tmdbId, mediaType, season, episode, diagnostics = null)
                    }
                    if (results.isNotEmpty()) send(scraper to results)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Scraper ${scraper.name} failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * Runs one scraper on a fixed sample title (a movie, or a series' first episode) and reports what
     * happened. Never throws except on cancellation: a broken script is a report with an [ScraperTestReport.error].
     */
    suspend fun testScraper(scraperId: String): ScraperTestReport {
        val diagnostics = TestDiagnostics()
        val scraper = store.scrapers.first().firstOrNull { it.id == scraperId }
            ?: return ScraperTestReport(
                scraper = null,
                results = emptyList(),
                diagnostics = diagnostics.also { it.addStep("Scraper not found") },
                durationMs = 0,
                error = "Scraper not found"
            )
        val movie = scraper.supportsType("movie")
        val tmdbId = if (movie) TEST_MOVIE_TMDB_ID else TEST_SERIES_TMDB_ID
        val mediaType = if (movie) "movie" else "tv"
        val season = if (movie) null else 1
        val episode = if (movie) null else 1
        diagnostics.addStep("Scraper: ${scraper.name} v${scraper.version}")
        diagnostics.addStep(
            if (movie) "Request: movie, TMDB $tmdbId" else "Request: series S01E01, TMDB $tmdbId"
        )
        val started = System.nanoTime()
        var error: String? = null
        val results = try {
            scraperSemaphore.withPermit {
                runScraper(scraper, tmdbId, mediaType, season, episode, diagnostics, propagateFailure = true)
            }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException && currentCoroutineContext().isActive) {
                error = "Timed out after ${PLUGIN_TIMEOUT_MS / 1000}s"
                emptyList()
            } else {
                throw e
            }
        } catch (e: Exception) {
            error = e.message?.take(300) ?: e.javaClass.simpleName
            emptyList()
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        error?.let { diagnostics.addStep("Failed: $it") }
        diagnostics.addStep("Result: ${results.size} stream${if (results.size == 1) "" else "s"} in ${elapsedMs}ms")
        return ScraperTestReport(scraper, results, diagnostics, elapsedMs, error)
    }

    private suspend fun runScraper(
        scraper: ScraperInfo,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        diagnostics: TestDiagnostics?,
        propagateFailure: Boolean = false
    ): List<LocalScraperResult> {
        val code = store.getScraperCode(scraper.id)
        if (code.isNullOrBlank()) {
            diagnostics?.addStep("No script stored for this scraper; refresh its repository")
            if (propagateFailure) throw PluginRepositoryException("No script stored for this scraper; refresh its repository")
            return emptyList()
        }
        // Only what the manifest asked for reaches the script, even if an older version stored more.
        val declared = scraper.settings.mapTo(HashSet()) { it.key }
        val settings = store.getScraperSettings(scraper.id).filterKeys { it in declared }
        diagnostics?.addStep("Script: ${code.length} chars, sha256 ${sha256Hex(code).take(12)}")
        val results = try {
            // The runner's own timeout fires first; this outer bound only covers a runner that hangs.
            withTimeout(PLUGIN_TIMEOUT_MS + 5_000L) {
                withContext(pluginDispatcher) {
                    runner.executePlugin(
                        code = code,
                        tmdbId = tmdbId,
                        mediaType = jsPluginMediaType(mediaType, scraper.supportedTypes, season, episode),
                        season = season,
                        episode = episode,
                        scraperId = scraper.id,
                        scraperSettings = settings,
                        diagnostics = diagnostics,
                        timeoutMs = PLUGIN_TIMEOUT_MS
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Distinguish this scraper's own timeout from the caller being cancelled.
            if (!currentCoroutineContext().isActive) throw e
            Log.w(TAG, "Scraper ${scraper.name} timed out")
            if (propagateFailure) throw e
            return emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Scraper ${scraper.name} failed: ${e.javaClass.simpleName}")
            if (propagateFailure) throw PluginRuntimeException(e.message ?: e.javaClass.simpleName, e)
            return emptyList()
        }
        return results
            .distinctBy { it.url }
            .take(MAX_RESULT_ITEMS)
            .map { it.copy(provider = scraper.name) }
    }

    // ---- repository download ----

    /** What a manifest may ask for: a few well-formed keys with short labels; the rest is dropped. */
    private fun sanitizeSettingFields(fields: List<ScraperSettingField>): List<ScraperSettingField> =
        fields.asSequence()
            .filter { it.key.matches(SETTING_KEY) && it.label.isNotBlank() }
            .distinctBy { it.key }
            .take(MAX_SETTING_FIELDS)
            .map {
                ScraperSettingField(
                    key = it.key,
                    label = it.label.trim().take(60),
                    description = it.description?.trim()?.take(200)?.takeIf { d -> d.isNotEmpty() },
                    placeholder = it.placeholder?.trim()?.take(80)?.takeIf { p -> p.isNotEmpty() }
                )
            }
            .toList()

    private fun requireWritable() {
        if (store.isReadOnlyProfile()) throw PluginRepositoryException("This profile uses the primary profile's plugins")
    }

    /** What the user typed, as the manifest URL it names. Throws [PluginRepositoryException] when it names none. */
    internal fun canonicalManifestUrl(input: String): HttpUrl {
        var text = input.trim()
        if (text.isEmpty()) throw PluginRepositoryException("Enter a repository URL")
        if (!text.contains("://")) text = "https://$text"
        val parsed = text.toHttpUrlOrNull() ?: throw PluginRepositoryException("That is not a valid URL")
        if (!PluginNetworkGuard.isAllowedRepositoryUrl(parsed)) {
            throw PluginRepositoryException("Repositories must use https (http only on your own network)")
        }
        // The manifest is the last path segment: a query string stays a query string, a fragment is dropped.
        val segments = parsed.encodedPathSegments.filter { it.isNotEmpty() }
        val builder = parsed.newBuilder().fragment(null).encodedPath("/" + segments.joinToString("/"))
        if (!segments.lastOrNull().equals(MANIFEST_FILE, ignoreCase = true)) builder.addPathSegment(MANIFEST_FILE)
        return builder.build()
    }

    private fun normalizeKey(url: String): String = url.trim().trimEnd('/').lowercase()

    private fun fetchManifest(url: HttpUrl): PluginManifest {
        val body = fetchText(url, MAX_MANIFEST_BYTES)
        val manifest = try {
            manifestJson.decodeFromString<PluginManifest>(body)
        } catch (e: Exception) {
            throw PluginRepositoryException("This is not a Nuvio plugin manifest")
        }
        if (manifest.name.isBlank()) throw PluginRepositoryException("The manifest has no name")
        if (manifest.scrapers.size > MAX_SCRAPERS_PER_REPOSITORY) {
            throw PluginRepositoryException("The manifest lists more than $MAX_SCRAPERS_PER_REPOSITORY scrapers")
        }
        // Unusable entries are dropped; the rest are what the repository offers.
        val usable = manifest.scrapers
            .filter { it.id.isNotBlank() && it.id.length <= 100 && it.name.isNotBlank() && it.filename.isNotBlank() }
            .distinctBy { it.id }
        return manifest.copy(scrapers = usable)
    }

    private class DownloadOutcome(val scrapers: List<ScraperInfo>, val failed: Int)

    private suspend fun downloadScrapers(
        repoId: String,
        manifestUrl: HttpUrl,
        infos: List<ScraperManifestInfo>,
        previous: List<ScraperInfo>
    ): DownloadOutcome = coroutineScope {
        val gate = Semaphore(MAX_PARALLEL_DOWNLOADS)
        val installed = infos.map { info ->
            async {
                gate.withPermit {
                    try {
                        val scriptUrl = manifestUrl.resolve(info.filename)
                            ?.takeIf { PluginNetworkGuard.isAllowedRepositoryUrl(it) }
                            ?: return@withPermit null
                        val code = fetchText(scriptUrl, MAX_SCRIPT_BYTES)
                        if (code.isBlank()) return@withPermit null
                        val scraperId = "$repoId:${info.id}"
                        store.saveScraperCode(scraperId, code)
                        val before = previous.firstOrNull { it.id == scraperId }
                        ScraperInfo(
                            id = scraperId,
                            repositoryId = repoId,
                            name = info.name.trim(),
                            description = info.description?.trim().orEmpty(),
                            version = info.version,
                            filename = info.filename,
                            supportedTypes = info.supportedTypes.ifEmpty { listOf("movie", "tv") },
                            // Explicit opt-in: a new scraper never runs until the user switches it on.
                            enabled = (before?.enabled ?: false) && info.enabled,
                            manifestEnabled = info.enabled,
                            logo = info.logo?.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) },
                            contentLanguage = info.contentLanguage.orEmpty(),
                            formats = info.formats,
                            settings = sanitizeSettingFields(info.settings)
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Scraper ${info.id} not downloaded: ${e.javaClass.simpleName}")
                        null
                    }
                }
            }
        }.awaitAll()
        DownloadOutcome(installed.filterNotNull(), installed.count { it == null })
    }

    /** Body of [url] as text, refusing a response over [maxBytes] without reading past it. */
    private fun fetchText(url: HttpUrl, maxBytes: Long): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw PluginRepositoryException("The server answered HTTP ${response.code}")
            // A redirect must not lead somewhere a repository could not have been installed from.
            if (!PluginNetworkGuard.isAllowedRepositoryUrl(response.request.url)) {
                throw PluginRepositoryException("The repository redirected to an address that is not allowed")
            }
            val body = response.body
            if (body.contentLength() > maxBytes) throw PluginRepositoryException("The file is too large")
            val source = body.source()
            source.request(maxBytes + 1)
            if (source.buffer.size > maxBytes) throw PluginRepositoryException("The file is too large")
            return source.readUtf8()
        }
    }

    private fun Exception.asRepositoryException(): PluginRepositoryException = when (this) {
        is PluginRepositoryException -> this
        is java.net.UnknownHostException -> PluginRepositoryException("The server could not be reached")
        is java.net.SocketTimeoutException -> PluginRepositoryException("The server did not answer in time")
        is java.io.IOException -> PluginRepositoryException("Download failed: ${message ?: javaClass.simpleName}")
        else -> PluginRepositoryException(message ?: javaClass.simpleName)
    }

    private fun runnableScrapers(scrapers: List<ScraperInfo>, repositories: List<PluginRepository>): List<ScraperInfo> {
        val enabledRepoIds = repositories.filter { it.enabled }.mapTo(HashSet()) { it.id }
        return scrapers.filter { it.enabled && it.repositoryId in enabledRepoIds }
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** A scraper script that threw or returned something unusable. */
class PluginRuntimeException(message: String, cause: Throwable? = null) : Exception(message, cause)
