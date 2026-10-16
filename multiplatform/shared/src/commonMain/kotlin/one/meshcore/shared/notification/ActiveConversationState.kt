package one.meshcore.shared.notification

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.chat.ActiveChannel
import one.meshcore.shared.chat.ActiveConversation
import one.meshcore.shared.model.Uuid

/**
 * The conversation currently on screen, the single source both the ingest unread-suppression seam
 * ([ActiveConversation], read by [one.meshcore.shared.chat.MessageIngestPipeline]) and the notification
 * banner-suppression gate read. The open thread sets it through [setActiveContact] /
 * [setActiveChannel] / [setActiveRoom], each of which atomically populates one slot and clears the other
 * two because exactly one conversation detail is visible at a time (Swift
 * `NotificationService.setActiveConversation`, which assigns all three slots in one call).
 */
class ActiveConversationState : ActiveConversation {
    private val mutex = Mutex()
    private var contactID: Uuid? = null
    private var channel: ActiveChannel? = null
    private var roomSessionID: Uuid? = null

    override suspend fun activeContactID(): Uuid? = mutex.withLock { contactID }

    override suspend fun activeChannel(): ActiveChannel? = mutex.withLock { channel }

    /** The room session on screen, read by the room posting gate. Null when no room is open. */
    suspend fun activeRoomSessionID(): Uuid? = mutex.withLock { roomSessionID }

    /** Marks [contactID]'s DM thread active, clearing any active channel or room. Null clears the slot. */
    suspend fun setActiveContact(contactID: Uuid?) = mutex.withLock {
        this.contactID = contactID
        this.channel = null
        this.roomSessionID = null
    }

    /** Marks [channel] active, clearing any active contact or room. Null clears the channel slot. */
    suspend fun setActiveChannel(channel: ActiveChannel?) = mutex.withLock {
        this.channel = channel
        this.contactID = null
        this.roomSessionID = null
    }

    /** Marks [sessionID]'s room active, clearing any active contact or channel. Null clears the slot. */
    suspend fun setActiveRoom(sessionID: Uuid?) = mutex.withLock {
        this.roomSessionID = sessionID
        this.contactID = null
        this.channel = null
    }

    /** Clears every slot (the thread left the screen with nothing replacing it). */
    suspend fun clear() = mutex.withLock {
        contactID = null
        channel = null
        roomSessionID = null
    }
}
