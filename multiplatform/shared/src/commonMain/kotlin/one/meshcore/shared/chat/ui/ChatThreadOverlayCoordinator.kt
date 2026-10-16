package one.meshcore.shared.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.MessageItem
import one.meshcore.shared.chat.reactions.ReactionSendResult
import one.meshcore.shared.chat.reactions.reactionSenderName
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.model.Uuid

/** Badge tap (emoji) or overflow/TalkBack (null preferred tab). */
internal data class ReactionDetailsRequest(val item: MessageItem, val initialEmoji: String? = null)

/** Sheets the thread opens from a message. The list still reads [selected] to decide whether links open. */
internal class ThreadOverlayState {
    var selected by mutableStateOf<MessageItem?>(null)
    var blockSenderItem by mutableStateOf<MessageItem?>(null)
    var sendDmItem by mutableStateOf<MessageItem?>(null)
    var pathMapDisplay by mutableStateOf<MessagePathPresentation?>(null)
    var reactionsRequest by mutableStateOf<ReactionDetailsRequest?>(null)
    var repeatItem by mutableStateOf<MessageItem?>(null)
    var repeatRows by mutableStateOf<List<one.meshcore.shared.backup.MessageRepeatDto>?>(null)

    fun select(item: MessageItem?) {
        selected = item
        if (item != null) pathMapDisplay = null
    }

    fun showRepeat(item: MessageItem?) {
        repeatItem = item
        if (item == null) repeatRows = null
    }
}

/** Sends [emoji] as a reaction on [item] when the live reaction graph is available. */
internal suspend fun sendReaction(
    item: MessageItem,
    emoji: String,
    environment: ChatThreadEnvironment,
    fetchMessage: suspend (Uuid) -> MessageDto?,
    onFailed: (Throwable) -> Unit,
) {
    val svc = environment.reactionService() ?: return
    val store = environment.reactionStore() ?: return
    val sender = environment.reactionSender() ?: return
    val dto = fetchMessage(item.id) ?: return
    val result = svc.react(
        emoji = emoji,
        message = dto,
        localNodeName = reactionSenderName(environment.localNodeName()),
        store = store,
        sender = sender,
    )
    if (result is ReactionSendResult.Failed) onFailed(result.error)
}

/**
 * Long-press actions, path detail, block-sender, and error dialogs for the thread — extracted so
 * [ChatThreadScreen] stays under the cyclomatic complexity budget.
 */
@Composable
internal fun ThreadOverlays(
    overlays: ThreadOverlayState,
    selected: MessageItem?,
    onLoadRepeats: (MessageItem) -> Unit,
    clipboardCopy: (String) -> Unit,
    actions: ChatThreadActions,
    environment: ChatThreadEnvironment,
    fetchMessage: suspend (Uuid) -> MessageDto?,
    fetchRepeats: suspend (Uuid) -> List<one.meshcore.shared.backup.MessageRepeatDto>,
    youLabel: String,
    recentEmojis: List<String>,
    frequentEmojis: List<String>,
    onReact: (MessageItem, String) -> Unit,
    onOpenContactChat: (String) -> Unit,
    errorMessage: String?,
    sendErrorMessage: String?,
    userLocation: () -> UserLocation?,
    isNetworkOffline: Boolean,
    translationPhase: (Uuid) -> MessageTranslationPhase? = { null },
    onSheetTranslate: (MessageItem) -> Unit = {},
    onChannelSenderBlocked: () -> Unit = {},
) {
    val reactionsRequest = overlays.reactionsRequest
    val reactionStore = environment.reactionStore()
    ThreadMessageActionsOverlay(
        overlays = overlays,
        selected = selected,
        onLoadRepeats = onLoadRepeats,
        clipboardCopy = clipboardCopy,
        actions = actions,
        environment = environment,
        fetchMessage = fetchMessage,
        fetchRepeats = fetchRepeats,
        youLabel = youLabel,
        recentEmojis = recentEmojis,
        frequentEmojis = frequentEmojis,
        onReact = onReact,
        userLocation = userLocation,
        isNetworkOffline = isNetworkOffline,
        translationPhase = translationPhase,
        onSheetTranslate = onSheetTranslate,
    )
    ThreadPathMapOverlay(
        overlays = overlays,
        environment = environment,
        isNetworkOffline = isNetworkOffline,
    )
    ThreadSendDmOverlay(
        overlays = overlays,
        environment = environment,
        onOpenContactChat = onOpenContactChat,
        userLocation = userLocation,
    )
    reactionsRequest?.let { request ->
        ReactionDetailsSheet(
            messageId = request.item.id,
            reactionStore = reactionStore,
            onDismiss = { overlays.reactionsRequest = null },
            initialEmoji = request.initialEmoji,
        )
    }
    ThreadRepeatDetailsOverlay(
        overlays = overlays,
        environment = environment,
        userLocation = userLocation,
    )
    ThreadBlockSenderOverlay(
        overlays = overlays,
        environment = environment,
        userLocation = userLocation,
        onChannelSenderBlocked = onChannelSenderBlocked,
    )
    ThreadErrorDialogs(
        errorMessage = errorMessage,
        sendErrorMessage = sendErrorMessage,
        onClearError = actions.onClearError,
        onClearSendError = actions.onClearSendError,
    )
}
