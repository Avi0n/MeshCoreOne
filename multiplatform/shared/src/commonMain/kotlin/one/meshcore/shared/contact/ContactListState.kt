package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid

/**
 * The observable state of the Nodes (contacts) list, held by [ContactListViewModel] and rendered by the
 * Compose screen. Kotlin port of the iOS `ContactsViewModel`'s observable surface; the derived filtered
 * list is exposed separately so the pure [ContactListFiltering] port stays independently testable.
 */
data class ContactListState(
    /** All contacts loaded for the current radio, before segment/search filtering. */
    val contacts: List<ContactDto> = emptyList(),
    /** Passively-heard inbound advert hops per uppercase-hex public key, refreshed on each load. */
    val inboundHopByKey: Map<String, Int> = emptyMap(),
    val searchText: String = "",
    val segment: NodeSegment = NodeSegment.CONTACTS,
    val sortOrder: NodeSortOrder = NodeSortOrder.LAST_HEARD,
    val isLoading: Boolean = false,
    /** True once a load has completed; gates the empty state so it does not flash before the first load. */
    val hasLoadedOnce: Boolean = false,
    val isSyncing: Boolean = false,
    val syncProgress: SyncProgress? = null,
    val error: ContactListError? = null,
    /** The contact whose favorite toggle is in flight, surfaced as a row spinner. */
    val togglingFavoriteId: Uuid? = null,
    /** Rows with a delete command in flight (spinner overlay). Distinct from [pendingRemovalIds]. */
    val deletingIds: Set<Uuid> = emptySet(),
    /** Rows hidden after a confirmed delete, held until a reload sees the row gone (anti-resurrection mask). */
    val pendingRemovalIds: Set<Uuid> = emptySet(),
    /** Whether the user has manually switched segments; blocks the one-time favorites auto-select. */
    val userSwitchedSegment: Boolean = false,
) {
    /** Whether a search is active (search spans all types and hides the segment picker). */
    val isSearching: Boolean get() = searchText.isNotEmpty()

    /** True when any loaded contact is a favorite; drives the one-time favorites auto-select. */
    val hasFavorites: Boolean get() = contacts.any { it.isFavorite }

    /** True while a delete for [id] is either confirmed-but-unreloaded or in flight. */
    fun isDeletePending(id: Uuid): Boolean = id in pendingRemovalIds || id in deletingIds
}

/** Progress during a contact sync: [received] of [total] contacts persisted. */
data class SyncProgress(val received: Int, val total: Int)

/**
 * A surfaced error from a contact-list action. Typed rather than a raw string because two cases resolve
 * to localized Compose string resources at render time; [ServiceMessage] carries a lower-layer failure's
 * message verbatim (the [ContactServiceError] messages already match the iOS `errorDescription` text).
 */
sealed interface ContactListError {
    /** A delete was attempted while disconnected. */
    data object ConnectToDelete : ContactListError

    /** A delete round-trip to the radio exceeded its timeout; the row stays put. */
    data object RemoveTimedOut : ContactListError

    /** A refresh was attempted while the radio was not ready. */
    data object CannotRefresh : ContactListError

    /** Any other failure, carrying the lower layer's message. */
    data class ServiceMessage(val message: String) : ContactListError
}
