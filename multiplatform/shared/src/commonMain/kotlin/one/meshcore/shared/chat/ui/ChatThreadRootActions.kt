package one.meshcore.shared.chat.ui

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.chat.MessageFragment
import one.meshcore.shared.chat.translation.seedIncomingTranslations
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.Uuid

/** The reply the composer should apply, or no message when the id did not resolve. */
internal data class ChatReplyRequest(
    val hasMessage: Boolean,
    val isChannel: Boolean,
    val senderNodeName: String?,
    val contactName: String?,
    val unknownLabel: String,
    val messageText: String,
    val currentDraft: String,
    val replyWithQuote: Boolean,
)

/** One timeline row reduced to the fields translation seeding reads. */
internal data class ChatTranslationSeed(val id: Uuid, val outgoing: Boolean, val original: String)

/** The original wire text supplied to translation, excluding rendered-only fragments. */
internal fun translationSourceText(content: List<MessageFragment>): String =
    content.filterIsInstance<MessageFragment.Text>().joinToString("\n") { it.payload.raw }

/** Whether a channel flood scope can be pushed, and the session values that push needs. */
internal data class ChannelFloodPush<T>(
    val canPush: Boolean,
    val floodScope: ChannelFloodScope?,
    val session: T?,
    val deviceDefaultFloodScopeName: String?,
    val supportsUnscopedFloodSend: Boolean,
)

/** A resolved message replaces the draft and focuses the composer. A missing message does nothing. */
internal fun applyChatReply(request: ChatReplyRequest, apply: (String) -> Unit) {
    if (!request.hasMessage) return
    val name = chatReplyMentionName(
        isChannel = request.isChannel,
        senderNodeName = request.senderNodeName,
        contactName = request.contactName,
        unknownLabel = request.unknownLabel,
    )
    apply(
        chatReplyText(
            mentionName = name,
            messageText = request.messageText,
            currentDraft = request.currentDraft,
            replyWithQuote = request.replyWithQuote,
        ),
    )
}

/**
 * Offers that are off seed nothing. Outgoing rows and blank text are not translation sources.
 * Other people's text is seeded in list order.
 */
internal suspend fun seedChatThreadTranslations(
    offersEnabled: Boolean,
    items: List<ChatTranslationSeed>,
    seed: suspend (Uuid, String) -> Unit,
) = seedIncomingTranslations(
    offersEnabled = offersEnabled,
    rows = items,
    incoming = { item -> if (item.outgoing) null else item.id to item.original },
    seed = seed,
)

/**
 * Info dismiss reloads the channel only when the row, the radio, and the channel service are all
 * present. A missed fetch keeps the channel already on screen.
 */
internal suspend fun applyChannelInfoDismiss(
    current: ChannelDto?,
    radioId: Uuid?,
    hasService: Boolean,
    load: suspend (Uuid, Int) -> ChannelDto?,
    assign: (ChannelDto) -> Unit,
) {
    if (current != null && radioId != null && hasService) {
        assign(channelAfterInfoDismiss(current, load(radioId, current.index)))
    }
}

/** A flood push needs a live connection, a channel scope, and a session. Any gap pushes nothing. */
internal suspend fun <T> pushChannelFloodScope(
    push: ChannelFloodPush<T>,
    sync: suspend (T, ChannelFloodScope, String?, Boolean) -> Unit,
) {
    val session = push.session
    val scope = push.floodScope
    if (push.canPush && session != null && scope != null) {
        sync(session, scope, push.deviceDefaultFloodScopeName, push.supportsUnscopedFloodSend)
    }
}

/** A reassigned channel slot clears the draft and leaves the thread. An unchanged slot stays. */
internal fun leaveReassignedSlot(reassigned: Boolean, clear: () -> Unit, leave: () -> Unit) {
    if (!reassigned) return
    clear()
    leave()
}

/** Teardown clears a draft only after the slot was reassigned. */
internal fun clearComposerIfReassigned(reassigned: Boolean, clear: () -> Unit) {
    if (reassigned) clear()
}
