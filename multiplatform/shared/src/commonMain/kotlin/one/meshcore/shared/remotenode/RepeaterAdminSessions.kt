package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.Uuid

/** Repeater session lookup and logout on an admin service. */
interface RepeaterAdminSessions {
    /** Disconnects: sends logout then removes the session. */
    suspend fun disconnect(sessionID: Uuid, publicKey: ByteArray)

    /** Every repeater session scoped to [radioID]. */
    suspend fun fetchRepeaterSessions(radioID: Uuid): List<RemoteNodeSessionDto>

    /** The connected repeater session whose public key starts with [publicKeyPrefix], or null. */
    suspend fun getConnectedSession(publicKeyPrefix: ByteArray): RemoteNodeSessionDto?
}

internal class RepeaterAdminSessionAccess(
    private val remoteNodeService: RemoteNodeService,
    private val store: RemoteNodeStore,
    private val passwordStore: NodePasswordStore,
) : RepeaterAdminSessions {
    /**
     * Connects to a repeater as admin: creates the session, logs in, stores the password only after a
     * successful login (when [rememberPassword]), and returns the updated session.
     */
    suspend fun connectAsAdmin(
        radioID: Uuid,
        contact: ContactDto,
        password: String?,
        login: RepeaterAdminLogin,
    ): RemoteNodeSessionDto {
        val session = remoteNodeService.createSession(radioID, contact)
        remoteNodeService.login(session.id, password, login.pathLength, login.onTimeoutKnown)
        if (password != null && login.rememberPassword) {
            passwordStore.storePassword(contact.publicKey, password)
        }
        return store.fetchSessionById(session.id) ?: throw RemoteNodeError.SessionNotFound
    }

    override suspend fun disconnect(sessionID: Uuid, publicKey: ByteArray) {
        remoteNodeService.logout(sessionID)
        remoteNodeService.removeSession(sessionID, publicKey)
    }

    override suspend fun fetchRepeaterSessions(radioID: Uuid): List<RemoteNodeSessionDto> =
        store.fetchSessionsByRadioId(radioID).filter { it.isRepeater }

    override suspend fun getConnectedSession(publicKeyPrefix: ByteArray): RemoteNodeSessionDto? =
        store.fetchSessionByPrefix(publicKeyPrefix)?.takeIf { it.isRepeater && it.isConnected }
}
