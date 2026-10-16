package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Discover clear faults. A Room exception sets the fallback and keeps the rows in the existing state
 * tests. Cancellation and a thrown error keep the rows and the thrown instance.
 */
class DiscoveryClearFaultTest {
    @Test
    fun aCancelledClearRethrowsTheSameExceptionAndKeepsTheRows() = runTest {
        val cancelled = CancellationException("superseded")
        val backing = seededClearStore()
        val vm = clearViewModel(
            backgroundScope,
            object : DiscoveredNodeStore by backing {
                override suspend fun clear(radioID: Uuid) = throw cancelled
            },
        )
        vm.load()

        val thrown = assertFailsWith<CancellationException> { vm.clearAll() }

        assertSame(cancelled, thrown)
        assertEquals(listOf("Alpha"), vm.state.value.nodes.map { it.name })
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun aClearErrorRethrowsTheSameErrorAndKeepsTheRows() = runTest {
        val failure = DiscoverClearProbeError()
        val backing = seededClearStore()
        val vm = clearViewModel(
            backgroundScope,
            object : DiscoveredNodeStore by backing {
                override suspend fun clear(radioID: Uuid) = throw failure
            },
        )
        vm.load()

        val thrown = assertFailsWith<DiscoverClearProbeError> { vm.clearAll() }

        assertSame(failure, thrown)
        assertEquals(listOf("Alpha"), vm.state.value.nodes.map { it.name })
        assertNull(vm.state.value.errorMessage)
    }
}

private class DiscoverClearProbeError : Error("probe")

private val clearRadio = ContactTestIds.RADIO

private suspend fun seededClearStore(): InMemoryDiscoveredNodeStore {
    val store = InMemoryDiscoveredNodeStore()
    store.upsert(
        DiscoveredNodeRecord(
            id = newDiscoveredNodeId(),
            radioID = clearRadio,
            publicKey = publicKey(0x01),
            name = "Alpha",
            typeRawValue = ContactType.CHAT.raw,
            lastHeardEpochSeconds = 1,
        ),
    )
    return store
}

private fun clearViewModel(scope: CoroutineScope, store: DiscoveredNodeStore) = DiscoveryViewModel(
    scope = scope,
    dependencies = DiscoveryViewModel.Dependencies(
        store = { store },
        contactService = { null },
        radioId = { clearRadio },
        loadFailedMessage = { DISCOVERY_LOAD_FAILED_MESSAGE },
        errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
    ),
)
