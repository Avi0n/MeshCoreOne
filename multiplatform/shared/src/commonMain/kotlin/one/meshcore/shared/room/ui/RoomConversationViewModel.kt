package one.meshcore.shared.room.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.chat.ChatConstants
import one.meshcore.shared.chat.ComposerState
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.canPost
import one.meshcore.shared.room.RoomConversationController
import one.meshcore.shared.room.RoomConversationInputs
import one.meshcore.shared.room.RoomNotificationSink
import one.meshcore.shared.room.RoomServerService
import one.meshcore.shared.room.fetchSession
import one.meshcore.shared.room.hasSameRoomIdentity

/**
 * Observable shell over [RoomConversationController]: load, send, retry, and compose text. The controller
 * owns the message list and send pipeline; this view model publishes a [StateFlow] for Compose.
 */
class RoomConversationViewModel private constructor(private val parts: RoomConversationParts) :
    RoomConversationLifecycle by parts.lifecycle,
    RoomConversationComposer by parts.sender,
    RoomConversationScroll by parts.scroll {
    val state: StateFlow<RoomConversationState>
        get() = parts.model.state

    /** Latest session snapshot for action availability and info sheets. */
    val currentSession: RemoteNodeSessionDto
        get() = parts.model.currentSession

    /** Applies a fresh row to the mounted conversation while preserving its messages and draft. */
    fun adoptSessionSnapshot(snapshot: RemoteNodeSessionDto) {
        if (currentSession.hasSameRoomIdentity(snapshot) && parts.model.controller.adoptSessionSnapshot(snapshot)) {
            parts.model.publish()
        }
    }

    /** Moves graph-change observation to a replacement host flow without resetting conversation state. */
    fun bindServicesChanged(flow: Flow<Any?>) = parts.lifecycle.bindServicesChanged(flow)

    constructor(
        scope: CoroutineScope,
        session: RemoteNodeSessionDto,
        dependencies: Dependencies,
    ) : this(RoomConversationParts(scope, session, dependencies))

    override fun dispose() {
        parts.lifecycle.dispose()
        parts.sender.close()
        parts.model.controller.close()
    }

    data class Dependencies(
        val roomServerService: () -> RoomServerService?,
        val fetchSession: (suspend (Uuid) -> RemoteNodeSessionDto?)? = null,
        /** Notification hooks for the room on screen; [RoomNotificationSink.NONE] when unwired. */
        val notifications: RoomNotificationSink = RoomNotificationSink.NONE,
        /**
         * Ticks whenever the per-connection service graph may have been replaced. The container is rebuilt
         * on every connect, so a room left on screen across a reconnect would otherwise keep collecting the
         * dead container's event flow; a `SharedFlow` never completes, so that subscription hangs silently
         * instead of failing. Default never emits, which pins the view model to one container for hosts
         * that have no connection of their own.
         */
        val servicesChanged: Flow<Any?> = emptyFlow(),
        /**
         * Localized text for a room-server failure, resolved at composition because a view model cannot
         * read string resources. Returns null when nothing shipped describes it.
         */
        val errorText: (Throwable) -> String? = { null },
        /** Current localized fallback when a load fault is not a mapped room-server error. */
        val loadFailedMessage: () -> String,
        /** Current localized fallback when a send or retry fault is not a mapped room-server error. */
        val sendFailedMessage: () -> String,
        /**
         * Reloads the conversation list after mark-read so unread badges match the store.
         * Defaults to a no-op for headless tests.
         */
        val onViewed: (suspend (Uuid) -> Unit)? = null,
        /**
         * The radio must be Ready, matching iOS `ChatInputBar.canSend`. Room `canPost` can stay true
         * while BLE is still connecting.
         */
        val isRadioReady: () -> Boolean = { true },
    )
}

private class RoomConversationParts(
    scope: CoroutineScope,
    session: RemoteNodeSessionDto,
    dependencies: RoomConversationViewModel.Dependencies,
) {
    val model = RoomConversationModel(
        controller = RoomConversationController(
            RoomConversationInputs(
                roomServerService = dependencies.roomServerService,
                scope = scope,
                fetchSession = dependencies.fetchSession
                    ?: { id -> dependencies.roomServerService()?.fetchSession(id) },
                onViewed = dependencies.onViewed,
                errorText = dependencies.errorText,
                loadFailedMessage = dependencies.loadFailedMessage,
                sendFailedMessage = dependencies.sendFailedMessage,
            ),
        ),
        session = session,
    )
    val scroll: RoomConversationScroll = RoomConversationScroller(model)
    val sender = RoomConversationSender(
        scope,
        session,
        dependencies,
        model,
    )
    val lifecycle = RoomConversationPresence(
        scope,
        session,
        dependencies,
        model,
    )
}

/** Render state for the room conversation screen. */
data class RoomConversationState(
    val messages: List<RoomMessageDto> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val isSending: Boolean = false,
    val composingText: String = "",
    val errorMessage: String? = null,
    val sessionName: String = "",
    val isConnected: Boolean = false,
    /** ACL can-post (read-write or admin); the screen also requires [isConnected] for the composer. */
    val canPost: Boolean = false,
    val permissionLevel: RoomPermissionLevel = RoomPermissionLevel.GUEST,
    val isAtBottom: Boolean = true,
    val unreadWhileScrolledUp: Int = 0,
    val scrollToBottomVersion: Int = 0,
    val isCoolingDown: Boolean = false,
    val focusRequest: Int = 0,
) {
    val composer: ComposerState
        get() = ComposerState(
            text = composingText,
            maxBytes = ChatConstants.MAX_DIRECT_MESSAGE_LENGTH_BYTES,
            isCoolingDown = isCoolingDown,
            focusRequest = focusRequest,
        )
    companion object {
        fun fromSession(session: RemoteNodeSessionDto): RoomConversationState = RoomConversationState(
            sessionName = session.name,
            isConnected = session.isConnected,
            canPost = session.canPost,
            permissionLevel = session.permissionLevel,
        )
    }
}
