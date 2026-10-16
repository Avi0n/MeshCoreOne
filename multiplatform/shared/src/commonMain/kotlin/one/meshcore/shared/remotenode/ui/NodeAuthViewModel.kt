package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.contact.isFloodRouted
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.NodePasswordStore
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.room.RoomServerService
import one.meshcore.shared.settings.PersistentLogger
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** A surfaced authentication error; the sheet maps each case to its `Auth.*` string resource. */
sealed interface NodeAuthError {
    /** The node did not respond before the firmware timeout. */
    data object Timeout : NodeAuthError

    /** A lower-layer failure, carrying its message verbatim. */
    data class Message(val text: String) : NodeAuthError
}

/** The observable state of the node authentication sheet. */
data class NodeAuthState(
    val password: String = "",
    val rememberPassword: Boolean = true,
    val useFloodRouting: Boolean = false,
    val isAuthenticating: Boolean = false,
    val errorMessage: NodeAuthError? = null,
    val secondsRemaining: Int? = null,
    val hasSavedPassword: Boolean = false,
    /** Repeater contacts for resolving the stored route's hop names in the path preview; empty until loaded. */
    val pathContacts: List<ContactDto> = emptyList(),
    /** Discovered rows for the same resolution, so a hop heard only through an advert still shows a name. */
    val pathDiscovered: List<DiscoveredNodeRecord> = emptyList(),
    /** The responder's position, which breaks a hop-prefix collision by proximity. */
    val pathUserLocation: UserLocation? = null,
    /** True while a timed-out direct login is being retried over a flood route. */
    val isRetryingViaFlood: Boolean = false,
    /**
     * The last countdown value TalkBack should read aloud, updated only at the announcement thresholds (and
     * every tick in the final seconds), not on every tick. A live-region readout in the sheet binds to it.
     */
    val secondsRemainingAnnouncement: Int? = null,
)

/**
 * Drives the node authentication sheet (password entry + connect) for both repeaters and room servers,
 * ported from the iOS `NodeAuthenticationSheet`. Plain over a [CoroutineScope] and provider closures, with
 * the observable surface as a [StateFlow]; the connect flow, the firmware-timeout countdown, and the
 * remember/flood toggles all live here so the composable stays a dumb view.
 *
 * The password is truncated to [MAX_PASSWORD_LENGTH] before the login send (MeshCore firmware caps node
 * passwords at 15 characters); a flood-routing connect resets the firmware path once per session so the
 * login packet floods.
 */
