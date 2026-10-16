package one.meshcore.shared.chat

import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/** A failed channel row with the caller's repeat counts, so a resend test can vary only the fault. */
internal suspend fun savedFailedChannel(store: InMemoryMessageStore, heardRepeats: Int, sendCount: Int): Uuid {
    val id = ChatFixtures.freshUuid()
    store.saveMessage(
        ChatFixtures.message(id = id, status = MessageStatus.FAILED, contactID = null, channelIndex = 0)
            .copy(heardRepeats = heardRepeats, sendCount = sendCount),
    )
    return id
}
