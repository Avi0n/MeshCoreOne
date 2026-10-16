package one.meshcore.shared.room.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.canPost
import one.meshcore.shared.room.RoomConversationController

/**
 * The conversation snapshot Compose reads, plus the scroll and cooldown fields the sender and
 * scroller share. [publish] is the only writer that folds the controller's message list into unread
 * counts.
 */
internal class RoomConversationModel(
    internal val controller: RoomConversationController,
    private val session: RemoteNodeSessionDto,
) {
    internal val stateUpdates = MutableStateFlow(RoomConversationState.fromSession(session))
    val state: StateFlow<RoomConversationState> = stateUpdates.asStateFlow()

    /** Latest session snapshot for action availability and info sheets. */
    val currentSession: RemoteNodeSessionDto
        get() = controller.session ?: session

    internal var isAtBottom = true
    internal var unreadWhileScrolledUp = 0
    private var lastMessageCount = 0
    private var lastNewestMessageId: Uuid? = null
    internal var scrollToBottomVersion = 0
    internal var isCoolingDown = false
    internal var focusRequest = 0

    fun publish() {
        val messages = controller.messages
        val count = messages.size
        val newestId = messages.lastOrNull()?.id
        if (!isAtBottom && newestId != null && newestId != lastNewestMessageId) {
            val added = (count - lastMessageCount).coerceAtLeast(0)
            if (added > 0) {
                unreadWhileScrolledUp += messages.takeLast(added).count { !it.isFromSelf }
            }
        }
        lastMessageCount = count
        lastNewestMessageId = newestId
        val live = controller.session ?: session
        stateUpdates.value = RoomConversationState(
            messages = controller.messages,
            isLoading = controller.isLoading,
            hasLoadedOnce = controller.hasLoadedOnce,
            isSending = controller.isSending,
            composingText = controller.composingText,
            errorMessage = controller.errorMessage,
            sessionName = live.name,
            isConnected = live.isConnected,
            canPost = live.canPost,
            permissionLevel = live.permissionLevel,
            isAtBottom = isAtBottom,
            unreadWhileScrolledUp = unreadWhileScrolledUp,
            scrollToBottomVersion = scrollToBottomVersion,
            isCoolingDown = isCoolingDown,
            focusRequest = focusRequest,
        )
    }
}
