package one.meshcore.shared.room.ui

/** Scroll position for the open room, including the pin used when the composer takes focus. */
interface RoomConversationScroll {
    /** The newest rows are in view. A return to the bottom clears the unread-while-scrolled-up count. */
    fun setNearBottom(atBottom: Boolean)

    /** Jumps the list to the newest row and clears the unread-while-scrolled-up count. */
    fun requestScrollToBottom()

    /**
     * Re-pins the newest row only while already at the bottom. Composer focus uses this so a
     * scrolled-up room stays put, matching iOS `scrollToBottomRequest`.
     */
    fun requestPinToBottom()
}

internal class RoomConversationScroller(private val model: RoomConversationModel) : RoomConversationScroll {
    override fun setNearBottom(atBottom: Boolean) {
        if (model.isAtBottom == atBottom) return
        model.isAtBottom = atBottom
        if (atBottom) model.unreadWhileScrolledUp = 0
        model.publish()
    }

    override fun requestScrollToBottom() {
        model.isAtBottom = true
        model.unreadWhileScrolledUp = 0
        model.scrollToBottomVersion += 1
        model.publish()
    }

    override fun requestPinToBottom() {
        if (!model.isAtBottom) return
        model.unreadWhileScrolledUp = 0
        model.scrollToBottomVersion += 1
        model.publish()
    }
}
