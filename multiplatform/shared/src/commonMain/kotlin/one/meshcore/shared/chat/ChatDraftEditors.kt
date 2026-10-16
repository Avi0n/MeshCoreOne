package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import one.meshcore.shared.model.Uuid

/** Process-lifetime editors, accessed on the host UI dispatcher independently of connection services. */
class ChatDraftEditors(private val store: DraftStore, private val scope: CoroutineScope) {
    private val editors = mutableMapOf<ChatConversationID, ChatDraftEditor>()
    private val clears = mutableMapOf<ChatConversationID, Deferred<Unit>>()
    private var clearTail: Deferred<Unit>? = null
    private var isShuttingDown = false

    fun editor(id: ChatConversationID): ChatDraftEditor {
        check(!isShuttingDown)
        return editors.getOrPut(id) { ChatDraftEditor(id, store, scope) { clears[id] } }
    }

    suspend fun flushAll() {
        editors.values.toList().forEach { it.flush() }
    }

    suspend fun resetChannelDrafts(slots: Map<Uuid, Set<Int>>) {
        clearChannels(slots, retire = false)
    }

    suspend fun retireChannelDrafts(radioID: Uuid, indices: Set<Int>) {
        clearChannels(mapOf(radioID to indices), retire = true)
    }

    suspend fun shutdown() {
        if (isShuttingDown) return
        isShuttingDown = true
        editors.values.forEach { it.stopAcceptingInput() }
        try {
            clearTail?.join()
            flushAll()
        } finally {
            editors.values.forEach { it.retire() }
            editors.clear()
        }
    }

    private suspend fun clearChannels(slots: Map<Uuid, Set<Int>>, retire: Boolean) {
        check(!isShuttingDown)
        val ids = slots.flatMap { (radio, indices) -> indices.map { ChatConversationID.channel(radio, it) } }
        if (ids.isEmpty()) return
        val affected = ids.mapNotNull { id ->
            val editor = if (retire) editors.remove(id) else editors[id]
            editor?.also { if (retire) it.retire() else it.reset() }
        }
        val predecessor = clearTail
        val operation = scope.async(start = CoroutineStart.LAZY) {
            predecessor?.join()
            affected.forEach { it.awaitAdmittedWrite() }
            store.clearChannelDrafts(slots, persistUnchanged = true)
        }
        ids.forEach { clears[it] = operation }
        clearTail = operation
        operation.invokeOnCompletion { failure ->
            if (failure == null) ids.forEach { id -> if (clears[id] === operation) clears.remove(id) }
        }
        operation.start()
        operation.await()
    }
}
