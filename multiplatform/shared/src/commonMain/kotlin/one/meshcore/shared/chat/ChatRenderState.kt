package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/**
 * Immutable snapshot of the chat timeline as the view renders it. Never mutated in place — every change
 * returns a new value via `copy`, and the single-item helpers keep [itemIndexByID] in step. Mirrors the
 * Swift `ChatRenderState`.
 *
 * [phase] is a tri-state so "not loaded yet" is distinct from "loaded and empty": the empty-state
 * placeholder gates on `phase == LOADED && items.isEmpty()`, so a freshly-bound coordinator whose first
 * fetch is in flight never flashes "No messages".
 */
data class ChatRenderState(
    val items: List<MessageItem>,
    val itemIndexByID: Map<Uuid, Int>,
    val hasMoreMessages: Boolean,
    val isLoadingOlder: Boolean,
    val totalFetchedCount: Int,
    val phase: LoadPhase = LoadPhase.UNINITIALIZED,
) {
    enum class LoadPhase {
        UNINITIALIZED,
        LOADING,
        LOADED,
    }

    /**
     * Replaces a single item by id. No-op (returns `this`) when the id is absent or [transform] returns an
     * equal item, so the caller's identity guard short-circuits instead of walking every row.
     */
    fun updatingItem(id: Uuid, transform: (MessageItem) -> MessageItem): ChatRenderState {
        val index = itemIndexByID[id] ?: return this
        val updated = transform(items[index])
        if (updated == items[index]) return this
        val newItems = items.toMutableList().also { it[index] = updated }
        return copy(items = newItems)
    }

    /** Removes a single item by id, rebuilding [itemIndexByID] since positions shift. No-op if absent. */
    fun removingItem(id: Uuid): ChatRenderState {
        if (!itemIndexByID.containsKey(id)) return this
        val newItems = items.filterNot { it.id == id }
        return copy(items = newItems, itemIndexByID = newItems.indexByID())
    }

    /** Appends a single item and extends [itemIndexByID]. The caller ensures the id is not already present. */
    fun appendingItem(item: MessageItem): ChatRenderState {
        val newItems = items + item
        val newIndex = itemIndexByID + (item.id to newItems.size - 1)
        return copy(
            items = newItems,
            itemIndexByID = newIndex,
            totalFetchedCount = totalFetchedCount + 1,
        )
    }

    companion object {
        val EMPTY = ChatRenderState(
            items = emptyList(),
            itemIndexByID = emptyMap(),
            hasMoreMessages = true,
            isLoadingOlder = false,
            totalFetchedCount = 0,
            phase = LoadPhase.UNINITIALIZED,
        )
    }
}
