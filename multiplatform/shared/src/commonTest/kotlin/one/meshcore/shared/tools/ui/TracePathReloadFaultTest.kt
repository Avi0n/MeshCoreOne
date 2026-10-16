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
 * A failed saved-path reload still reports an empty list. Cancellation and an error leave the reload.
 */
class TracePathReloadFaultTest {
    private class ReloadProbeError : Error("probe")

    @Test
    fun aReloadFaultClearsTheListAndReportsFailure() = runTest {
        val loaded = mutableListOf<List<SavedTracePathDto>>()
        var failed = false
        reloadTracePathScreenSaved(
            store = savedTracePathsStore { error("db locked") },
            radio = ChatFixtures.RADIO,
            onLoaded = { loaded += it },
            onFailed = { failed = true },
        )

        assertEquals(1, loaded.size)
        assertTrue(loaded.single().isEmpty())
        assertTrue(failed)
    }

    @Test
    fun aCancelledReloadPropagates() = runTest {
        val loaded = mutableListOf<List<SavedTracePathDto>>()
        var failed = false
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            reloadTracePathScreenSaved(
                store = savedTracePathsStore { throw cancelled },
                radio = ChatFixtures.RADIO,
                onLoaded = { loaded += it },
                onFailed = { failed = true },
            )
        }

        assertSame(cancelled, thrown)
        assertTrue(loaded.isEmpty())
        assertFalse(failed)
    }

    @Test
    fun aReloadErrorPropagates() = runTest {
        val loaded = mutableListOf<List<SavedTracePathDto>>()
        var failed = false
        val fault = ReloadProbeError()
        val thrown = assertFailsWith<ReloadProbeError> {
            reloadTracePathScreenSaved(
                store = savedTracePathsStore { throw fault },
                radio = ChatFixtures.RADIO,
                onLoaded = { loaded += it },
                onFailed = { failed = true },
            )
        }

        assertSame(fault, thrown)
        assertTrue(loaded.isEmpty())
        assertFalse(failed)
    }
}
