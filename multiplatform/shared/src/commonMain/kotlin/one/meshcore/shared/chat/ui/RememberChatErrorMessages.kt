package one.meshcore.shared.chat.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.chat.ChatErrorMessages
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_alert_unable_to_send_message
import one.meshcore.shared.resources.chats_error_delete_conversation_failed
import one.meshcore.shared.resources.chats_error_delete_message_failed
import one.meshcore.shared.resources.chats_error_delete_timed_out
import one.meshcore.shared.resources.chats_error_load_conversations_failed
import one.meshcore.shared.resources.chats_error_load_messages_failed
import one.meshcore.shared.resources.chats_error_load_older_messages_failed
import one.meshcore.shared.resources.chats_error_not_connected_to_delete
import one.meshcore.shared.resources.chats_error_pin_save_failed
import one.meshcore.shared.resources.chats_error_send_queue_persist_failed
import one.meshcore.shared.resources.chats_error_services_unavailable
import one.meshcore.shared.resources.localizable_error_message_service_invalid_recipient
import one.meshcore.shared.resources.localizable_error_message_service_message_too_long
import one.meshcore.shared.resources.localizable_error_message_service_not_connected
import one.meshcore.shared.resources.localizable_error_message_service_send_failed
import one.meshcore.shared.ui.rememberMeshBridgeErrorText
import org.jetbrains.compose.resources.stringResource

/**
 * Resolves the chat error copy at composition, where resources are readable, for view models that catch
 * inside coroutines. Mirrors `rememberToolsCliMessages`.
 */
@Composable
fun rememberChatErrorMessages(): ChatErrorMessages {
    val bridgeErrorText = rememberMeshBridgeErrorText()
    return ChatErrorMessages(
        notConnected = stringResource(Res.string.localizable_error_message_service_not_connected),
        sendFailed = stringResource(Res.string.localizable_error_message_service_send_failed),
        invalidRecipient = stringResource(Res.string.localizable_error_message_service_invalid_recipient),
        messageTooLong = stringResource(Res.string.localizable_error_message_service_message_too_long),
        loadConversationsFailed = stringResource(Res.string.chats_error_load_conversations_failed),
        pinSaveFailed = stringResource(Res.string.chats_error_pin_save_failed),
        loadOlderMessagesFailed = stringResource(Res.string.chats_error_load_older_messages_failed),
        notConnectedToDelete = stringResource(Res.string.chats_error_not_connected_to_delete),
        servicesUnavailable = stringResource(Res.string.chats_error_services_unavailable),
        sendQueuePersistFailed = stringResource(Res.string.chats_error_send_queue_persist_failed),
        unableToSend = stringResource(Res.string.chats_alert_unable_to_send_message),
        loadMessagesFailed = stringResource(Res.string.chats_error_load_messages_failed),
        deleteConversationFailed = stringResource(Res.string.chats_error_delete_conversation_failed),
        deleteMessageFailed = stringResource(Res.string.chats_error_delete_message_failed),
        deleteTimedOut = stringResource(Res.string.chats_error_delete_timed_out),
        sessionError = bridgeErrorText,
    )
}
