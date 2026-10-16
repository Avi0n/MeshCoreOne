package one.meshcore.shared.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.model.Uuid

/**
 * Per-slot generation iOS `AppState.channelSlotGenerations` bumps when a channel occupant changes, so
 * an already-composed thread can empty its composer and leave instead of flushing the old draft onto
 * the next occupant.
 */
class ChannelSlotGenerationTracker {
    private val _generations = MutableStateFlow<Map<ChatConversationID, Int>>(emptyMap())

    val generations: StateFlow<Map<ChatConversationID, Int>> = _generations.asStateFlow()

    fun generation(id: ChatConversationID): Int = _generations.value[id] ?: 0

    fun bump(radioID: Uuid, indices: Set<Int>) {
        if (indices.isEmpty()) return
        _generations.update { current ->
            val next = current.toMutableMap()
            for (index in indices) {
                val id = ChatConversationID.channel(radioID, index)
                next[id] = (next[id] ?: 0) + 1
            }
            next
        }
    }
}
