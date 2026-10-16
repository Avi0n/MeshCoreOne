package one.meshcore.shared.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.meshcore.DiscoverResponse
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid

internal class DiscoveryLifetimeFixture(scope: CoroutineScope, val store: ContactStore = InMemoryContactStore()) {
    var radio: Uuid? = RADIO
    var service: ContactService? = ContactService(NoOpContactSession, store, now = { 0L })
    var events: Flow<MeshEvent>? = MutableSharedFlow<MeshEvent>()
    var clock = 0L
    var sendCalls = 0
    var send: suspend () -> Long = { TAG }
    val workspace = NodeDiscoveryWorkspace(
        scope = scope,
        dependencies = NodeDiscoveryWorkspace.Dependencies(
            sendDiscover = { _, _ ->
                sendCalls++
                send()
            },
            events = { events },
            contactService = { service },
            discoveredNodeStore = { null },
            radioId = { radio },
            isRadioConnected = { service != null },
            maxContacts = { null },
        ),
        nowEpochMillis = { clock },
        scanDurationMs = WINDOW,
    )
    val model = workspace.bind(messages())

    fun messages(failure: String = NODE_DISCOVERY_SCAN_FAILED) = NodeDiscoveryWorkspace.Messages(
        notConnected = { NODE_DISCOVERY_NOT_CONNECTED },
        scanFailed = failure,
        unknownNode = NODE_DISCOVERY_UNKNOWN_NODE,
        contactError = { NODE_DISCOVERY_LOCALIZED_ERROR },
    )

    companion object {
        const val TAG = 0x1122_3344L
        const val WINDOW = 5_000L
        val RADIO = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val OTHER_RADIO = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
        fun result(key: Int = 0x44, name: String = "Hilltop") = NodeDiscoveryResult(
            name = name,
            publicKey = ByteArray(32) { key.toByte() },
            nodeType = ContactType.REPEATER.raw,
            snr = 5.0,
            snrIn = 4.0,
            rssi = -90,
            scanFilter = NodeDiscoveryFilter.REPEATERS,
            receivedAtEpochSeconds = 0L,
        )
        fun response(key: Int = 0x44, tag: Long = TAG): MeshEvent = MeshEvent.DiscoverResponseReceived(
            DiscoverResponse(
                nodeType = ContactType.REPEATER.raw,
                snr = 5.0,
                snrIn = 4.0,
                rssi = -90,
                pathLength = 1,
                tag = NodeDiscoveryViewModel.tagToLittleEndianBytes(tag),
                publicKey = ByteArray(32) { key.toByte() },
            ),
        )
    }
}
