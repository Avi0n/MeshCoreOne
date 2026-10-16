package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Discover load faults. A Room exception becomes the fallback copy in the existing state tests.
 * Cancellation and a thrown error leave the list loading and keep the thrown instance.
 */
class DiscoveryLoadFaultTest {
    @Test
    fun aLoadFaultUsesTheLatestLocalizedCopy() = runTest {
        var message = "first language"
        val vm = loadViewModel(backgroundScope, loadFailedMessage = { message }) { error("db locked") }

        vm.load()
        assertEquals("first language", vm.state.value.errorMessage)

        message = "second language"
        vm.load()
        assertEquals("second language", vm.state.value.errorMessage)
    }

    @Test
    fun aCancelledLoadRethrowsTheSameException() = runTest {
        val cancelled = CancellationException("superseded")
        val vm = loadViewModel(backgroundScope) { throw cancelled }
        val thrown = assertFailsWith<CancellationException> { vm.load() }

        assertSame(cancelled, thrown)
        assertTrue(vm.state.value.isLoading)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun aLoadErrorRethrowsTheSameError() = runTest {
        val failure = DiscoverLoadProbeError()
        val vm = loadViewModel(backgroundScope) { throw failure }
        val thrown = assertFailsWith<DiscoverLoadProbeError> { vm.load() }

        assertSame(failure, thrown)
        assertTrue(vm.state.value.isLoading)
        assertNull(vm.state.value.errorMessage)
    }
}

private class DiscoverLoadProbeError : Error("probe")

private fun loadViewModel(
    scope: CoroutineScope,
    loadFailedMessage: () -> String = { DISCOVERY_LOAD_FAILED_MESSAGE },
    onList: () -> Nothing,
) = DiscoveryViewModel(
    scope = scope,
    dependencies = DiscoveryViewModel.Dependencies(
        store = {
            object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
                override suspend fun list(radioID: Uuid): List<DiscoveredNodeRecord> = onList()
            }
        },
        contactService = { null },
        radioId = { ContactTestIds.RADIO },
        loadFailedMessage = loadFailedMessage,
        errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
    ),
)
