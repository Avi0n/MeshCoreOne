package one.meshcore.shared.chat

import kotlinx.coroutines.flow.SharedFlow
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo
import kotlin.time.Duration

/**
 * The device-session seam [MessageService] depends on: the send/path operations plus the pushed-event
 * stream the ACK listener consumes. The narrow port keeps the service in commonMain and independently
 * testable. [one.meshcore.shared.connection.ChatSessionAdapter] bridges the live session. Every method
 * mirrors a Swift `MeshCoreSessionProtocol` call the message service makes.
 */
interface ChatSession {
    /** The hot stream of pushed device events; the ACK listener filters it for [MeshEvent.Acknowledgement]. */
    val events: SharedFlow<MeshEvent>

    /** This device's public key, or null when disconnected (mirrors `currentSelfInfo?.publicKey`). */
    suspend fun currentSelfPublicKey(): ByteArray?

    /**
     * Queues a direct message for transmission, returning the chosen route and expected ACK. [timestamp]
     * is epoch seconds; [attempt] is the retry index the firmware hashes into the ACK code. Throws
     * [ChatDeviceException] on a device error (its [ChatDeviceException.firmwareCode] drives pool backoff).
     */
    suspend fun sendDirectMessage(destination: ByteArray, text: String, timestamp: Long, attempt: Int): MessageSentInfo

    /** Broadcasts a channel message. No ACK is expected. [timestamp] is epoch seconds. */
    suspend fun sendChannelMessage(channelIndex: Int, text: String, timestamp: Long)

    /** Applies scope and broadcasts while holding one Swift command-response slot. */
    suspend fun sendChannelMessage(channelIndex: Int, text: String, timestamp: Long, floodScope: ResolvedFloodScope)

    /** Resets a contact's out-path to flood routing. */
    suspend fun resetPath(publicKey: ByteArray)

    /** Reads a contact's current state from the device, or null if it is not in the device contacts. */
    suspend fun getContact(publicKey: ByteArray): MeshContact?

    /**
     * Suspends until an [MeshEvent.Acknowledgement] whose code equals [code] arrives or [timeout]
     * elapses. Returns true if the ACK landed, false on timeout. Mirrors the Swift
     * `waitForEvent(filter: .acknowledgement(code:), timeout:)`.
     */
    suspend fun waitForAcknowledgement(code: ByteArray, timeout: Duration): Boolean
}
