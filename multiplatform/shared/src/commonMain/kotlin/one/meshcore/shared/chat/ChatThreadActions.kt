package one.meshcore.shared.chat

import kotlinx.coroutines.Job
import one.meshcore.shared.model.Uuid

/** Starts an action whose result belongs only to this open thread. */
internal fun ChatThreadViewModel.launchAction(action: suspend ChatThreadViewModel.() -> Unit): Job? =
    ownedJobs.launch { this@launchAction.action() }

suspend fun ChatThreadViewModel.sendMessage(text: String) = sendActions.sendMessage(text)

suspend fun ChatThreadViewModel.retryMessage(messageID: Uuid, isResend: Boolean = false) =
    sendActions.retryMessage(messageID, isResend)

suspend fun ChatThreadViewModel.deleteMessage(messageID: Uuid) = sendActions.deleteMessage(messageID)

/**
 * Surfaces a reaction-send fault on the thread error alert. iOS `sendChannelReaction` /
 * `sendDMReaction` assign `errorMessage = error.userFacingMessage`; the send itself already
 * skipped the optimistic persist.
 */
fun ChatThreadViewModel.surfaceReactionFailure(error: Throwable) {
    errorMessage = messages.userFacing(error, messages.sendFailed)
    publish()
}

fun ChatThreadViewModel.clearError() {
    errorMessage = null
    publish()
}

fun ChatThreadViewModel.clearSendError() {
    sendErrorMessage = null
    publish()
}

fun ChatThreadViewModel.clearErrorBanner() {
    errorBannerMessage = null
    publish()
}

/**
 * Reloads the conversation list after the thread closes. Mirrors iOS
 * `ChatConversationView.performCleanup` → `requestConversationReload`.
 */
suspend fun ChatThreadViewModel.notifyConversationsChanged() = dependencies.onConversationsChanged()
