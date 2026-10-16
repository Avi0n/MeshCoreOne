package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.model.Uuid

/**
 * Drives the "send DM to a channel sender" picker (spec §12.2). A channel message carries a human-readable
 * sender name rather than a stable identity, so this resolves that name to stored contacts via the pure
 * [SenderContactMatcher] (case-insensitive, blocked excluded) and presents the matches; picking one opens
 * that DM. The Kotlin analogue of the iOS `SendDMSheet` `.task` load, plain over a provider closure so the
 * resolve path is unit-testable on the desktop JVM with a fake service.
 *
 * A load failure or disconnected graph leaves the matches empty, matching the iOS sheet that logs and shows
 * the "no matches" state on a fetch error.
 */
class SendDMViewModel(private val dependencies: Dependencies) {
    /** The live, per-connection input read at load time; a null result means disconnected. */
    class Dependencies(val contactService: () -> ContactService?)

    private val _state = MutableStateFlow(SendDMState())

    /** The observable picker state the Compose sheet renders. */
    val state: StateFlow<SendDMState> = _state.asStateFlow()

    /**
     * Loads the radio's contacts and resolves [senderName] to the case-insensitive matches (blocked
     * excluded). [radioID] is the channel message's `channel.radioID`; a disconnected or failing load lands
     * an empty match set so the sheet shows the "no matches" state.
     */
    suspend fun load(senderName: String, radioID: Uuid) {
        val service = dependencies.contactService()
        if (service == null) {
            _state.update { it.copy(matchingContacts = emptyList(), isLoading = false) }
            return
        }
        _state.update { it.copy(matchingContacts = emptyList(), isLoading = true) }
        // A Room fetch failure is not a ContactServiceError; settle it locally as no matches.
        val allContacts = try {
            service.getContacts(radioID)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            _state.update { it.copy(isLoading = false) }
            return
        }
        val matches = SenderContactMatcher.filter(
            contacts = allContacts,
            senderName = senderName,
            excludeBlocked = true,
        )
        _state.update { it.copy(matchingContacts = matches, isLoading = false) }
    }
}

/** The send-DM picker state: the name-resolved [matchingContacts] and the initial [isLoading] spinner. */
data class SendDMState(val matchingContacts: List<ContactDto> = emptyList(), val isLoading: Boolean = true)