@OptIn(ExperimentalTime::class)
class NodeAuthViewModel(
    private val scope: CoroutineScope,
    private val contact: ContactDto,
    private val role: RemoteNodeRole,
    private val dependencies: Dependencies,
    /** Wall-clock reader for the countdown, so a delayed tick reflects true elapsed time rather than drifting. */
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val logger: PersistentLogger? = null,
) {
    // The holder is already the fix LongParameterList asks for; splitting it again would only hide the
    // sheet's real dependency set.
    data class Dependencies(
        val repeaterAdminService: () -> RepeaterAdminService?,
        val roomServerService: () -> RoomServerService?,
        val passwordStore: () -> NodePasswordStore?,
        val contactService: () -> ContactService?,
        val radioId: () -> Uuid?,
        /**
         * Whether a radio session is live. Offline [RoomServerService] still exists for conversation
         * reads; iOS node auth requires `appState.services`.
         */
        val isRadioConnected: () -> Boolean = { true },
        val discoveredNodeStore: () -> DiscoveredNodeStore? = { null },
        /** Localized text for every remote-node failure, resolved at the Compose boundary. */
        val errorText: (Throwable) -> String,
        /** The responder's position for the path preview's proximity tiebreak; null when unknown. */
        val userLocation: () -> UserLocation? = { null },
    )

    private val _state = MutableStateFlow(NodeAuthState(useFloodRouting = contact.isFloodRouted))
    val state: StateFlow<NodeAuthState> = _state.asStateFlow()
    private val countdown = NodeAuthCountdown(scope, nowMillis, _state)
    private val path = NodeAuthPath(contact, dependencies, _state)
    private val login = NodeAuthLogin(contact, role, dependencies, _state, path, countdown)

    private var authJob: Job? = null
    private var isClosed = false

    fun setPassword(value: String) = _state.update {
        it.copy(password = value, errorMessage = if (it.errorMessage != null) null else it.errorMessage)
    }

    fun setRememberPassword(value: Boolean) = _state.update { it.copy(rememberPassword = value) }

    fun setUseFloodRouting(value: Boolean) = _state.update { it.copy(useFloodRouting = value) }

    /** Loads a previously-saved password for this node into the field. */
    suspend fun loadSavedPassword() {
        val saved = dependencies.passwordStore()?.retrievePassword(contact.publicKey) ?: return
        _state.update { it.copy(password = saved, hasSavedPassword = true) }
    }

    /**
     * Loads the radio's contacts and discovered rows so the path preview can resolve each stored hop to a
     * repeater name. A flood-routed contact or one with no stored path has nothing to resolve, mirroring
     * the iOS load guard.
     */
    suspend fun loadPath() {
        path.load()
    }

    /**
     * Connects/authenticates. On success invokes [onSuccess] with the session (the sheet dismisses); on
     * failure sets [NodeAuthState.errorMessage]. A re-entry cancels the prior attempt.
     */
    @Suppress("TooGenericExceptionCaught")
    fun authenticate(onSuccess: (RemoteNodeSessionDto) -> Unit) {
        if (isClosed) return
        authJob?.cancel()
        countdown.cancel()
        _state.update { it.copy(errorMessage = null, isAuthenticating = true, isRetryingViaFlood = false) }
        authJob = scope.launch {
            // A failed login is shown on the sheet. Cancellation settles the sheet and still propagates.
            try {
                val session = login.connect()
                clearSavedPasswordIfNeeded()
                countdown.cancel()
                _state.update { it.settledAfterCountdown() }
                onSuccess(session)
            } catch (cancelled: CancellationException) {
                countdown.cancel()
                _state.update { it.settledAfterCountdown() }
                throw cancelled
            } catch (_: RemoteNodeError.Timeout) {
                fail(NodeAuthError.Timeout)
            } catch (error: Exception) {
                fail(NodeAuthError.Message(dependencies.errorText(error)))
            }
        }
    }

    /** Cancels an in-flight authentication. */
    fun cancel() {
        authJob?.cancel()
        countdown.cancel()
        _state.update { it.settledAfterCountdown() }
    }

    /** Stops authentication owned by a sheet that has left composition and rejects later retries. */
    fun close() {
        if (isClosed) return
        isClosed = true
        cancel()
    }

    // A failed password delete is logged and login still finishes. Cancellation and a thrown error still abort login.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun clearSavedPasswordIfNeeded() {
        if (!(_state.value.hasSavedPassword && !_state.value.rememberPassword)) return
        try {
            dependencies.passwordStore()?.deletePassword(contact.publicKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger?.warning(failedToDeleteSavedPasswordLine(error.message ?: error.toString()))
        }
    }

    private fun fail(error: NodeAuthError) {
        countdown.cancel()
        _state.update { it.settledAfterCountdown().copy(errorMessage = error) }
    }

    companion object {
        const val MAX_PASSWORD_LENGTH = 15

        /** Countdown values at which TalkBack gets a time-remaining announcement. */
        private val COUNTDOWN_ANNOUNCEMENT_THRESHOLDS = listOf(30, 15, 10)

        /** At or below this many seconds, every countdown tick is announced. */
        private const val FINAL_COUNTDOWN_SECONDS = 5

        /**
         * Whether TalkBack should read the countdown at [remaining]: crossing a coarse threshold (crossing
         * rather than exact equality so a skipped tick can't drop an announcement) or every tick in the final
         * seconds. Mirrors the iOS `AuthenticationSection` announcement rule.
         */
        internal fun shouldAnnounceCountdown(previous: Int?, remaining: Int): Boolean {
            val crossedThreshold = COUNTDOWN_ANNOUNCEMENT_THRESHOLDS.any { threshold ->
                remaining <= threshold && (previous ?: Int.MAX_VALUE) > threshold
            }
            return crossedThreshold || remaining <= FINAL_COUNTDOWN_SECONDS
        }
    }
}
