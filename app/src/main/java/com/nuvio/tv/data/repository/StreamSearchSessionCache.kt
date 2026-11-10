package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class StreamSearchRequestKey(
    val profileId: Int,
    val type: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val sourceConfiguration: String
)

/**
 * What a search's producer may know about the session it runs in: how many collectors are watching
 * it ([observers], a prefetch counts while it collects), and a way to say the work was cut short
 * ([markIncomplete]: the session is then dropped on completion instead of cached, so a later visit
 * searches again instead of showing a partial answer).
 */
internal class StreamSearchControl(
    val observers: StateFlow<Int>,
    private val onIncomplete: () -> Unit
) {
    fun markIncomplete() = onIncomplete()
}

/**
 * Runs [block] until it ends, but abandons it when no collector has watched the search for
 * [graceMs] (the user left the stream screen and no prefetch is waiting): returns null then, after
 * [StreamSearchControl.markIncomplete]. Another collector arriving within the grace keeps it running.
 */
internal suspend fun <T> runWhileObserved(
    control: StreamSearchControl,
    graceMs: Long,
    block: suspend () -> T
): T? = coroutineScope {
    val work = async { block() }
    val watcher = launch {
        control.observers.collectLatest { count ->
            if (count <= 0) {
                delay(graceMs)
                control.markIncomplete()
                work.cancel()
            }
        }
    }
    try {
        work.await()
    } catch (e: CancellationException) {
        // Our own cancel of the work is an answer of null; the caller being cancelled is not.
        if (currentCoroutineContext().isActive && work.isCancelled) null else throw e
    } finally {
        watcher.cancel()
    }
}

/**
 * Keeps a small set of source searches alive independently of UI collectors.
 * Completed results are memory-only and short-lived because addon URLs may expire.
 */
internal class StreamSearchSessionCache(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val completedTtlMs: Long = DEFAULT_COMPLETED_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    private data class Snapshot(
        val result: NetworkResult<List<AddonStreams>>,
        val version: Long,
        val isComplete: Boolean
    )

    private class Session(
        val state: MutableStateFlow<Snapshot>
    ) {
        lateinit var job: Job
        /** Collectors currently watching this session. */
        val observers = MutableStateFlow(0)
        @Volatile var incomplete: Boolean = false
        @Volatile var invalidated: Boolean = false
        var completedAtMs: Long? = null
        var lastSuccessfulResult: NetworkResult.Success<List<AddonStreams>>? = null
    }

    private val mutex = Mutex()
    private val sessions = LinkedHashMap<StreamSearchRequestKey, Session>(16, 0.75f, true)

    fun observe(
        key: StreamSearchRequestKey,
        forceRefresh: Boolean,
        producer: (StreamSearchControl) -> Flow<NetworkResult<List<AddonStreams>>>
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        // acquireSession counts this collector in before the search can start.
        val session = acquireSession(key, forceRefresh, producer)
        try {
            var emittedVersion = -1L
            emitAll(
                session.state.transformWhile { snapshot ->
                    if (snapshot.version != emittedVersion) {
                        emittedVersion = snapshot.version
                        emit(snapshot.result)
                    }
                    !snapshot.isComplete
                }
            )
        } finally {
            session.observers.update { it - 1 }
        }
    }

    private suspend fun acquireSession(
        key: StreamSearchRequestKey,
        forceRefresh: Boolean,
        producer: (StreamSearchControl) -> Flow<NetworkResult<List<AddonStreams>>>
    ): Session {
        var created: Session? = null
        val selected = mutex.withLock {
            removeExpiredLocked()
            removeObsoleteSessionsLocked(key)

            if (forceRefresh) {
                sessions.remove(key)?.cancelAndComplete()
            } else {
                sessions[key]?.let { existing ->
                    existing.observers.update { it + 1 }
                    return@withLock existing
                }
            }

            createSession(key, producer).also { session ->
                session.observers.update { it + 1 }
                sessions[key] = session
                trimToSizeLocked()
                created = session
            }
        }
        withContext(NonCancellable) {
            created?.job?.start()
        }
        return selected
    }

    private fun createSession(
        key: StreamSearchRequestKey,
        producer: (StreamSearchControl) -> Flow<NetworkResult<List<AddonStreams>>>
    ): Session {
        val session = Session(
            MutableStateFlow(
                Snapshot(
                    result = NetworkResult.Loading,
                    version = 0L,
                    isComplete = false
                )
            )
        )
        session.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                producer(StreamSearchControl(session.observers) { session.incomplete = true }).collect { result ->
                    if (result is NetworkResult.Success && result.data.isNotEmpty()) {
                        session.lastSuccessfulResult = result
                    }
                    session.state.update { current ->
                        Snapshot(
                            result = result,
                            version = current.version + 1,
                            isComplete = false
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                session.state.update { current ->
                    Snapshot(
                        result = NetworkResult.Error(error.message ?: "Failed to fetch streams"),
                        version = current.version + 1,
                        isComplete = false
                    )
                }
            } finally {
                completeSession(key, session)
            }
        }
        return session
    }

    private suspend fun completeSession(key: StreamSearchRequestKey, session: Session) {
        if (session.invalidated) return
        session.state.update { current ->
            val lastSuccess = session.lastSuccessfulResult
            if (lastSuccess != null && current.result !is NetworkResult.Success) {
                Snapshot(
                    result = lastSuccess,
                    version = current.version + 1,
                    isComplete = true
                )
            } else {
                current.copy(isComplete = true)
            }
        }
        mutex.withLock {
            if (sessions[key] !== session) return@withLock
            // A search cut short (nobody was watching) is not kept: the next visit searches afresh.
            if (session.lastSuccessfulResult != null && !session.incomplete) {
                session.completedAtMs = nowMs()
            } else {
                sessions.remove(key)
            }
        }
    }

    private fun removeObsoleteSessionsLocked(requestedKey: StreamSearchRequestKey) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val (existingKey, session) = iterator.next()
            val belongsToAnotherProfile = existingKey.profileId != requestedKey.profileId
            val sourceConfigurationChanged = existingKey.matchesMediaRequest(requestedKey) &&
                existingKey.sourceConfiguration != requestedKey.sourceConfiguration
            if (belongsToAnotherProfile || sourceConfigurationChanged) {
                iterator.remove()
                session.cancelAndComplete()
            }
        }
    }

    private fun removeExpiredLocked() {
        val now = nowMs()
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next().value
            val completedAt = session.completedAtMs ?: continue
            if (now - completedAt >= completedTtlMs) {
                iterator.remove()
                session.cancelAndComplete()
            }
        }
    }

    private fun trimToSizeLocked() {
        while (sessions.size > maxEntries) {
            val iterator = sessions.entries.iterator()
            if (!iterator.hasNext()) return
            val session = iterator.next().value
            iterator.remove()
            session.cancelAndComplete()
        }
    }

    private fun Session.cancelAndComplete() {
        invalidated = true
        state.update { current -> current.copy(isComplete = true) }
        job.cancel()
    }

    private fun StreamSearchRequestKey.matchesMediaRequest(other: StreamSearchRequestKey): Boolean =
        profileId == other.profileId &&
            type == other.type &&
            videoId == other.videoId &&
            season == other.season &&
            episode == other.episode

    private companion object {
        const val DEFAULT_COMPLETED_TTL_MS = 15 * 60 * 1_000L
        const val DEFAULT_MAX_ENTRIES = 12
    }
}
