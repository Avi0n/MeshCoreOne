package one.meshcore.shared.tools.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.trace.InMemorySavedTracePathStore
import one.meshcore.shared.trace.SavedTracePathStore
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A failed saved-path detail still returns null. Cancellation and an error leave the load.
 */
class TracePathLoadPathFaultTest {
    private class LoadPathProbeError : Error("probe")

    @Test
    fun aCancelledPathDetailPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            loadSavedTracePath(store { throw cancelled }, ChatFixtures.RADIO)
        }

        assertSame(cancelled, thrown)
    }

    @Test
    fun aPathDetailErrorPropagates() = runTest {
        val fault = LoadPathProbeError()
        val thrown = assertFailsWith<LoadPathProbeError> {
            loadSavedTracePath(store { throw fault }, ChatFixtures.RADIO)
        }

        assertSame(fault, thrown)
    }

    @Test
    fun aPathDetailStoreFaultReturnsNull() = runTest {
        assertNull(loadSavedTracePath(store { error("store locked") }, ChatFixtures.RADIO))
    }

    private fun store(fetch: suspend (Uuid) -> SavedTracePathDto?): SavedTracePathStore {
        val backing = InMemorySavedTracePathStore()
        return object : SavedTracePathStore by backing {
            override suspend fun fetchSavedTracePath(id: Uuid): SavedTracePathDto? = fetch(id)
        }
    }
}
