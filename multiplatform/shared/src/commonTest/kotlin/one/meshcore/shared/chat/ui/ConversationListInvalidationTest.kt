package one.meshcore.shared.chat.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Ingest, block, and delete bumps reload the chats list. A preview read fault still leaves the next
 * bump live. Cancellation and an error stop that collector.
 */
class ConversationListInvalidationTest {
    @Test
    fun twoBumpsReloadAndPrewarm() = runTest {
        val reloads = Counter()
        val prewarms = Counter()
        collectConversationListInvalidations(
            twoBumps(),
            reload = { reloads.increment() },
            prewarm = { prewarms.increment() },
        )
        assertEquals(2, reloads.value)
        assertEquals(2, prewarms.value)
    }

    @Test
    fun aPreviewFaultStillReloadsTheNextBump() = runTest {
        val reloads = Counter()
        val prewarms = Counter()
        collectConversationListInvalidations(
            twoBumps(),
            reload = { reloads.increment() },
            prewarm = {
                prewarms.increment()
                if (prewarms.value == 1) error("preview locked")
            },
        )
        assertEquals(2, reloads.value)
        assertEquals(2, prewarms.value)
    }

    @Test
    fun aCancelledPreviewReadDoesNotReloadTheNextBump() = runTest {
        val reloads = Counter()
        val prewarms = Counter()
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            collectConversationListInvalidations(
                twoBumps(),
                reload = { reloads.increment() },
                prewarm = {
                    prewarms.increment()
                    if (prewarms.value == 1) throw cancelled
                },
            )
        }
        assertSame(cancelled, thrown)
        assertEquals(1, reloads.value)
        assertEquals(1, prewarms.value)
    }

    @Test
    fun aPreviewReadErrorDoesNotReloadTheNextBump() = runTest {
        val reloads = Counter()
        val prewarms = Counter()
        val probe = PreviewReadProbeError()
        val thrown = assertFailsWith<PreviewReadProbeError> {
            collectConversationListInvalidations(
                twoBumps(),
                reload = { reloads.increment() },
                prewarm = {
                    prewarms.increment()
                    if (prewarms.value == 1) throw probe
                },
            )
        }
        assertSame(probe, thrown)
        assertEquals(1, reloads.value)
        assertEquals(1, prewarms.value)
    }

    private fun twoBumps() = flow {
        emit(Unit)
        emit(Unit)
    }
}

private class Counter {
    var value: Int = 0
        private set

    fun increment() {
        value += 1
    }
}

private class PreviewReadProbeError : Error("probe")
