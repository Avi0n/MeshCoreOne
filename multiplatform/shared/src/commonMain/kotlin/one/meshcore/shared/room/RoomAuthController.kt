package one.meshcore.shared.room

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.NodePasswordStore
import one.meshcore.shared.remotenode.RemoteNodeError

/** Outcome of a room join/reconnect attempt, mirroring the Swift auth sheet's error branches, L10n-free. */
sealed interface RoomAuthResult {
    /** Authenticated; [session] is the updated room session to hand back to the caller. */
    data class Success(val session: RemoteNodeSessionDto) : RoomAuthResult

    /** The firmware did not answer within the derived timeout. */
    data object Timeout : RoomAuthResult

    /** No live device/connection. */
    data object NotConnected : RoomAuthResult

    /** Authentication failed for another reason; [message] is the developer-English detail. */
    data class Failed(val message: String) : RoomAuthResult
}

/**
 * The room authentication-sheet logic core, mirroring the Swift `NodeAuthenticationSheet` join/reconnect
 * flow for the room-server role minus the SwiftUI shell: password entry (with the firmware's 15-char cap),
 * the remember-password toggle, the saved-password load/delete, error mapping to structured results, and
 * the timeout hint surfaced via `onTimeoutKnown`. Compose- and `L10n`-free.
 *
 * Path/flood-routing selection (and the one-time `resetPath` round-trip) is a contact/path concern outside
 * the room lane, so the resolved [pathLength] is passed in by the caller; it feeds only the login timeout
 * derivation (0 = direct, the flood sentinel = flood-routed).
 */
class RoomAuthController(
    private val roomServerService: RoomServerService,
    private val passwordStore: NodePasswordStore,
) {
    var password: String = ""
    var rememberPassword: Boolean = true
    var isAuthenticating: Boolean = false
        private set
    var hasSavedPassword: Boolean = false
        private set

    /** Seconds the firmware suggested for this attempt, set once `onTimeoutKnown` fires; drives the countdown. */
    var timeoutSeconds: Int? = null
        private set

    /** The password actually sent: room servers accept at most [MAX_PASSWORD_LENGTH] characters. */
    val passwordToUse: String get() = password.take(MAX_PASSWORD_LENGTH)

    /** Whether the entered password exceeds the firmware cap (the UI shows a hint but still truncates). */
    val isPasswordTooLong: Boolean get() = password.length > MAX_PASSWORD_LENGTH

    /** Loads a previously stored password for [publicKey] so the field pre-fills when the sheet opens. */
    suspend fun loadSavedPassword(publicKey: ByteArray) {
        passwordStore.retrievePassword(publicKey)?.let {
            password = it
            hasSavedPassword = true
        }
    }

    /**
     * Joins the room: logs in through [RoomServerService.joinRoom] with the truncated password and the
     * remember toggle, reports the firmware timeout hint via [timeoutSeconds], and on success clears a
     * previously stored password when the user unchecked remember.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun join(radioID: Uuid, contact: ContactDto, pathLength: Int = 0): RoomAuthResult = authenticate {
        val session = roomServerService.joinRoom(
            radioID = radioID,
            contact = contact,
            password = passwordToUse,
            login = RoomServerLogin(
                rememberPassword = rememberPassword,
                pathLength = pathLength,
                onTimeoutKnown = { timeoutSeconds = it },
            ),
        )
        if (hasSavedPassword && !rememberPassword) {
            try {
                passwordStore.deletePassword(contact.publicKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Authentication succeeded; failed credential cleanup must not change that result.
            }
        }
        session
    }

    /** Re-authenticates an existing room session (stored password), reporting the timeout hint. */
    suspend fun reconnect(sessionID: Uuid, pathLength: Int = 0): RoomAuthResult = authenticate {
        roomServerService.reconnectRoom(sessionID, pathLength)
    }

    private suspend inline fun authenticate(block: () -> RemoteNodeSessionDto): RoomAuthResult {
        isAuthenticating = true
        timeoutSeconds = null
        return try {
            RoomAuthResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteNodeError) {
            mapError(e)
        } finally {
            isAuthenticating = false
        }
    }

    private fun mapError(error: RemoteNodeError): RoomAuthResult = when (error) {
        is RemoteNodeError.Timeout -> RoomAuthResult.Timeout
        is RemoteNodeError.NotConnected -> RoomAuthResult.NotConnected
        else -> RoomAuthResult.Failed(error.message ?: "authentication failed")
    }

    private companion object {
        const val MAX_PASSWORD_LENGTH = 15
    }
}
