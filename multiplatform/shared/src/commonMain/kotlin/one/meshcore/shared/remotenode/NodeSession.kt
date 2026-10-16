package one.meshcore.shared.remotenode

import kotlinx.coroutines.flow.SharedFlow
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo

/**
 * The device-session seam [RemoteNodeService] depends on: the node-access commands the service issues,
 * plus the pushed [events] stream it watches for `loginSuccess`/`loginFailed`. A narrow port over the
 * androidMain-only `MeshCoreSession` so the service stays in commonMain and is unit-testable with a fake,
 * mirroring [one.meshcore.shared.contact.ContactSession] and [one.meshcore.shared.channel.ChannelSession].
 *
 * A command failure is raised as a [MeshError]; [one.meshcore.shared.connection.NodeSessionAdapter] maps a
 * bridge `MeshBridgeException` to one and fakes throw it directly.
 */
interface NodeSession : NodeQuerySession {
    /** The hot stream of pushed device events, including the login-result pushes the service routes. */
    val events: SharedFlow<MeshEvent>

    /** Sends a login to [destination] (32-byte key); the result arrives later as a login push event. */
    suspend fun sendLogin(destination: ByteArray, password: String): MessageSentInfo

    /** Sends a logout to [destination] (32-byte key). */
    suspend fun sendLogout(destination: ByteArray)

    /** Sends a keep-alive to [publicKey], carrying [syncSince] as the room-sync force-resync hint. */
    suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: Long): MessageSentInfo

    /**
     * Adds or updates a whole contact on the device. Used by login auto-heal to re-add a node the radio no
     * longer knows (firmware answers a login to a missing contact with `notFound`). A device `TABLE_FULL`
     * surfaces as [MeshError.DeviceError]. The default rejects the call so only the live adapter routes it.
     */
    suspend fun addContact(contact: MeshContact): Unit = throw MeshError.NotConnected

    /**
     * Resets a contact's out-path to flood routing on the device. Used by CLI direct-path flood recovery
     * when a stored direct path has gone stale. The default rejects the call so only the live adapter routes it.
     */
    suspend fun resetPath(publicKey: ByteArray): Unit = throw MeshError.NotConnected

    /**
     * Sends a CLI [command] to [destination] (32-byte key) and returns the queued-send info. The response
     * arrives later as a pushed `contactMessageReceived` event with the CLI text type. Firmware queues
     * that frame until [getMessage] (or auto-fetch) dequeues it, so the CLI waiter must pump [getMessage]
     * the way Swift `performCLIExchange` does.
     */
    suspend fun sendCommand(destination: ByteArray, command: String): MessageSentInfo

    /**
     * Pops one queued inbound frame off the radio. Default is empty so disconnected fakes do not spin.
     * The live adapter forwards [one.meshcore.shared.meshcore.MeshSession.getMessage].
     */
    suspend fun getMessage(): GetMessageResult = GetMessageResult.NoMoreMessages
}
