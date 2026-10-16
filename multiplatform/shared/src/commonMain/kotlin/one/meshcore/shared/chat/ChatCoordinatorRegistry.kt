package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Resolves the one [ChatCoordinator] shared by every UI surface bound to a conversation, keyed on
 * [ChatConversationID]. Process-lifetime per radio, matching iOS `AppState.chatCoordinatorRegistry`.
 * [tearDown] empties entries on restore and forget, not on a routine disconnect.
 *
 * Capped by an LRU (default [DEFAULT_CAPACITY]) so a long session across many conversations cannot
 * keep every first page in memory. Evicted coordinators cancel in-flight builds. A [coordinator]
 * read promotes; [existingCoordinator] does not, so a closed-chat refresh can skip cold conversations
 * without polluting the cache.
 *
 * Confined to the same single dispatcher as the coordinators it builds (see [ChatCoordinator]); the map is
 * touched only there.
 */
class ChatCoordinatorRegistry(
    private val store: ChatCoordinatorStore,
    private val scope: CoroutineScope,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val coordinators = LinkedHashMap<ChatConversationID, ChatCoordinator>()

    /** The shared coordinator for [conversationID], created on first request and reused thereafter. */
    fun coordinator(conversationID: ChatConversationID): ChatCoordinator {
        coordinators.remove(conversationID)?.let { existing ->
            coordinators[conversationID] = existing
            return existing
        }
        val created = ChatCoordinator(conversationID, store, scope, computeDispatcher)
        coordinators[conversationID] = created
        while (coordinators.size > capacity) {
            val oldest = coordinators.keys.first()
            coordinators.remove(oldest)?.cancelInFlight()
        }
        return created
    }

    /**
     * The coordinator already tracked for [conversationID], or null if none exists. A pure lookup: it
     * neither creates an entry nor promotes order, so a closed-chat refresh can skip cold conversations.
     */
    fun existingCoordinator(conversationID: ChatConversationID): ChatCoordinator? = coordinators[conversationID]

    /**
     * Evicts the coordinator for [conversationID], cancelling its in-flight builds. No-op when absent.
     * Mirrors iOS `ChatCoordinatorRegistry.remove(for:)`.
     */
    fun remove(conversationID: ChatConversationID) {
        coordinators.remove(conversationID)?.cancelInFlight()
    }

    /** Cancel every coordinator's in-flight work and drop all instances. */
    fun tearDown() {
        coordinators.values.forEach { it.cancelInFlight() }
        coordinators.clear()
    }

    companion object {
        /** Matches iOS `ChatCoordinatorRegistry.defaultCapacity`. */
        const val DEFAULT_CAPACITY = 16
    }
}
