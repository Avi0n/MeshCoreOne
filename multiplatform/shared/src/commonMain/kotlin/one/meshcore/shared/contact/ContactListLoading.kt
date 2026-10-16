package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runExceptionFallback
import one.meshcore.shared.runReportingException

/** Loads the nodes list and refreshes it from the radio or a local invalidation. */
interface ContactListLoading {
    /**
     * Subscribes to [ContactService.events] for the live service so device-driven contact table
     * changes refresh the list. Idempotent when the service identity is unchanged; rebinds when
     * [ContactListViewModel.Dependencies.contactService] returns a new instance after reconnect. Suspends until the
     * multicast subscription is registered so the first device push is not missed.
     */
    suspend fun bindServiceEvents()

    /**
     * Subscribes to [ContactListViewModel.Dependencies.listInvalidations] so a contact added or removed by the app
     * itself (Add Contact, QR scan, Discover "Add", node-config import) appears without a manual refresh. Those
     * paths bump the app-wide invalidation instead of emitting a device push, so [bindServiceEvents]
     * never sees them. Rebinds when [invalidations] changes and otherwise keeps the existing subscription.
     */
    fun bindListInvalidations(invalidations: Flow<Unit>? = null)

    /**
     * Loads contacts for the live radio into [ContactListViewModel.state]. When disconnected (no radio), marks the
     * list loaded so the empty-state guidance shows rather than a perpetual spinner, mirroring iOS which renders
     * the ContactsEmptyView, not a loading indicator, when there is nothing to sync.
     */
    suspend fun load()

    /**
     * Pull-to-refresh: syncs contacts from the radio, then reloads. Blocked with [ContactListError.CannotRefresh]
     * when the radio is not ready, mirroring the iOS offline-refresh alert.
     */
    suspend fun refresh()

    /**
     * Syncs contacts from the device and reloads. Re-entrancy is claimed synchronously so a re-trigger
     * while a sync is in flight is a no-op; a cancellation (tab switch) is not reported as an error.
     */
    suspend fun syncContacts()
}

internal class ContactListLoader(private val model: ContactListModel) : ContactListLoading {
    override suspend fun bindServiceEvents() {
        if (model.isClosed) return
        model.serviceEvents.bind(model.dependencies.contactService()) { event ->
            when (event) {
                is ContactServiceEvent.ContactAdvertisementApplied,
                is ContactServiceEvent.NodeDeleted,
                -> {
                    val radioId = model.dependencies.radioId() ?: return@bind
                    reload(radioId)
                }
                else -> Unit
            }
        }
    }

    override fun bindListInvalidations(invalidations: Flow<Unit>?) {
        val current = invalidations ?: model.dependencies.listInvalidations
        if (model.isClosed || model.invalidationsFlow === current) return
        model.invalidationsJob?.cancel()
        model.invalidationsFlow = current
        model.invalidationsJob = model.scope.launch {
            current.collect {
                try {
                    val radioId = model.dependencies.radioId()
                    if (radioId == null) {
                        clearLoadedList()
                    } else {
                        reload(radioId)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A Room read must not kill later contact-list reloads.
                }
            }
        }
    }

    override suspend fun load() {
        val radioId = model.dependencies.radioId() ?: run {
            clearLoadedList()
            return
        }
        reload(radioId)
    }

    /**
     * Drops the previous radio's rows so a forgotten device cannot stay on the Nodes list.
     * Mirrors the Chats [one.meshcore.shared.chat.ConversationListViewModel] null-radio clear.
     */
    private fun clearLoadedList() {
        model.state.update {
            it.copy(
                contacts = emptyList(),
                inboundHopByKey = emptyMap(),
                pendingRemovalIds = emptySet(),
                deletingIds = emptySet(),
                isLoading = false,
                isSyncing = false,
                syncProgress = null,
                hasLoadedOnce = true,
            )
        }
    }

    override suspend fun refresh() {
        if (!model.dependencies.isReady()) {
            model.state.update { it.copy(error = ContactListError.CannotRefresh) }
            return
        }
        syncContacts()
    }

    override suspend fun syncContacts() {
        val service = model.dependencies.contactService() ?: return
        val radioId = model.dependencies.radioId() ?: return
        if (model.state.value.isSyncing) return
        model.state.update { it.copy(isSyncing = true, syncProgress = null, error = null) }

        val progressJob = model.scope.launch {
            service.events().consumeEach { event ->
                if (event is ContactServiceEvent.SyncProgress) {
                    model.state.update { it.copy(syncProgress = SyncProgress(event.received, event.total)) }
                }
            }
        }

        try {
            runReportingException(
                onFailure = { error ->
                    model.state.update {
                        it.copy(error = ContactListError.ServiceMessage(model.serviceMessage(error)))
                    }
                },
            ) {
                service.syncContactsForRefresh(radioId)
                reload(radioId)
                model.state.update { it.copy(syncProgress = null) }
            }
        } finally {
            progressJob.cancel()
            model.state.update { it.copy(isSyncing = false) }
        }
    }

    internal suspend fun reload(radioID: Uuid) {
        val service = model.dependencies.contactService() ?: return
        model.state.update { it.copy(isLoading = true, error = null) }

        // Room fetch is not a ContactServiceError. A narrow catch left isLoading true.
        val loaded: List<ContactDto>? = runReportingException(
            onFailure = { error ->
                model.state.update { it.copy(error = ContactListError.ServiceMessage(model.serviceMessage(error))) }
                null
            },
        ) {
            service.getContacts(radioID)
        }

        // Best-effort inbound-hop fallback; a failure here must not fail the contact load.
        val hops = runExceptionFallback({ emptyMap() }) {
            model.dependencies.inboundHops(radioID)
        }

        model.state.update { current ->
            val base = current.copy(inboundHopByKey = hops, hasLoadedOnce = true, isLoading = false)
            if (loaded == null) {
                base
            } else {
                val autoFavorite = !current.hasLoadedOnce &&
                    !current.userSwitchedSegment &&
                    loaded.any { it.isFavorite }
                base.copy(
                    contacts = loaded,
                    pendingRemovalIds = current.pendingRemovalIds intersect loaded.mapTo(mutableSetOf()) { it.id },
                    segment = if (autoFavorite) NodeSegment.FAVORITES else current.segment,
                )
            }
        }
    }
}
