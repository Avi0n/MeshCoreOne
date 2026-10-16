package one.meshcore.shared.chat

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runReportingException

/**
 * Re-runs the failed-send query and applies the keys. Does not reload contacts, channels, or rooms.
 * A generation guard drops a stale in-flight fetch after disconnect or a newer refresh, matching iOS
 * `refreshFailedSendIndicators`.
 */
suspend fun ConversationListViewModel.refreshFailedSendIndicators() {
    failedSendRefreshGeneration += 1
    val generation = failedSendRefreshGeneration
    val store = dependencies.store()
    val radioID = dependencies.radioId()
    if (store == null || radioID == null) {
        listState.update { it.copy(failedSendConversationIDs = emptySet()) }
        return
    }
    val ids: Set<Uuid>? = runReportingException(onFailure = { _ -> null }) {
        store.fetchFailedSendConversationKeys(radioID).allIDs
    }
    if (ids != null && generation == failedSendRefreshGeneration) {
        listState.update { it.copy(failedSendConversationIDs = ids) }
    }
}

/**
 * Reloads contacts, channels, and rooms for the live radio into one consistent snapshot, then loads
 * the last-message previews. Clears rows when no radio is available. Only the contacts fetch surfaces an error;
 * channel/room fetch failures stay silent (channels/rooms are optional), matching the iOS reload.
 */
suspend fun ConversationListViewModel.reload() = reloadMutex.withLock {
    val store = dependencies.store()
    val radioID = dependencies.radioId()
    if (store == null || radioID == null) {
        // No live or last-connected radio: drop the previous snapshot so a forgotten device
        // cannot leave its rows on screen. Mirrors iOS `clearConversations`.
        loadedRadioID = null
        listState.update {
            it.copy(
                contacts = emptyList(),
                retainingDirectContactIDs = null,
                channels = emptyList(),
                rooms = emptyList(),
                previews = emptyMap(),
                pendingRemovalIds = emptySet(),
                deletingIds = emptySet(),
                failedSendConversationIDs = emptySet(),
                isLoading = false,
                hasLoadedOnce = true,
            ).recomputed()
        }
        return@withLock
    }
    listState.update { it.copy(isLoading = true, errorMessage = null) }

    var loadError: String? = null
    val fetchedContacts: List<ContactDto>? = runReportingException(
        onFailure = { error ->
            loadError = messages.userFacing(error, messages.loadConversationsFailed)
            null
        },
    ) { store.fetchConversationContacts(radioID) }
    val fetchedChannels: List<ChannelDto>? = runReportingException(onFailure = { _ -> null }) {
        store.fetchChannels(radioID)
    }
    val fetchedRooms = fetchRoomRows(store, radioID)
    val contactIDs: Set<Uuid>? = runReportingException(onFailure = { _ -> null }) {
        store.fetchContactIDs(radioID)
    }
    val failedSendIds = fetchFailedSendIds(store, radioID).orEmpty()

    currentCoroutineContext().ensureActive()
    if (!ownsReload(store, radioID)) return@withLock
    val keepPreviousRows = loadedRadioID == radioID
    loadedRadioID = radioID
    listState.update { previous ->
        val current = previous.rowsForRadio(keepPreviousRows)
        current.copy(
            contacts = fetchedContacts ?: current.contacts,
            channels = fetchedChannels ?: current.channels,
            rooms = fetchedRooms ?: current.rooms,
            retainingDirectContactIDs = contactIDs,
            failedSendConversationIDs = failedSendIds,
            errorMessage = loadError,
            isLoading = false,
            hasLoadedOnce = true,
        ).reconciledPendingRemovals().recomputed()
    }

    if (ownsReload(store, radioID)) loadLastMessagePreviews(store, radioID)
}

/** A failed room fetch returns null so reload keeps the prior rooms. Cancellation still propagates. */
private suspend fun fetchRoomRows(store: ConversationListStore, radioID: Uuid) =
    runReportingException(onFailure = { _ -> null }) { store.fetchRooms(radioID) }

/** A failed badge query returns null, and reload treats that as no badges. Cancellation leaves the badges. */
private suspend fun fetchFailedSendIds(store: ConversationListStore, radioID: Uuid): Set<Uuid>? =
    runReportingException(onFailure = { _ -> null }) { store.fetchFailedSendConversationKeys(radioID).allIDs }

/** A failed read can retain this radio's rows, but cannot borrow another radio's buffers. */
private fun ConversationListState.rowsForRadio(keepPreviousRows: Boolean): ConversationListState =
    if (keepPreviousRows) {
        this
    } else {
        copy(
            contacts = emptyList(),
            channels = emptyList(),
            rooms = emptyList(),
            previews = emptyMap(),
            pendingRemovalIds = emptySet(),
            deletingIds = emptySet(),
            channelDeleteFailure = null,
        )
    }
