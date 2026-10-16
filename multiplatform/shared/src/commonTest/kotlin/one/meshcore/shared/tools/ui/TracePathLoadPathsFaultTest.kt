package one.meshcore.shared.tools.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.chat.ChatFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The screen reload still publishes an empty list after a store fault, and reports that fault.
 * Cancellation and an error leave the load.
 */
class TracePathLoadPathsFaultTest {
    private class LoadPathsProbeError : Error("probe")

    @Test
    fun aCancelledPathListPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        var failed = false
        val thrown = assertFailsWith<CancellationException> {
            reloadTracePathScreenSaved(
                savedTracePathsStore { throw cancelled },
                ChatFixtures.RADIO,
                onLoaded = { error("loaded after cancel") },
                onFailed = { failed = true },
            )
        }

        assertSame(cancelled, thrown)
        assertFalse(failed)
    }

    @Test
    fun aPathListErrorPropagates() = runTest {
        val fault = LoadPathsProbeError()
        var failed = false
        val thrown = assertFailsWith<LoadPathsProbeError> {
            reloadTracePathScreenSaved(
                savedTracePathsStore { throw fault },
                ChatFixtures.RADIO,
                onLoaded = { error("loaded after error") },
                onFailed = { failed = true },
            )
        }

        assertSame(fault, thrown)
        assertFalse(failed)
    }

    @Test
    fun aPathListStoreFaultReturnsEmpty() = runTest {
        var loaded: List<SavedTracePathDto>? = null
        var failed = false
        reloadTracePathScreenSaved(
            savedTracePathsStore { error("store locked") },
            ChatFixtures.RADIO,
            onLoaded = { loaded = it },
            onFailed = { failed = true },
        )

        assertEquals(emptyList(), loaded)
        assertTrue(failed)
    }
}
