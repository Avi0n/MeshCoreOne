package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/**
 * Generation-stamped write capability for a [ChatCoordinator] timeline. Minted only by
 * [ChatCoordinator.bindWriter]; once a newer writer is bound, mutations here no-op so a stale
 * prime cannot write over an open conversation. Mirrors iOS `ChatTimelineWriter`.
 */
class ChatTimelineWriter private constructor(private val parts: Parts, val role: ChatWriterRole) :
    ChatTimelineMessages by parts.messages,
    ChatTimelineRender by parts.render {
    internal constructor(
        coordinator: ChatCoordinator,
        generation: ULong,
        role: ChatWriterRole,
    ) : this(Parts(coordinator, generation), role)

    /** Whether this writer still holds write access. */
    val isCurrent: Boolean
        get() = parts.isCurrent

    private class Parts(private val coordinator: ChatCoordinator, private val generation: ULong) {
        val isCurrent: Boolean
            get() = generation == coordinator.writerGeneration

        val messages: ChatTimelineMessages = Messages()
        val render: ChatTimelineRender = Render()

        private inline fun ifCurrent(body: () -> Unit) {
            if (!isCurrent) return
            body()
        }

        private inner class Messages : ChatTimelineMessages {
            override fun replaceAll(newMessages: List<MessageDto>) {
                ifCurrent { coordinator.replaceAll(newMessages) }
            }

            override fun prepend(older: List<MessageDto>) {
                ifCurrent { coordinator.prepend(older) }
            }

            override fun append(message: MessageDto): Boolean {
                var appended = false
                ifCurrent { appended = coordinator.append(message) }
                return appended
            }

            override fun update(messageID: Uuid, transform: (MessageDto) -> MessageDto) {
                ifCurrent { coordinator.update(messageID, transform) }
            }

            override fun remove(messageID: Uuid) {
                ifCurrent { coordinator.remove(messageID) }
            }

            override fun replaceMessagesPreservingByID(reordered: List<MessageDto>) {
                ifCurrent { coordinator.replaceMessagesPreservingByID(reordered) }
            }

            override fun applyStatusUpdate(
                messageID: Uuid,
                status: MessageStatus,
                roundTripTime: Long?,
                userInitiated: Boolean,
            ) {
                ifCurrent {
                    coordinator.applyStatusUpdate(messageID, status, roundTripTime, userInitiated)
                }
            }

            override fun applyMentionSeen(messageID: Uuid) {
                ifCurrent { coordinator.applyMentionSeen(messageID) }
            }

            override fun enqueueReload(updatedMessageIDs: Set<Uuid>) {
                ifCurrent { coordinator.enqueueReload(updatedMessageIDs) }
            }

            override fun enqueueReload(messageID: Uuid) {
                ifCurrent { coordinator.enqueueReload(messageID) }
            }
        }

        private inner class Render : ChatTimelineRender {
            override fun updateRenderState(transform: (ChatRenderState) -> ChatRenderState) {
                ifCurrent { coordinator.updateRenderState(transform) }
            }

            override fun beginLoading() {
                ifCurrent { coordinator.beginLoading() }
            }

            override fun markLoaded() {
                ifCurrent { coordinator.markLoaded() }
            }

            override fun appendRenderItem(item: MessageItem) {
                ifCurrent { coordinator.appendRenderItem(item) }
            }

            override fun updateRenderItem(id: Uuid, transform: (MessageItem) -> MessageItem) {
                ifCurrent { coordinator.updateRenderItem(id, transform) }
            }

            override fun removeRenderItem(id: Uuid) {
                ifCurrent { coordinator.removeRenderItem(id) }
            }

            override fun rebuildItems(
                inputs: List<Pair<MessageDto, MessageBuildInputs>>,
                envInputs: EnvInputs,
                postApply: (() -> Unit)?,
            ) {
                ifCurrent { coordinator.rebuildItems(inputs, envInputs, postApply) }
            }

            override suspend fun <T> performWindowOperation(operation: suspend () -> T): T =
                coordinator.performWindowOperation(operation)
        }
    }
}

/** Message-row writes. A stale writer no-ops instead of mutating the open conversation. */
interface ChatTimelineMessages {
    fun replaceAll(newMessages: List<MessageDto>)
    fun prepend(older: List<MessageDto>)
    fun append(message: MessageDto): Boolean
    fun update(messageID: Uuid, transform: (MessageDto) -> MessageDto)
    fun remove(messageID: Uuid)
    fun replaceMessagesPreservingByID(reordered: List<MessageDto>)
    fun applyStatusUpdate(
        messageID: Uuid,
        status: MessageStatus,
        roundTripTime: Long? = null,
        userInitiated: Boolean = false,
    )
    fun applyMentionSeen(messageID: Uuid)
    fun enqueueReload(updatedMessageIDs: Set<Uuid>)
    fun enqueueReload(messageID: Uuid)
}

/** Render-item writes and the window operation that is not generation-gated at enqueue. */
interface ChatTimelineRender {
    /**
     * Direct render-state assignment for pagination fields and single-row updates that already
     * keep the canonical messages array consistent. Bumps `renderStateID` so an in-flight item
     * build is discarded. Mirrors iOS `ChatTimelineWriter.updateRenderState`.
     */
    fun updateRenderState(transform: (ChatRenderState) -> ChatRenderState)
    fun beginLoading()
    fun markLoaded()
    fun appendRenderItem(item: MessageItem)
    fun updateRenderItem(id: Uuid, transform: (MessageItem) -> MessageItem)
    fun removeRenderItem(id: Uuid)
    fun rebuildItems(
        inputs: List<Pair<MessageDto, MessageBuildInputs>>,
        envInputs: EnvInputs,
        postApply: (() -> Unit)? = null,
    )

    /**
     * Chains on prior populate / loadOlder / hardReset. Not generation-gated at enqueue;
     * mutations inside already no-op when this writer is stale.
     */
    suspend fun <T> performWindowOperation(operation: suspend () -> T): T
}
