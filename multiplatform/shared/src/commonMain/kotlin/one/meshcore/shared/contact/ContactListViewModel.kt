package one.meshcore.shared.contact

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.PreferencesStore

/**
 * Drives the Nodes (contacts) list: it loads contacts for the live radio, applies the search/segment
 * filter and sort through the pure [ContactListFiltering] port, and runs the row context-menu actions
 * (favorite, block, delete) and pull-to-refresh sync against the injected [ContactService]. The Kotlin
 * analogue of the iOS `ContactsViewModel`, plain over a [CoroutineScope] and provider closures so it is
 * unit-testable on the desktop JVM with a fake service.
 *
 * Reloads after user actions and pull-to-refresh, and also on live device pushes that mutate the
 * contact table ([ContactServiceEvent.ContactAdvertisementApplied] / [ContactServiceEvent.NodeDeleted])
 * so 0x80 auto-add and overwrite-delete appear without a manual refresh (iOS `contactsVersion` bump).
 */
class ContactListViewModel private constructor(private val parts: ContactListParts) :
    ContactListPresentation by parts.presentation,
    ContactListLoading by parts.loading,
    ContactListRowActions by parts.edits {
    /**
     * The live, per-connection inputs the view model reads at each use. A provider returning `null`
     * mirrors a disconnected state, matching the iOS view model's `@MainActor () -> T?` providers.
     */
    data class Dependencies(
        /** The contact service for the live radio, or `null` when disconnected. */
        val contactService: () -> ContactService?,
        /** The live radio's data-partition id, or `null` when disconnected. */
        val radioId: () -> Uuid?,
        /**
         * Whether a radio session is live (Connected/Syncing/Ready). Offline [ContactService] still
         * exists for list reads; mutations that iOS gates on `contactService` (live services) must
         * not run against that disconnected stand-in.
         */
        val isConnected: () -> Boolean = { false },
        /** Whether the radio is fully synced and ready (gates pull-to-refresh). */
        val isReady: () -> Boolean = { false },
        /** The user's location for distance sorting, or `null` when unavailable. */
        val userLocation: () -> UserLocation? = { null },
        /** Best-effort passively-heard inbound hops per uppercase-hex key; a failure must not fail the load. */
        val inboundHops: suspend (Uuid) -> Map<String, Int> = { emptyMap() },
        /** Persists the sort order across launches (iOS `@AppStorage(nodesSortOrder)`); `null` disables it. */
        val sortOrderStore: PreferencesStore? = null,
        /**
         * Emits when an app-side contact mutation (Add Contact, QR scan, Discover "Add", node-config
         * import) changed the table. Those carry no device push, so this is the list's only signal.
         */
        val listInvalidations: Flow<Unit> = emptyFlow(),
        /** Localized text for every contact-service failure, resolved at the Compose boundary. */
        val errorText: (Throwable) -> String,
        /** Confirmed removal, after the local row is gone; ordinary filtered/missing rows do not call this. */
        val onContactRemoved: (ContactDto) -> Unit = {},
    )

    /** The observable list state the Compose screen renders. */
    val state: StateFlow<ContactListState> = parts.model.state.asStateFlow()

    constructor(scope: CoroutineScope, dependencies: Dependencies) : this(ContactListParts(scope, dependencies)) {
        // Restore the persisted sort order so Distance/Name/Hops survive a relaunch (iOS @AppStorage default).
        dependencies.sortOrderStore?.let { store ->
            parts.model.sortRestoreJob = scope.launch {
                store.getString(NodeSortOrder.SORT_ORDER_PREFERENCE_KEY)?.let { raw ->
                    parts.model.state.update { it.copy(sortOrder = NodeSortOrder.fromRaw(raw)) }
                }
            }
        }
    }

    /** Stops subscriptions and pending initialization owned by this list instance. */
    fun close() = parts.model.close()
}

private class ContactListParts(scope: CoroutineScope, dependencies: ContactListViewModel.Dependencies) {
    val model = ContactListModel(scope, dependencies)
    val loading = ContactListLoader(model)
    val presentation = ContactListPresenter(model)
    val edits = ContactListRowActionRunner(model, loading)
}
