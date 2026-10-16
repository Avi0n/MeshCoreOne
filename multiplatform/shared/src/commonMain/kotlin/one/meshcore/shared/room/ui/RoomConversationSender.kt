package one.meshcore.shared.room.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.chat.ChatConstants
import one.meshcore.shared.chat.ComposerState
import one.meshcore.shared.chat.ComposerViewModel
import one.meshcore.shared.lifecycle.OwnedJobs
import one.meshcore.shared.model.Uuid

/** Composer text, send, retry, and the session refresh the room screen drives. */
interface RoomConversationComposer {
    fun setComposingText(text: String)

    /**
     * Raises the composer after the actions sheet has finished dismissing, matching iOS
     * `handleReply` + `inputFocusRequest`.
     */
    fun requestComposerFocus()

    fun send()

    /** Sends [text] without touching the composer (room message Send Again). */
    fun sendText(text: String)

    fun retry(messageId: Uuid)

    /** Re-reads the session after reconnect so banners and can-post track the live ACL. */
    fun refreshSession()

    fun clearError()
}

internal class RoomConversationSender(
    scope: CoroutineScope,
    private val session: RemoteNodeSessionDto,
    private val dependencies: RoomConversationViewModel.Dependencies,
    private val model: RoomConversationModel,
) : RoomConversationComposer {
    private val controller get() = model.controller
    private var cooldownJob: Job? = null
    private val jobs = OwnedJobs(scope)

    override fun setComposingText(text: String) {
        if (jobs.isClosed) return
        controller.composingText = text
        model.stateUpdates.update { it.copy(composingText = text) }
    }

    override fun requestComposerFocus() {
        jobs.launch {
            delay(ChatConstants.MESSAGE_ACTIONS_DISMISSAL_MS)
            model.focusRequest += 1
            model.publish()
        }
    }

    override fun send() {
        if (jobs.isClosed) return
        val text = controller.composingText
        val composer = ComposerState(
            text,
            ChatConstants.MAX_DIRECT_MESSAGE_LENGTH_BYTES,
            model.isCoolingDown,
        )
        if (!composer.canSend(dependencies.isRadioReady())) return
        val trimmed = text.trim()
        model.isCoolingDown = true
        controller.composingText = ""
        if (model.isAtBottom) {
            model.unreadWhileScrolledUp = 0
            model.scrollToBottomVersion += 1
        }
        model.publish()
        startCooldown()
        jobs.launch {
            controller.sendMessage(trimmed)
            model.publish()
            notifyConversationList()
        }
    }

    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = jobs.launch {
            delay(ComposerViewModel.SEND_COOLDOWN_MS)
            model.isCoolingDown = false
            model.publish()
        }
    }

    override fun sendText(text: String) {
        if (jobs.isClosed || text.isBlank()) return
        // iOS `sendMessage` / `retryMessage` require live `roomServerService`. OfflineDataAccess still
        // returns a RoomServerService, so a disconnected Send Again would persist a pending row then fail.
        if (!dependencies.isRadioReady()) return
        jobs.launch {
            controller.sendMessage(text)
            model.publish()
        }
    }

    override fun retry(messageId: Uuid) {
        if (jobs.isClosed || !dependencies.isRadioReady()) return
        jobs.launch {
            controller.retryMessage(messageId)
            model.publish()
            notifyConversationList()
        }
    }

    override fun refreshSession() {
        jobs.launch {
            controller.refreshSession()
            model.publish()
        }
    }

    private suspend fun notifyConversationList() {
        dependencies.onViewed?.invoke(session.id)
    }

    override fun clearError() {
        if (jobs.isClosed) return
        controller.errorMessage = null
        model.stateUpdates.update { it.copy(errorMessage = null) }
    }

    fun close() {
        jobs.close()
        cooldownJob = null
    }
}
