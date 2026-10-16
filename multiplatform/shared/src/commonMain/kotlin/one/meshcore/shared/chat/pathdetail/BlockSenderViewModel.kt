package one.meshcore.shared.chat.pathdetail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.SenderContactMatcher
import one.meshcore.shared.contact.BlockedChannelSenderService
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactServiceError
import one.meshcore.shared.contact.readContactsOrNull
import one.meshcore.shared.contact.updateContactPreferences
import one.meshcore.shared.model.Uuid

/**
 * Drives the "block channel sender from a message" sheet. Channel messages carry only a sender name, so a
 * block is name-based: [load] resolves the name to case-insensitive, not-already-blocked contacts (via the
 * pure [SenderContactMatcher]) that the user may additionally block, and [blockAnyway] runs the block.
 *
 * The block reuses [BlockedChannelSenderService] (persist the name and delete the sender's channel
 * messages + refresh the cache) and blocks each selected contact through [ContactService]; it never
 * reimplements that deletion. Mirrors the iOS `ChatConversationView.performBlock`, which dismisses the
 * sheet and runs the effects best-effort (a persist or contact-block failure is logged, not surfaced).
 */
class BlockSenderViewModel(private val dependencies: Dependencies) {
    /** The live, per-connection inputs read at action time; a null result means disconnected. */
    class Dependencies(
        val contactService: () -> ContactService?,
        val blockedChannelSenderService: () -> BlockedChannelSenderService?,
    )

    private val _state = MutableStateFlow(BlockSenderState())

    /** The observable sheet state the Compose surface renders. */
    val state: StateFlow<BlockSenderState> = _state.asStateFlow()

    /**
     * Resolves [senderName] to the radio's contacts the user could also block: an exact/case-insensitive
     * name match that is not already blocked. [radioID] is the channel's `radioID`; a disconnected or
     * failing load lands an empty match set.
     */
    suspend fun load(senderName: String, radioID: Uuid) {
        val service = dependencies.contactService()
        if (service == null) {
            _state.update {
                it.copy(matchingContacts = emptyList(), selectedContactIDs = emptySet(), isLoading = false)
            }
            return
        }
        _state.update {
            it.copy(matchingContacts = emptyList(), selectedContactIDs = emptySet(), isLoading = true)
        }
        val contacts = service.readContactsOrNull(radioID) ?: run {
            // A Room fetch is not a contact-service error. Leave the sheet without a spinner.
            _state.update { it.copy(isLoading = false) }
            return
        }
        val matches = SenderContactMatcher.filter(contacts = contacts, senderName = senderName, excludeBlocked = true)
        _state.update { it.copy(matchingContacts = matches, isLoading = false) }
    }

    /** Toggles whether the matching [contactID] is also blocked when the user confirms. */
    fun toggleContact(contactID: Uuid) {
        _state.update { current ->
            val selected = current.selectedContactIDs.toMutableSet()
            if (!selected.add(contactID)) selected.remove(contactID)
            current.copy(selectedContactIDs = selected)
        }
    }

    /**
     * Blocks [senderName] on [radioID] and any contacts the user selected, then marks the block complete so
     * the sheet dismisses. Effects run best-effort: a missing service or a failing block leaves
     * [BlockSenderState.blockCompleted] set (the iOS sheet dismisses and logs rather than surfacing an alert).
     */
    suspend fun blockAnyway(senderName: String, radioID: Uuid) {
        _state.update { it.copy(isBlocking = true) }
        val senderService = dependencies.blockedChannelSenderService()
        val contactService = dependencies.contactService()
        val selected = _state.value.selectedContactIDs
        try {
            // iOS `performBlock` requires live `appState.services`. Offline ContactService still
            // exists, so blocking matching contacts here would write Room and delete that sender's
            // channel messages while disconnected.
            if (senderService == null) return
            try {
                senderService.blockSender(radioID = radioID, senderName = senderName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // iOS logs a persist fault and still dismisses.
            }
            if (contactService != null) {
                for (contactID in selected) {
                    blockContact(contactService, contactID)
                }
            }
        } finally {
            _state.update { it.copy(isBlocking = false, blockCompleted = true) }
        }
    }

    private suspend fun blockContact(service: ContactService, contactID: Uuid) {
        try {
            service.updateContactPreferences(contactID = contactID, isBlocked = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ContactServiceError) {
            // A single contact failing to block does not abort the sender block; the iOS flow logs and continues.
        }
    }
}

/**
 * The block-sender sheet state: the name-resolved [matchingContacts] the user can also block, their
 * [selectedContactIDs], the initial [isLoading] spinner, the in-flight [isBlocking] flag, and
 * [blockCompleted] once the block ran (the signal to dismiss).
 */
data class BlockSenderState(
    val matchingContacts: List<ContactDto> = emptyList(),
    val selectedContactIDs: Set<Uuid> = emptySet(),
    val isLoading: Boolean = true,
    val isBlocking: Boolean = false,
    val blockCompleted: Boolean = false,
)
