package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid

/** The observable state of the Blocked Contacts screen. */
data class BlockedContactsState(
    /** The radio's blocked contacts, sorted by name. */
    val contacts: List<ContactDto> = emptyList(),
    val isLoading: Boolean = false,
    /** True once a load has completed; gates the empty state so it does not flash before the first load. */
    val hasLoadedOnce: Boolean = false,
    /** A surfaced load failure's message, shown via an error alert. */
    val errorMessage: String? = null,
)

/**
 * Drives the Blocked Contacts screen: loads the live radio's blocked contacts through the injected
 * [ContactService] and reloads on demand (the Compose layer re-invokes [load] when the contacts version
 * changes). Kotlin analogue of the iOS `BlockedContactsView`'s state; unblocking happens from the detail
 * screen or list context menu via [ContactService.updateContactPreferences], not here.
 */
class BlockedContactsViewModel(private val scope: CoroutineScope, private val dependencies: Dependencies) {
    private val serviceEvents = ContactServiceEventBinding(scope)

    /** The live, per-connection inputs, read at each load; a null provider result means disconnected. */
    class Dependencies(
        val contactService: () -> ContactService?,
        val radioId: () -> Uuid?,
        /** Localized text for every contact-service failure, including the generic fallback. */
        val errorText: (Throwable) -> String,
    )

    private val _state = MutableStateFlow(BlockedContactsState())

    /** The observable state the Compose screen renders. */
    val state: StateFlow<BlockedContactsState> = _state.asStateFlow()

    /**
     * Subscribes to [ContactService.events] so a device 0x8F overwrite-oldest (or 0x80 auto-add)
     * refreshes the blocked list, matching iOS `contactsVersion`. Idempotent when the service
     * identity is unchanged; rebinds after reconnect.
     */
    suspend fun bindServiceEvents() {
        serviceEvents.bind(dependencies.contactService()) { event ->
            when (event) {
                is ContactServiceEvent.ContactAdvertisementApplied,
                is ContactServiceEvent.NodeDeleted,
                -> load()
                else -> Unit
            }
        }
    }

    /** Stops the live service subscription owned by this screen instance. */
    fun close() = serviceEvents.close()

    /** Loads the live radio's blocked contacts. Disconnected still marks the list loaded so the empty pane shows. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun load() {
        val service = dependencies.contactService()
        val radioID = dependencies.radioId()
        if (service == null || radioID == null) {
            _state.update {
                it.copy(contacts = emptyList(), isLoading = false, hasLoadedOnce = true)
            }
            return
        }
        _state.update { it.copy(isLoading = true) }
        try {
            val blocked = service.getBlockedContacts(radioID)
            _state.update {
                it.copy(contacts = blocked, isLoading = false, hasLoadedOnce = true, errorMessage = null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.update {
                it.copy(isLoading = false, errorMessage = dependencies.errorText(error))
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
