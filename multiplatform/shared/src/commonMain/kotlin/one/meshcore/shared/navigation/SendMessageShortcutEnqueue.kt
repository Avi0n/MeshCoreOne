package one.meshcore.shared.navigation

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ChatConstants
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid

/** Result of a Shortcuts send after the URL has been staged. Mirrors iOS `SendOutcome`. */
sealed interface SendMessageShortcutOutcome {
    /** The durable queue accepted the message. */
    data object Queued : SendMessageShortcutOutcome

    /** No live radio/services; the caller must keep the app in the foreground. */
    data object MustForeground : SendMessageShortcutOutcome

    /** The target id is missing or belongs to another radio. */
    data object InvalidRecipient : SendMessageShortcutOutcome

    /** Channel text exceeds the node-name-adjusted budget. */
    data object MessageTooLong : SendMessageShortcutOutcome
}

data class ShortcutEnqueue(
    val request: MeshCoreDeepLink.SendMessage,
    val radioReady: Boolean,
    val currentRadioId: Uuid?,
    val fetchContact: suspend (Uuid) -> ContactDto?,
    val fetchChannel: suspend (Uuid) -> ChannelDto?,
    val enqueueDm: suspend (ContactDto) -> Unit,
    val enqueueChannel: suspend (ChannelDto) -> Unit,
    val localNodeName: String? = null,
)

/**
 * Resolves [ShortcutEnqueue.request] against the connected radio and enqueues a pending send.
 * iOS `SendMessageIntent.performSend` uses the same ready/radio-scope/recipient checks.
 */
suspend fun enqueueSendMessageShortcut(enqueue: ShortcutEnqueue): SendMessageShortcutOutcome {
    if (!enqueue.radioReady || enqueue.currentRadioId == null) return SendMessageShortcutOutcome.MustForeground
    return enqueueShortcutForKind(enqueue)
}

private suspend fun enqueueShortcutForKind(enqueue: ShortcutEnqueue): SendMessageShortcutOutcome {
    val targetId = Uuid.normalized(enqueue.request.targetId)
    return when (enqueue.request.kind) {
        MeshCoreDeepLink.SendMessage.Kind.CONTACT -> enqueueContactShortcut(enqueue, targetId)
        MeshCoreDeepLink.SendMessage.Kind.CHANNEL -> enqueueChannelShortcut(enqueue, targetId)
    }
}

private suspend fun enqueueContactShortcut(enqueue: ShortcutEnqueue, targetId: Uuid): SendMessageShortcutOutcome {
    val contact = enqueue.fetchContact(targetId) ?: return SendMessageShortcutOutcome.InvalidRecipient
    if (contact.typeRawValue != ContactType.CHAT.raw) return SendMessageShortcutOutcome.InvalidRecipient
    if (contact.radioID != enqueue.currentRadioId) return SendMessageShortcutOutcome.MustForeground
    enqueue.enqueueDm(contact)
    return SendMessageShortcutOutcome.Queued
}

private suspend fun enqueueChannelShortcut(enqueue: ShortcutEnqueue, targetId: Uuid): SendMessageShortcutOutcome {
    val channel = enqueue.fetchChannel(targetId) ?: return SendMessageShortcutOutcome.InvalidRecipient
    if (channel.radioID != enqueue.currentRadioId) return SendMessageShortcutOutcome.MustForeground
    val budget = channelShortcutBudgetBytes(enqueue.localNodeName)
    if (enqueue.request.text.encodeToByteArray().size > budget) return SendMessageShortcutOutcome.MessageTooLong
    enqueue.enqueueChannel(channel)
    return SendMessageShortcutOutcome.Queued
}

/**
 * Whether Shortcuts can enqueue now. iOS `SendMessageIntent.route` queues on Ready and on Syncing
 * (`queueAfterSync`); Connected-but-not-synced still escalates to the foreground.
 */
fun isSendMessageRadioReady(state: ConnectionState, hasServices: Boolean): Boolean =
    hasServices && (state is ConnectionState.Ready || state is ConnectionState.Syncing)

/** Dialog to show after a confirm tap. [None] means the send queued. */
enum class SendMessageShortcutDialog {
    None,
    TooLong,
    InvalidRecipient,
    NotConnected,
}

fun sendMessageShortcutDialog(outcome: SendMessageShortcutOutcome): SendMessageShortcutDialog = when (outcome) {
    SendMessageShortcutOutcome.Queued -> SendMessageShortcutDialog.None
    SendMessageShortcutOutcome.MessageTooLong -> SendMessageShortcutDialog.TooLong
    SendMessageShortcutOutcome.InvalidRecipient -> SendMessageShortcutDialog.InvalidRecipient
    SendMessageShortcutOutcome.MustForeground -> SendMessageShortcutDialog.NotConnected
}

/** Firmware prepends `"<NodeName>: "` to every channel broadcast. */
internal fun channelShortcutBudgetBytes(nodeName: String?): Int = ChatConstants.maxChannelMessageLength(nodeName)
