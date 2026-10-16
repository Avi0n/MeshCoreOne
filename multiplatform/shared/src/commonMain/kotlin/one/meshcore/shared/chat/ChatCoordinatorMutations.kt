package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.Uuid

/** Replace the whole canonical list. Rebuilds the lookup, settles [phase] to LOADED, bumps the counter. */
fun ChatCoordinator.replaceAll(newMessages: List<MessageDto>) {
    messages = newMessages
    messagesByID = newMessages.associateBy { it.id }
    if (renderState.phase != ChatRenderState.LoadPhase.LOADED) {
        renderState = renderState.copy(phase = ChatRenderState.LoadPhase.LOADED)
    }
    bumpRenderStateID()
}

/** Move UNINITIALIZED -> LOADING; a no-op once LOADING/LOADED so a refresh never regresses to a placeholder. */
fun ChatCoordinator.beginLoading() {
    if (renderState.phase != ChatRenderState.LoadPhase.UNINITIALIZED) return
    renderState = renderState.copy(phase = ChatRenderState.LoadPhase.LOADING)
    bumpRenderStateID()
}

/** Force [phase] to LOADED. Used by error / early-return paths that never reach [replaceAll]. Idempotent. */
fun ChatCoordinator.markLoaded() {
    if (renderState.phase == ChatRenderState.LoadPhase.LOADED) return
    renderState = renderState.copy(phase = ChatRenderState.LoadPhase.LOADED)
    bumpRenderStateID()
}

/** Insert older messages at the head, skipping ids already present so a concurrent append can't duplicate. */
fun ChatCoordinator.prepend(older: List<MessageDto>) {
    if (older.isEmpty()) return
    val filtered = older.filter { !messagesByID.containsKey(it.id) }
    if (filtered.isEmpty()) return
    messages = filtered + messages
    messagesByID = messagesByID + filtered.associateBy { it.id }
    bumpRenderStateID()
}

/** Append a message unless its id is already known. Returns whether it was inserted. */
fun ChatCoordinator.append(message: MessageDto): Boolean {
    if (messagesByID.containsKey(message.id)) return false
    messages = messages + message
    messagesByID = messagesByID + (message.id to message)
    bumpRenderStateID()
    return true
}

/** Apply an in-place transform to the message with [messageID]. No-op on a missing id. */
fun ChatCoordinator.update(messageID: Uuid, transform: (MessageDto) -> MessageDto) {
    val index = messages.indexOfFirst { it.id == messageID }
    if (index < 0) return
    val updated = transform(messages[index])
    messages = messages.toMutableList().also { it[index] = updated }
    messagesByID = messagesByID + (messageID to updated)
    bumpRenderStateID()
}

/** Remove a message by id. No-op if absent. */
fun ChatCoordinator.remove(messageID: Uuid) {
    if (!messagesByID.containsKey(messageID)) return
    messages = messages.filterNot { it.id == messageID }
    messagesByID = messagesByID - messageID
    bumpRenderStateID()
}

/** Reassign the whole list after a same-sender reorder pass; rebuilds the lookup. */
fun ChatCoordinator.replaceMessagesPreservingByID(reordered: List<MessageDto>) {
    messages = reordered
    messagesByID = reordered.associateBy { it.id }
    bumpRenderStateID()
}

internal fun ChatCoordinator.bumpRenderStateID() {
    renderStateID += 1uL
}
