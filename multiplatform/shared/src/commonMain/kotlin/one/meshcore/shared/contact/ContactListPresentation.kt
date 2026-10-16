package one.meshcore.shared.contact

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto

/** Search, segment, and sort intents for the nodes list. */
interface ContactListPresentation {
    /**
     * The segment/search-filtered, sorted contacts for [state], through the pure [ContactListFiltering]
     * port. The screen recomputes this in composition as the collected state changes; a plain function
     * (rather than a derived flow) keeps the exact order the tests assert independent of scheduler timing.
     */
    fun filteredContacts(state: ContactListState): List<ContactDto>

    fun setSearchText(text: String)

    /** Selects [segment] from the picker; marks the manual switch so the favorites auto-select never fires again. */
    fun setSegment(segment: NodeSegment)

    /** Selects [order] and persists it so the choice survives the next launch (iOS `@AppStorage`). */
    fun setSortOrder(order: NodeSortOrder)

    fun clearError()
}

internal class ContactListPresenter(private val model: ContactListModel) : ContactListPresentation {
    override fun filteredContacts(state: ContactListState): List<ContactDto> = ContactListFiltering.filteredContacts(
        ContactListFiltering.Inputs(
            contacts = state.contacts,
            searchText = state.searchText,
            segment = state.segment,
            sortOrder = state.sortOrder,
            userLocation = model.dependencies.userLocation(),
            inboundHopByKey = state.inboundHopByKey,
            pendingRemovalIds = state.pendingRemovalIds,
        ),
    )

    override fun setSearchText(text: String) = model.state.update { it.copy(searchText = text) }

    override fun setSegment(segment: NodeSegment) =
        model.state.update { it.copy(segment = segment, userSwitchedSegment = true) }

    override fun setSortOrder(order: NodeSortOrder) {
        model.state.update { it.copy(sortOrder = order) }
        model.dependencies.sortOrderStore?.let { store ->
            model.scope.launch { store.putString(NodeSortOrder.SORT_ORDER_PREFERENCE_KEY, order.raw) }
        }
    }

    override fun clearError() = model.state.update { it.copy(error = null) }
}
