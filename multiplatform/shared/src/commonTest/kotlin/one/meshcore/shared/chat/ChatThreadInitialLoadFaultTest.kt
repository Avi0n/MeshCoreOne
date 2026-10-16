package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Ordinary fetch faults settle with localized copy. Cancellation and propagated errors keep
 * first-open staging available for a retry without claiming notification readiness.
 */
class ChatThreadInitialLoadFaultTest {
    @Test
    fun aFetchFaultUsesTheLatestLocalizedCopy() = runTest {
        var messages = ChatErrorMessages.developerEnglish().copy(loadMessagesFailed = "First locale")
        val store = FakeChatThreadStore().apply { fetchFailure = IllegalStateException("disk") }
        val (viewModel, _) = makeThreadViewModel(store = store, errorMessages = { messages })

        viewModel.loadInitial()
        assertEquals("First locale", viewModel.errorMessage)

        messages = messages.copy(loadMessagesFailed = "Second locale")
        viewModel.loadInitial()

        assertEquals("Second locale", viewModel.errorMessage)
    }

    @Test
    fun aFetchFaultSurfacesTheLoadError() = runTest {
        val store = FakeChatThreadStore().apply { fetchFailure = IllegalStateException("disk") }
        val (viewModel, coordinator) = makeThreadViewModel(store = store)

        viewModel.loadInitial()

        assertEquals(ChatErrorMessages.developerEnglish().loadMessagesFailed, viewModel.errorMessage)
        assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
        assertTrue(viewModel.initialLoadSettled)
        assertEquals(0, store.clearUnreadCalls)
    }

    @Test
    fun aCancelledFetchKeepsStagingWithoutTheLoadError() = runTest {
        val stopped = CancellationException("stopped")
        val store = FakeChatThreadStore().apply { fetchFailure = stopped }
        val (viewModel, coordinator) = makeThreadViewModel(store = store)

        val cancelled = assertFailsWith<CancellationException> {
            viewModel.loadInitial()
        }

        assertSame(stopped, cancelled)
        assertNull(viewModel.errorMessage)
        assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
        assertFalse(viewModel.initialLoadSettled)
        assertNull(viewModel.renderState.value.completedInitialLoadGeneration)
        assertEquals(0, store.clearUnreadCalls)
    }

    @Test
    fun aFetchErrorKeepsStagingWithoutTheLoadError() = runTest {
        val probe = InitialLoadProbeError()
        val store = FakeChatThreadStore().apply { fetchFailure = probe }
        val (viewModel, coordinator) = makeThreadViewModel(store = store)

        val thrown = assertFailsWith<InitialLoadProbeError> {
            viewModel.loadInitial()
        }

        assertSame(probe, thrown)

        assertNull(viewModel.errorMessage)
        assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
        assertFalse(viewModel.initialLoadSettled)
        assertNull(viewModel.renderState.value.completedInitialLoadGeneration)
        assertEquals(0, store.clearUnreadCalls)
    }
}

private class InitialLoadProbeError : Error("probe")
