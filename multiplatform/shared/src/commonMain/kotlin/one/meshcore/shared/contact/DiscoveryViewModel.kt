package one.meshcore.shared.contact

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.meshcore.shared.map.runDiscoveredNodeAdd
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.runReportingException
import one.meshcore.shared.settings.PersistentLogger

/**
 * Discover screen state: loads volatile discovered nodes + contact public keys for the "Added"
 * badge. [startObserving] binds [DiscoveredNodeStore.snapshot] so advert ingest updates the list
 * without a manual refresh. Adding a node is delegated to the UI via [ContactService] import.
 */
class DiscoveryViewModel(
    private val scope: CoroutineScope,
    private val dependencies: Dependencies,
    private val nowEpochSeconds: () -> Long = { 0L },
    private val queryEditor: DiscoveryQueryEditor = DiscoveryQueryEditor(
        scope = scope,
        userLocation = dependencies.userLocation,
        sortOrderStore = dependencies.sortOrderStore,
    ),
) : DiscoveryQueryControls by queryEditor {
    data class Dependencies(
        val store: () -> DiscoveredNodeStore?,
        val contactService: () -> ContactService?,
        val radioId: () -> Uuid?,
        /**
         * Whether a radio session is live. Offline [ContactService] still exists for Added-badge
         * reads; Add is a device `CMD_ADD` that iOS gates on `appState.services?.contactService`.
         */
        val isRadioConnected: () -> Boolean = { false },
        /** The user's location for distance sorting, or `null` when unavailable. */
        val userLocation: () -> UserLocation? = { null },
        /**
         * Persists Discover's sort order across launches (iOS `@AppStorage(discoverySortOrder)`);
         * `null` disables it.
         */
        val sortOrderStore: PreferencesStore? = null,
        /**
         * Emits when an app-side contact mutation changed the table (iOS `contactsVersion`). Discover
         * reloads off it so the "Added" badges track adds made on other surfaces.
         */
        val listInvalidations: Flow<Unit> = emptyFlow(),
        /** The device's contact-table cap, interpolated into the full-table error; null when unknown. */
        val maxContacts: () -> Int? = { null },
        val discoverTrace: PersistentLogger? = null,
        /** Current localized fallback for load, clear, and delete persistence failures. */
        val loadFailedMessage: () -> String,
        /** Localized text for every contact-service failure, resolved at the Compose boundary. */
        val errorText: (Throwable) -> String,
    )

    private val _state = queryEditor.state
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()
    private val reloadFailure: (Exception) -> Unit = { error ->
        dependencies.discoverTrace?.error("Discover reload failed: ${error.message ?: error}")
        _state.update {
            it.copy(isLoading = false, hasLoadedOnce = true, errorMessage = dependencies.loadFailedMessage())
        }
    }

    private var observeJob: Job? = null
    private var invalidationsJob: Job? = null
    private var invalidationsFlow: Flow<Unit>? = null
    private val serviceEvents = ContactServiceEventBinding(scope)

    /**
     * Subscribes to [Dependencies.listInvalidations] so a contact added or removed anywhere in the app
     * refreshes both the node rows and the "Added" badge set, matching the iOS `contactsVersion` reload.
     * The store snapshot alone cannot carry this: the contact table is a different source. Rebinds when
     * [invalidations] changes and otherwise keeps the existing subscription.
     */
    fun bindListInvalidations(invalidations: Flow<Unit>? = null) {
        val current = invalidations ?: dependencies.listInvalidations
        if (invalidationsFlow === current) return
        invalidationsJob?.cancel()
        invalidationsFlow = current
        invalidationsJob = scope.launch {
            current.collect {
                runReportingException<Unit>(onFailure = reloadFailure) {
                    load()
                }
            }
        }
    }

    /**
     * Subscribes to [ContactService.events] so a device 0x80 auto-add refreshes Added badges,
     * matching iOS `contactsVersion`. Idempotent when the service identity is unchanged.
     */
    suspend fun bindServiceEvents() {
        serviceEvents.bind(dependencies.contactService()) { event ->
            if (event.reloadsContactUi) {
                runReportingException<Unit>(onFailure = reloadFailure) {
                    load()
                }
            }
        }
    }

    /**
     * Subscribes to the store's snapshot so NewContact/Advertisement upserts appear live.
     * Cancels any previous subscription.
     */
    fun startObserving() {
        observeJob?.cancel()
        val store = dependencies.store() ?: return
        observeJob = scope.launch {
            store.snapshot.collect { all ->
                val radioID = dependencies.radioId() ?: return@collect
                val nodes = all.filter { it.radioID == radioID }
                    .sortedByDescending { it.lastHeardEpochSeconds }
                _state.update {
                    it.copy(
                        nodes = nodes,
                        hasLoadedOnce = true,
                        isLoading = false,
                    )
                }
            }
        }
    }

    /** Stops the store, invalidation, and service-event collectors owned by this screen. */
    fun close() {
        observeJob?.cancel()
        observeJob = null
        invalidationsJob?.cancel()
        invalidationsJob = null
        invalidationsFlow = null
        serviceEvents.close()
    }

    suspend fun load() {
        val store = dependencies.store()
        val radioID = dependencies.radioId()
        if (store == null || radioID == null) {
            // Discover is reachable while disconnected, and there is no radio partition to read then.
            // Marking the list loaded shows the empty-state guidance instead of a blank scroll area,
            // matching how the contact list handles a null radio.
            _state.update {
                it.copy(
                    nodes = emptyList(),
                    addedPublicKeyHexes = emptySet(),
                    isLoading = false,
                    hasLoadedOnce = true,
                )
            }
            return
        }
        _state.update { it.copy(isLoading = true) }
        runReportingException<Unit>(
            onFailure = { error ->
                dependencies.discoverTrace?.error(
                    "B4 view reload FAILED radio=$radioID: ${error.message ?: error}",
                )
                _state.update {
                    it.copy(isLoading = false, hasLoadedOnce = true, errorMessage = dependencies.loadFailedMessage())
                }
            },
        ) {
            val nodes = store.list(radioID)
            val keys = dependencies.contactService()?.getContacts(radioID)
                ?.map { it.publicKey.toUppercaseHex() }
                ?.toSet()
                .orEmpty()
            _state.update {
                it.copy(
                    nodes = nodes,
                    addedPublicKeyHexes = keys,
                    isLoading = false,
                    hasLoadedOnce = true,
                    errorMessage = null,
                )
            }
            dependencies.discoverTrace?.info(
                "B4 view reload loaded=${nodes.size} addedKeys=${keys.size} radio=$radioID",
            )
        }
    }

    suspend fun clearAll() {
        val store = dependencies.store() ?: return
        val radioID = dependencies.radioId() ?: return
        // iOS `clearAllDiscoveredNodes` sets `errorMessage` and leaves the list in place.
        runReportingException<Unit>(
            onFailure = { _ -> _state.update { it.copy(errorMessage = dependencies.loadFailedMessage()) } },
        ) {
            store.clear(radioID)
            load()
        }
    }

    /** Removes one discovered row, then refreshes. Mirrors iOS `DiscoveryViewModel.deleteDiscoveredNode`. */
    suspend fun deleteNode(node: DiscoveredNodeRecord) {
        val store = dependencies.store() ?: return
        // iOS `deleteDiscoveredNode` sets `errorMessage` when the persist write fails.
        runReportingException<Unit>(
            onFailure = { _ -> _state.update { it.copy(errorMessage = dependencies.loadFailedMessage()) } },
        ) {
            store.remove(node.radioID, node.publicKey)
            load()
        }
    }

    /**
     * Adds [node] to the radio contact table via [ContactService.addOrUpdateContact], then refreshes
     * the Added badge set. Mirrors iOS DiscoveryView.addNode.
     */
    suspend fun addNode(node: DiscoveredNodeRecord) {
        if (!dependencies.isRadioConnected()) return
        val service = dependencies.contactService() ?: return
        _state.update { it.copy(addingNodeId = node.id, errorMessage = null, addError = null) }
        try {
            runDiscoveredNodeAdd(
                maxContacts = dependencies.maxContacts,
                errorText = dependencies.errorText,
                onError = { addError -> _state.update { it.copy(addError = addError) } },
            ) {
                service.addOrUpdateContact(
                    radioID = node.radioID,
                    frame = node.toContactFrame(nowEpochSeconds()),
                )
                load()
            }
        } finally {
            _state.update { it.copy(addingNodeId = null) }
        }
    }

    /** Dismisses the add/load error alert (iOS `.errorAlert` binding set to nil). */
    fun clearError() {
        _state.update { it.copy(errorMessage = null, addError = null) }
    }
}
