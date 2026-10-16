package one.meshcore.shared.connection

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.channel.ChannelBatchRead
import one.meshcore.shared.channel.ChannelDeviceException
import one.meshcore.shared.channel.ChannelSendTimeoutException
import one.meshcore.shared.channel.ChannelSession
import one.meshcore.shared.channel.ChannelTimeoutException
import one.meshcore.shared.channel.ChannelTransportException
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.chat.ChatDeviceException
import one.meshcore.shared.chat.ChatSession
import one.meshcore.shared.chat.IngestSession
import one.meshcore.shared.contact.ContactDeviceException
import one.meshcore.shared.contact.ContactSession
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.meshcore.firmwareCode
import kotlin.time.Duration

/**
 * Bridges the live [MeshSession] to the narrow [ChatSession] seam [one.meshcore.shared.chat.MessageService]
 * depends on. `currentSelfPublicKey` reads the live key so an import or regenerate during this
 * connection attributes later sends to the new identity. `waitForAcknowledgement` folds the pushed event
 * stream for the matching ACK with a timeout — the retry loop's explicit wait, secondary to the message
 * service's own continuous ACK monitor. Device errors on the send commands surface as [ChatDeviceException]
 * so the pool-backoff and channel NOT_FOUND paths classify them.
 */
class ChatSessionAdapter(private val session: MeshSession, private val selfPublicKey: () -> ByteArray?) : ChatSession {
    override val events: SharedFlow<MeshEvent> = session.events

    override suspend fun currentSelfPublicKey(): ByteArray? = selfPublicKey()

    override suspend fun sendDirectMessage(
        destination: ByteArray,
        text: String,
        timestamp: Long,
        attempt: Int,
    ): MessageSentInfo = mapDeviceError { session.sendMessage(destination, text, timestamp, attempt) }

    override suspend fun sendChannelMessage(channelIndex: Int, text: String, timestamp: Long) =
        mapDeviceError { session.sendChannelMessage(channelIndex, text, timestamp) }

    override suspend fun sendChannelMessage(
        channelIndex: Int,
        text: String,
        timestamp: Long,
        floodScope: ResolvedFloodScope,
    ) = mapDeviceError { session.sendChannelMessage(channelIndex, text, timestamp, floodScope) }

    override suspend fun resetPath(publicKey: ByteArray) = session.resetPath(publicKey)

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = session.getContact(publicKey)

    override suspend fun waitForAcknowledgement(code: ByteArray, timeout: Duration): Boolean =
        withTimeoutOrNull(timeout) {
            session.events
                .filterIsInstance<MeshEvent.Acknowledgement>()
                .first { it.code.contentEquals(code) }
            true
        } ?: false
}

/** Bridges the live [MeshSession] to the [IngestSession] seam the message poller drains against. */
class IngestSessionAdapter(private val session: MeshSession) : IngestSession {
    override val events: SharedFlow<MeshEvent> = session.events

    override suspend fun getMessage(): GetMessageResult = session.getMessage()
}

/**
 * Bridges the live [MeshSession] to the [ContactSession] seam [one.meshcore.shared.contact.ContactService]
 * depends on. Device errors surface as [ContactDeviceException] so the service maps `TABLE_FULL`/`NOT_FOUND`
 * to typed errors.
 */
class ContactSessionAdapter(private val session: MeshSession) : ContactSession {
    override suspend fun getContacts(since: Long?): List<MeshContact> = session.getContacts(since)

    override suspend fun getContactsReportingTotal(since: Long?) = session.getContactsReportingTotal(since)

    override suspend fun getContact(publicKey: ByteArray): MeshContact? =
        mapContactDeviceError { session.getContact(publicKey) }

    override suspend fun addContact(contact: MeshContact) = mapContactDeviceError { session.addContact(contact) }

    override suspend fun removeContact(publicKey: ByteArray) =
        mapContactDeviceError { session.removeContact(publicKey) }

