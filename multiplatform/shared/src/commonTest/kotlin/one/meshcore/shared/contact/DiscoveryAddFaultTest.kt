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
 * Discover add faults. A contact-service exception becomes [AddContactError] in the existing add tests.
 * Cancellation and a thrown error clear the busy flag and keep the thrown instance.
 */
class DiscoveryAddFaultTest {
    @Test
    fun aCancelledAddRethrowsTheSameExceptionAndClearsTheBusyFlag() = runTest {
        val cancelled = CancellationException("superseded")
        val session = FakeContactSession()
        val vm = addViewModel(backgroundScope, session) { throw cancelled }

        val thrown = assertFailsWith<CancellationException> { vm.addNode(addNodeRecord()) }

        assertSame(cancelled, thrown)
        assertNull(vm.state.value.addingNodeId)
        assertNull(vm.state.value.addError)
        assertEquals(1, session.addedContacts.size)
    }

    @Test
    fun anAddErrorRethrowsTheSameErrorAndClearsTheBusyFlag() = runTest {
        val failure = DiscoverAddProbeError()
        val session = FakeContactSession()
        val vm = addViewModel(backgroundScope, session) { throw failure }

        val thrown = assertFailsWith<DiscoverAddProbeError> { vm.addNode(addNodeRecord()) }

        assertSame(failure, thrown)
        assertNull(vm.state.value.addingNodeId)
        assertNull(vm.state.value.addError)
        assertEquals(1, session.addedContacts.size)
    }
}

private class DiscoverAddProbeError : Error("probe")

private val addRadio = ContactTestIds.RADIO

private fun addNodeRecord() = DiscoveredNodeRecord(
    id = newDiscoveredNodeId(),
    radioID = addRadio,
    publicKey = publicKey(0x11),
    name = "Alpha",
    typeRawValue = ContactType.CHAT.raw,
    lastHeardEpochSeconds = 1,
)

private fun addViewModel(scope: CoroutineScope, session: FakeContactSession, onList: () -> Nothing) =
    DiscoveryViewModel(
        scope = scope,
        dependencies = DiscoveryViewModel.Dependencies(
            store = {
                object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
                    override suspend fun list(radioID: Uuid): List<DiscoveredNodeRecord> = onList()
                }
            },
            contactService = { testContactService(session = session) },
            radioId = { addRadio },
            isRadioConnected = { true },
            loadFailedMessage = { DISCOVERY_LOAD_FAILED_MESSAGE },
            errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
        ),
    )
