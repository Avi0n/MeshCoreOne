package one.meshcore.shared.urlsafety

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class BlocklistFetchFaultTest {
    @Test
    fun aCancelledFetchIsRethrown() = runBlocking {
        val cancelled = CancellationException("stop")
        val thrown = assertFailsWith<CancellationException> {
            runBlocklistFetch(onFailure = { "fallback" }) { throw cancelled }
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun anOrdinaryFetchFailureUsesTheFallback() = runBlocking {
        val result = runBlocklistFetch(onFailure = { null }) {
            throw IllegalStateException("offline")
        }
        assertNull(result)
    }

    @Test
    fun aFetchErrorPropagates() = runBlocking {
        val probe = BlocklistFetchProbeError()
        val thrown = assertFailsWith<BlocklistFetchProbeError> {
            runBlocklistFetch(onFailure = { "fallback" }) { throw probe }
        }
        assertSame(probe, thrown)
    }
}

private class BlocklistFetchProbeError : Error("probe")