    override suspend fun resetPath(publicKey: ByteArray) = mapContactDeviceError { session.resetPath(publicKey) }

    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo =
        mapContactDeviceError { session.sendPathDiscovery(destination) }

    override suspend fun shareContact(publicKey: ByteArray) = mapContactDeviceError { session.shareContact(publicKey) }

    override suspend fun changeContactFlags(contact: MeshContact, flags: Int) =
        mapContactDeviceError { session.changeContactFlags(contact, flags) }
}

/**
 * Bridges the live [MeshSession] to the narrow [ChannelSession] seam
 * [one.meshcore.shared.channel.ChannelService] depends on. A bridge failure is re-thrown as the matching
 * typed channel exception so the service's classifier distinguishes a device error (with its firmware
 * sub-code), a response timeout, a stuck send, and a transport failure — the four buckets its circuit
 * breaker and retry/backoff branch on. `getChannels` packs the slot indices into the bounded-window byte
 * request and unpacks the answered/missing split the reconcile loop consumes.
 */
class ChannelSessionAdapter(private val session: MeshSession) : ChannelSession {
    override suspend fun getChannel(index: Int): ChannelInfo = mapChannelError { session.getChannel(index) }

    override suspend fun getChannels(indices: List<Int>): ChannelBatchRead = mapChannelError {
        val result = session.getChannels(indices.map { it.toByte() }.toByteArray())
        ChannelBatchRead(received = result.received, missing = result.missing)
    }

    override suspend fun setChannel(index: Int, name: String, secret: ByteArray) =
        mapChannelError { session.setChannel(index, name, secret) }
}

// A DEVICE_ERROR is intentionally re-thrown as the typed chat/contact exception carrying the firmware
// sub-code the classifiers switch on; the bridge exception is the source of that code, not lost signal.
private inline fun <T> mapDeviceError(block: () -> T): T = try {
    block()
} catch (e: MeshBridgeException) {
    throw chatDeviceFailure(e)
}

private fun chatDeviceFailure(error: MeshBridgeException): Throwable =
    if (error.code == BridgeErrorCode.DEVICE_ERROR) ChatDeviceException(error.firmwareCode()) else error

private inline fun <T> mapContactDeviceError(block: () -> T): T = try {
    block()
} catch (e: MeshBridgeException) {
    throw contactDeviceFailure(e)
}

private fun contactDeviceFailure(error: MeshBridgeException): Throwable =
    if (error.code == BridgeErrorCode.DEVICE_ERROR) ContactDeviceException(error.firmwareCode()) else error

// Each bridge error code maps to the typed channel exception the service's classifier expects; an
// unrecognized code is re-thrown unchanged for the classifier's Unknown bucket.
private inline fun <T> mapChannelError(block: () -> T): T = try {
    block()
} catch (e: MeshBridgeException) {
    throw channelFailure(e)
}

private fun channelFailure(error: MeshBridgeException): Throwable = error.toChannelException()

private fun MeshBridgeException.toChannelException(): Throwable = when (code) {
    BridgeErrorCode.DEVICE_ERROR -> ChannelDeviceException(firmwareCode())
    BridgeErrorCode.TIMEOUT -> ChannelTimeoutException()
    BridgeErrorCode.TRANSPORT_SEND_FAILED -> ChannelSendTimeoutException()
    BridgeErrorCode.NOT_CONNECTED,
    BridgeErrorCode.CONNECTION_LOST,
    BridgeErrorCode.TRANSPORT_CONNECTION_FAILED,
    BridgeErrorCode.TRANSPORT_DEVICE_NOT_FOUND,
    BridgeErrorCode.TRANSPORT_SERVICE_NOT_FOUND,
    BridgeErrorCode.TRANSPORT_CHARACTERISTIC_NOT_FOUND,
    -> ChannelTransportException(detail)
    else -> this
}
