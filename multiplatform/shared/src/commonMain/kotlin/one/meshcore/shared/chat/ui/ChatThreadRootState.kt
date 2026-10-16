package one.meshcore.shared.chat.ui

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.contact.ContactServiceEvent
import one.meshcore.shared.model.Uuid

/** A live preference flow, or the empty stand-in when the thread has no store for it. */
internal fun <T> stateOrFallback(state: StateFlow<T>?, fallback: StateFlow<T>): StateFlow<T> = state ?: fallback

/** A channel slot that has never been reassigned reads as generation zero. */
internal fun slotGenerationFor(generations: Map<ChatConversationID, Int>, id: ChatConversationID): Int =
    generations[id] ?: 0

/** A missing node name is an empty composer identity, not a blank the UI has to special-case later. */
internal fun chatEnvUserName(name: String?): String = name ?: ""

/** Map thumbnails stay off when the channel suppresses them, even if the global toggle is on. */
internal fun chatEnvShowsMapPreviews(enabled: Boolean, suppressed: Boolean): Boolean = enabled && !suppressed

/**
 * A zero refresh signal is the initial state, so it does not reload. A missing contact has nothing
 * to refresh. A failed load leaves the row that is already on screen.
 */
internal suspend fun <T> refreshLiveContact(id: Uuid?, signal: Int, load: suspend (Uuid) -> T?, assign: (T) -> Unit) {
    if (id == null || signal == 0) return
    val loaded = load(id) ?: return
    currentCoroutineContext().ensureActive()
    assign(loaded)
}

/**
 * A null event channel means there is no contact service. Only a cleared conversation reloads the thread.
 * Consumes the subscription, cancelling it when the collector ends or its callback fails.
 */
internal suspend fun collectClearedContacts(
    events: ReceiveChannel<ContactServiceEvent>?,
    onCleared: suspend (Uuid) -> Unit,
) {
    val live = events ?: return
    live.consumeEach { event ->
        if (event is ContactServiceEvent.MessagesCleared) onCleared(event.contactID)
    }
}

/**
 * Collectors bind only while the connection can deliver thread events. A thread that already has a
 * page refreshes that window; a first open leaves the initial load to the other effect.
 */
internal suspend fun startThreadCollection(
    canCollect: Boolean,
    isLoaded: Boolean,
    start: suspend () -> Unit,
    refresh: suspend () -> Unit,
) {
    if (canCollect) {
        start()
        if (isLoaded) refresh()
    }
}
