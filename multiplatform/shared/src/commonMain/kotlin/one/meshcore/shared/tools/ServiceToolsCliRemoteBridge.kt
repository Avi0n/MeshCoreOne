package one.meshcore.shared.tools

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.NodePasswordStore
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.createSession
import one.meshcore.shared.remotenode.login
import one.meshcore.shared.remotenode.sendRawCLICommand
import kotlin.time.Duration

/**
 * Production [ToolsCliRemoteBridge] over [RemoteNodeService] + [NodeCliCoordinator].
 */
class ServiceToolsCliRemoteBridge(
    private val radioIdProvider: () -> Uuid?,
    private val remoteNodeService: RemoteNodeService,
    private val nodeCliCoordinator: NodeCliCoordinator,
    private val passwordStore: NodePasswordStore,
) : ToolsCliRemoteBridge {
    override suspend fun radioId(): Uuid? = radioIdProvider()

    override suspend fun retrievePassword(publicKey: ByteArray): String? = passwordStore.retrievePassword(publicKey)

    override suspend fun deletePassword(publicKey: ByteArray) {
        passwordStore.deletePassword(publicKey)
    }

    override suspend fun storePassword(password: String, publicKey: ByteArray) {
        passwordStore.storePassword(publicKey, password)
    }

    override suspend fun createSession(radioID: Uuid, contact: ContactDto): Uuid =
        remoteNodeService.createSession(radioID, contact).id

    override suspend fun login(
        sessionID: Uuid,
        password: String,
        pathLength: Int,
        onTimeoutKnown: (suspend (Int) -> Unit)?,
    ): Boolean = remoteNodeService.login(sessionID, password, pathLength, onTimeoutKnown).success

    override suspend fun logout(sessionID: Uuid) {
        remoteNodeService.logout(sessionID)
    }

    override suspend fun sendRawCommand(sessionID: Uuid, command: String, timeout: Duration?): String =
        if (timeout != null) {
            nodeCliCoordinator.sendRawCLICommand(sessionID, command, timeout)
        } else {
            nodeCliCoordinator.sendRawCLICommand(sessionID, command)
        }

    /**
     * Identity is the per-connection service graph, not the wrapper: the app builds a bridge per provider
     * read, and [ToolsCliTerminalKey] must see those reads as one terminal or every return to the CLI
     * discards the scrollback and the remote logins. A rebuilt service graph is a new connection.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ServiceToolsCliRemoteBridge) return false
        return remoteNodeService === other.remoteNodeService &&
            nodeCliCoordinator === other.nodeCliCoordinator &&
            passwordStore === other.passwordStore
    }

    override fun hashCode(): Int {
        var result = remoteNodeService.hashCode()
        result = HASH_MULTIPLIER * result + nodeCliCoordinator.hashCode()
        result = HASH_MULTIPLIER * result + passwordStore.hashCode()
        return result
    }

    private companion object {
        const val HASH_MULTIPLIER = 31
    }
}
