package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.Uuid

/**
 * In-memory [ChatCoordinatorStore] for the coordinator tests. [gateFirstFetch], when set, suspends the very
 * next [fetchMessage] on the given latch (and clears itself), so a test can enqueue more reload ids while a
 * drain is parked mid-fetch and prove the loop re-check coalesces them into the same drain.
 */
internal class FakeCoordinatorStore : ChatCoordinatorStore {
    private val messages = mutableMapOf<Uuid, MessageDto>()

    var directPage: List<MessageDto> = emptyList()
    var channelPage: List<MessageDto> = emptyList()
    var lastDirectLimit: Int? = null
    var lastChannelLimit: Int? = null
    var gateFirstFetch: CompletableDeferred<Unit>? = null

    fun put(message: MessageDto) {
        messages[message.id] = message
    }

    override suspend fun fetchMessage(id: Uuid): MessageDto? {
        gateFirstFetch?.let { latch ->
            gateFirstFetch = null
            latch.await()
        }
        return messages[id]
    }

    override suspend fun fetchDirectMessages(contactID: Uuid, limit: Int, offset: Int): List<MessageDto> {
        lastDirectLimit = limit
        return directPage
    }

    override suspend fun fetchChannelMessages(
        radioID: Uuid,
        channelIndex: Int,
        limit: Int,
        offset: Int,
    ): List<MessageDto> {
        lastChannelLimit = limit
        return channelPage
    }
}
