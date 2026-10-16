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
 * Discover delete faults. A Room exception sets the fallback and keeps the row in the existing state
 * tests. Cancellation and a thrown error keep the row and the thrown instance.
 */
class DiscoveryDeleteFaultTest {
    @Test
    fun aCancelledDeleteRethrowsTheSameExceptionAndKeepsTheRow() = runTest {
        val cancelled = CancellationException("superseded")
        val backing = seededDeleteStore()
        val vm = deleteViewModel(
            backgroundScope,
            object : DiscoveredNodeStore by backing {
                override suspend fun remove(radioID: Uuid, publicKey: ByteArray) = throw cancelled
            },
        )
        vm.load()

        val thrown = assertFailsWith<CancellationException> { vm.deleteNode(vm.state.value.nodes.single()) }

        assertSame(cancelled, thrown)
        assertEquals(listOf("Alpha"), vm.state.value.nodes.map { it.name })
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun aDeleteErrorRethrowsTheSameErrorAndKeepsTheRow() = runTest {
        val failure = DiscoverDeleteProbeError()
        val backing = seededDeleteStore()
        val vm = deleteViewModel(
            backgroundScope,
            object : DiscoveredNodeStore by backing {
                override suspend fun remove(radioID: Uuid, publicKey: ByteArray) = throw failure
            },
        )
        vm.load()

        val thrown = assertFailsWith<DiscoverDeleteProbeError> { vm.deleteNode(vm.state.value.nodes.single()) }

        assertSame(failure, thrown)
        assertEquals(listOf("Alpha"), vm.state.value.nodes.map { it.name })
        assertNull(vm.state.value.errorMessage)
    }
}

private class DiscoverDeleteProbeError : Error("probe")

private val deleteRadio = ContactTestIds.RADIO

private suspend fun seededDeleteStore(): InMemoryDiscoveredNodeStore {
    val store = InMemoryDiscoveredNodeStore()
    store.upsert(
        DiscoveredNodeRecord(
            id = newDiscoveredNodeId(),
            radioID = deleteRadio,
            publicKey = publicKey(0x01),
            name = "Alpha",
            typeRawValue = ContactType.CHAT.raw,
            lastHeardEpochSeconds = 1,
        ),
    )
    return store
}

private fun deleteViewModel(scope: CoroutineScope, store: DiscoveredNodeStore) = DiscoveryViewModel(
    scope = scope,
    dependencies = DiscoveryViewModel.Dependencies(
        store = { store },
        contactService = { null },
        radioId = { deleteRadio },
        loadFailedMessage = { DISCOVERY_LOAD_FAILED_MESSAGE },
        errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
    ),
)
