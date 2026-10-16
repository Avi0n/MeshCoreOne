package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.contact.AddContactError
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactSession
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.FakeContactSession
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.InMemoryDiscoveredNodeStore
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Saving a discovered node reports the contact error and clears the spinner. Cancellation keeps
 * the thrown instance and leaves that error clear. A thrown error propagates.
 */
class NodeDiscoveryAddFaultTest {
    @Test
    fun aCancelledSaveKeepsTheThrownInstance() = runTest {
        val stopped = CancellationException("stopped")
        val session = FakeContactSession()
        val vm = addingViewModel(session) { _, _ -> throw stopped }

        val thrown = assertFailsWith<CancellationException> { vm.addNode(hilltop()) }

        assertSame(stopped, thrown)
        assertNull(vm.state.value.addError)
        assertNull(vm.state.value.addingPublicKeyHex)
        assertFalse(vm.state.value.isAdded(hilltop()))
    }

    @Test
    fun aSaveExceptionReportsTheAddFaultAndClearsTheSpinner() = runTest {
        val session = FakeContactSession()
        val vm = addingViewModel(session) { _, _ -> error("db locked") }

        vm.addNode(hilltop())

        val reported = vm.state.value.addError
        assertIs<AddContactError.ServiceMessage>(reported)
        assertEquals(NODE_DISCOVERY_LOCALIZED_ERROR, reported.message)
        assertNull(vm.state.value.addingPublicKeyHex)
        assertFalse(vm.state.value.isAdded(hilltop()))
        assertEquals(1, session.addedContacts.size)
    }

    @Test
    fun aSaveErrorPropagatesAndClearsTheSpinner() = runTest {
        val probe = AddDiscoverProbeError()
        val session = FakeContactSession()
        val vm = addingViewModel(session) { _, _ -> throw probe }

        val thrown = assertFailsWith<AddDiscoverProbeError> { vm.addNode(hilltop()) }

        assertSame(probe, thrown)
        assertNull(vm.state.value.addError)
        assertNull(vm.state.value.addingPublicKeyHex)
        assertFalse(vm.state.value.isAdded(hilltop()))
        assertEquals(1, session.addedContacts.size)
    }

    private fun addingViewModel(
        session: ContactSession,
        save: suspend (Uuid, ContactFrame) -> Uuid,
    ): NodeDiscoveryViewModel {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid = save(radioID, frame)
        }
        val service = ContactService(session = session, store = store, now = { ADDED_AT_EPOCH_SECONDS })
        return NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { _, _ -> 1L },
                events = { null },
                contactService = { service },
                discoveredNodeStore = { InMemoryDiscoveredNodeStore() },
                radioId = { RADIO_ID },
                isRadioConnected = { true },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
            nowEpochSeconds = { ADDED_AT_EPOCH_SECONDS },
        )
    }

    private fun hilltop() = NodeDiscoveryResult(
        name = "Hilltop",
        publicKey = ByteArray(PUBLIC_KEY_LENGTH) { HILLTOP_KEY_BYTE.toByte() },
        nodeType = ContactType.REPEATER.raw,
        snr = HILLTOP_SNR,
        snrIn = HILLTOP_SNR_IN,
        rssi = HILLTOP_RSSI,
        scanFilter = NodeDiscoveryFilter.REPEATERS,
        receivedAtEpochSeconds = ADDED_AT_EPOCH_SECONDS,
    )
}

private class AddDiscoverProbeError : Error("probe")

private const val ADDED_AT_EPOCH_SECONDS = 1_700_000_000L
private const val PUBLIC_KEY_LENGTH = 32
private const val HILLTOP_KEY_BYTE = 0x11
private const val HILLTOP_SNR = 9.0
private const val HILLTOP_SNR_IN = 4.0
private const val HILLTOP_RSSI = -80

private val RADIO_ID = Uuid.normalized("A0000000-0000-0000-0000-000000000099")
