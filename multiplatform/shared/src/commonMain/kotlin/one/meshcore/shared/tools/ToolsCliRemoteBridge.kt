package one.meshcore.shared.tools

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import kotlin.time.Duration

/**
 * Seam between the Tools CLI terminal and remote-node login/CLI. Production wires
 * [one.meshcore.shared.remotenode.RemoteNodeService] + [one.meshcore.shared.remotenode.NodeCliCoordinator];
 * tests inject fakes.
 */
interface ToolsCliRemoteBridge {
    suspend fun radioId(): Uuid?

    suspend fun retrievePassword(publicKey: ByteArray): String?

    suspend fun deletePassword(publicKey: ByteArray)

    suspend fun storePassword(password: String, publicKey: ByteArray)

    /** Creates or reuses a remote session; returns the session id. */
    suspend fun createSession(radioID: Uuid, contact: ContactDto): Uuid

    /** Returns true when login succeeded. [onTimeoutKnown] is the firmware timeout in seconds. */
    suspend fun login(
        sessionID: Uuid,
        password: String,
        pathLength: Int,
        onTimeoutKnown: (suspend (Int) -> Unit)? = null,
    ): Boolean

    suspend fun logout(sessionID: Uuid)

    /** Sends a raw CLI line; [timeout] overrides the default (used for fire-and-forget `reboot`). */
    suspend fun sendRawCommand(sessionID: Uuid, command: String, timeout: Duration? = null): String
}
