package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * An older-page fault raises the banner and leaves the loaded page in place. Cancellation and an
 * error leave the banner clear and stop the page.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadOlderPageFaultTest {
    @Test
    fun aCancelledOlderPageLeavesTheBannerClear() = runTest {
        val store = FakeChatThreadStore(rows = incomingThreadRows(OLDER_PAGE_ROW_COUNT))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        val loaded = viewModel.renderState.value.items.size
        val stopped = CancellationException("stopped")
        store.fetchFailure = stopped

        val cancelled = assertFailsWith<CancellationException> {
            viewModel.loadOlderMessages()
        }
        runCurrent()

        assertSame(stopped, cancelled)
        val state = viewModel.renderState.value
        assertNull(state.errorBannerMessage)
        assertNull(state.errorMessage)
        assertFalse(state.isLoadingOlder)
        assertEquals(loaded, state.items.size)
    }

    @Test
    fun anOlderPageErrorLeavesTheBannerClear() = runTest {
        val store = FakeChatThreadStore(rows = incomingThreadRows(OLDER_PAGE_ROW_COUNT))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        val loaded = viewModel.renderState.value.items.size
        val probe = OlderPageProbeError()
        store.fetchFailure = probe

        val thrown = assertFailsWith<OlderPageProbeError> {
            viewModel.loadOlderMessages()
        }
        runCurrent()

        assertSame(probe, thrown)

        val state = viewModel.renderState.value
        assertNull(state.errorBannerMessage)
        assertNull(state.errorMessage)
        assertFalse(state.isLoadingOlder)
        assertEquals(loaded, state.items.size)
    }

    private companion object {
        const val OLDER_PAGE_ROW_COUNT = 60
    }
}

private class OlderPageProbeError : Error("probe")
