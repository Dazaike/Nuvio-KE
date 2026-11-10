package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Leaving a search abandons the plugin work that nobody waits for, without caching a partial answer. */
class StreamSearchSessionObserversTest {

    private fun key() = StreamSearchRequestKey(1, "movie", "tt1", null, null, "cfg")

    private fun group(name: String) = NetworkResult.Success(listOf(AddonStreams(name, null, emptyList())))

    @Test
    fun `the session counts the collectors watching it`() = runBlocking {
        val cache = StreamSearchSessionCache()
        val gate = CompletableDeferred<Unit>()
        var control: StreamSearchControl? = null
        val producer: (StreamSearchControl) -> Flow<NetworkResult<List<AddonStreams>>> = { c ->
            control = c
            flow {
                emit(group("a"))
                gate.await()
            }
        }

        val first = launch(Dispatchers.IO) { cache.observe(key(), false, producer).toList() }
        withTimeout(5_000) { while (control?.observers?.value != 1) delay(5) }
        val second = launch(Dispatchers.IO) { cache.observe(key(), false, producer).toList() }
        withTimeout(5_000) { while (control?.observers?.value != 2) delay(5) }

        first.cancelAndJoin()
        withTimeout(5_000) { while (control?.observers?.value != 1) delay(5) }
        gate.complete(Unit)
        second.join()
        assertEquals(0, control!!.observers.value)
    }

    @Test
    fun `work nobody watches is abandoned after the grace and the session is not cached`() = runBlocking {
        val cache = StreamSearchSessionCache()
        val runs = AtomicInteger()
        val abandoned = CompletableDeferred<Boolean?>()
        val producer: (StreamSearchControl) -> Flow<NetworkResult<List<AddonStreams>>> = { control ->
            flow {
                val run = runs.incrementAndGet()
                emit(group("addon"))
                // Only the first search lingers; the repeat finishes at once.
                if (run == 1) abandoned.complete(runWhileObserved(control, graceMs = 150) { awaitCancellation() })
            }
        }

        // A prefetch-like collector: takes the first answer and leaves.
        cache.observe(key(), false, producer).first { it is NetworkResult.Success }
        assertNull(withTimeout(5_000) { abandoned.await() })

        // The cut-short session is gone: a later visit searches again instead of reading a partial answer.
        withTimeout(5_000) { cache.observe(key(), false, producer).toList() }
        assertEquals(2, runs.get())
    }

    @Test
    fun `a collector that arrives within the grace keeps the work running`() = runBlocking {
        val observers = MutableStateFlow(1)
        val control = StreamSearchControl(observers) { error("must not be cut short") }
        val finished = async(Dispatchers.IO) {
            runWhileObserved(control, graceMs = 400) { delay(900); "done" }
        }

        delay(50)
        observers.value = 0      // the screen left ...
        delay(150)
        observers.value = 1      // ... and a new one opened inside the grace

        assertEquals("done", withTimeout(5_000) { finished.await() })
    }

    @Test
    fun `work that finishes while watched returns its value and is not marked incomplete`() = runBlocking {
        var marked = false
        val control = StreamSearchControl(MutableStateFlow(1)) { marked = true }

        val value = runWhileObserved(control, graceMs = 50) { "result" }

        assertEquals("result", value)
        assertFalse(marked)
    }

    @Test
    fun `cancelling the caller still cancels the work and propagates`() = runBlocking {
        val control = StreamSearchControl(MutableStateFlow(1)) {}
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            runWhileObserved(control, graceMs = 10_000) { started.complete(Unit); awaitCancellation() }
        }
        started.await()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }
}
