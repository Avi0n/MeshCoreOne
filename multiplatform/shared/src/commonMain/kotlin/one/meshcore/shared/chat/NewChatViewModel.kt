package one.meshcore.shared.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.readContactsOrNull
import one.meshcore.shared.model.Uuid

/**
 * Drives the new-chat contact picker (spec §12.1): it loads the live radio's contacts and applies the
 * pure [NewChatContactFilter] eligibility + search. The Kotlin analogue of the iOS `NewChatView` `.task`
 * load and `filteredContacts` computed property, plain over provider closures so it is unit-testable on
 * the desktop JVM with a fake service.
 *
 * A load failure or a disconnected graph (null provider result) leaves the contact list empty, matching
 * the iOS view that swallows the fetch error to an empty picker; the empty state then gates on the loaded
 * contacts being empty.
 */
class NewChatViewModel(private val dependencies: Dependencies) {
    /** The live, per-connection inputs read at load time; a null provider result means disconnected. */
    class Dependencies(val contactService: () -> ContactService?, val radioId: () -> Uuid?)

    private val _state = MutableStateFlow(NewChatState())

    /** The observable picker state the Compose sheet renders. */
    val state: StateFlow<NewChatState> = _state.asStateFlow()

    fun setSearchText(text: String) = _state.update { it.copy(searchText = text) }

    /**
     * The eligible, search-filtered contacts for [state] through the pure [NewChatContactFilter]. The sheet
     * recomputes this in composition as the collected state changes; a plain function (rather than a derived
     * flow) keeps the exact order the tests assert independent of scheduler timing.
     */
    fun eligibleContacts(state: NewChatState): List<ContactDto> =
        NewChatContactFilter.eligibleContacts(state.contacts, state.searchText)

    /** Loads the live radio's contacts into [state]; a disconnected or failing load lands an empty picker. */
    suspend fun load() {
        val service = dependencies.contactService()
        val radioId = dependencies.radioId()
        if (service == null || radioId == null) {
            // The picker VM is remembered across sheet presentations. A forgotten radio
            // must drop the previous load so the user cannot open a DM on a gone partition.
            // iOS `NewChatView` is a fresh @State list each present and stays empty here.
            _state.update {
                it.copy(contacts = emptyList(), isLoading = false, hasLoadedOnce = true)
            }
            return
        }
        _state.update { it.copy(isLoading = true) }
        // A Room fetch failure is not a contact-service error. The update below still settles the spinner.
        val loaded = service.readContactsOrNull(radioId)
        _state.update {
            it.copy(contacts = loaded.orEmpty(), isLoading = false, hasLoadedOnce = true)
        }
    }
}

/**
 * The new-chat picker state. [contacts] is the raw loaded list; [NewChatViewModel.eligibleContacts] derives
 * the visible rows so the empty state can gate on the unfiltered list being empty (mirroring the iOS view,
 * which shows "No Contacts" only when no contacts exist, not when a search merely matches none).
 */
data class NewChatState(
    val contacts: List<ContactDto> = emptyList(),
    val searchText: String = "",
    val isLoading: Boolean = false,
    val hasLoadedOnce: Boolean = false,
)
