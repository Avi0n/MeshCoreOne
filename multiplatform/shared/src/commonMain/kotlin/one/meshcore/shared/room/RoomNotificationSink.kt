package one.meshcore.shared.room

import one.meshcore.shared.model.Uuid

/**
 * The notification hooks an open room conversation drives, the room analogue of
 * `one.meshcore.shared.chat.ActiveConversationSink`. Claiming the slot both suppresses banners for the
 * room on screen and clears what its tray already delivered; releasing it hands notifications back.
 */
interface RoomNotificationSink {
    /** Marks [sessionID] as the conversation on screen, clearing its delivered rows and the badge. */
    suspend fun setActiveRoom(sessionID: Uuid)

    /**
     * Releases the slot [sessionID] holds as the screen tears down. Not suspending, and a no-op when a
     * newer room already claimed the slot: the screen's coroutine scope is cancelled by the time this
     * runs, so the implementation dispatches on a longer-lived one.
     */
    fun clearActiveRoomAsync(sessionID: Uuid)

    companion object {
        /** The disconnected default: a host that has not wired the notification layer posts nothing. */
        val NONE: RoomNotificationSink = object : RoomNotificationSink {
            override suspend fun setActiveRoom(sessionID: Uuid) = Unit

            override fun clearActiveRoomAsync(sessionID: Uuid) = Unit
        }
    }
}
