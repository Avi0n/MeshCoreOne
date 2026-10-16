package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ChatConstants
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatThreadRenderState
import one.meshcore.shared.chat.ComposerState
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.ui.ChatThreadEnvironment
import one.meshcore.shared.chat.ui.ChatThreadRoot
import one.meshcore.shared.chat.ui.ChatThreadScreen
import one.meshcore.shared.chat.ui.ChatThreadScreenDependencies
import one.meshcore.shared.chat.ui.ChatThreadScreenState
import one.meshcore.shared.chat.ui.LocalPendingChatScroll
import one.meshcore.shared.chat.ui.PendingChatScroll
import one.meshcore.shared.chat.ui.forConversation
import one.meshcore.shared.chat.ui.idleChatThreadActions
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.navigation.ChatRoute

/**
 * The resolved target of a [ChatRoute]: the identity-hex route becomes a `(radio, conversation)`
 * [ChatConversationID] plus the display title and, for a DM, the contact snapshot the thread reads. The
 * connected data layer resolves it (channel slot lookup, contact fetch), so it is produced asynchronously.
 */
data class ChatDestination(
    val conversationID: ChatConversationID,
    val title: String,
    val contact: ContactDto?,
    /** Non-null when the route is a channel thread; seeds the channel-info sheet. */
    val channel: ChannelDto? = null,
)

/**
 * Binds a chat route to the live per-connection graph: [resolveDestination] turns the route into a
 * [ChatDestination] against the connected data layer, and [environment] builds the thread's live provider
 * bundle for a resolved destination. The platform graph (Android `AppContainer`) supplies both; a `null`
 * binder on [ShellDependencies] renders the disconnected placeholder.
 */
class ChatThreadBinder(
    val resolveDestination: suspend (ChatRoute) -> ChatDestination?,
    val environment: (ChatDestination) -> ChatThreadEnvironment,
)

/**
 * The chat detail both shells mount for a pushed [ChatRoute]: it resolves the route to a destination
 * against the live graph, then hosts [ChatThreadRoot] with that connection's providers. Until the
 * destination resolves it paints thread chrome with [ChatRoute.displayTitle] (never the raw route id)
 * so the first frame is a human title rather than `channel:<uuid>`.
 */
@Composable
fun ChatDetailPane(
    route: ChatRoute,
    binder: ChatThreadBinder?,
    connectionState: StateFlow<DeviceConnectionState>,
    /**
     * The message a notification tap asked the opened thread to scroll to. Required, and passed rather
     * than read from an ambient default, so a shell that stops supplying it fails to compile instead of
     * quietly swallowing every notification tap.
     */
    pendingScroll: PendingChatScroll,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onLinkTap: (LinkTarget) -> Unit = {},
    onOpenContactChat: (String) -> Unit = {},
    onOpenContactInfo: (String) -> Unit = {},
) {
    val pendingTitle = route.displayTitle?.takeIf { it.isNotBlank() }.orEmpty()

    if (binder == null) {
        PlaceholderPane(
            title = pendingTitle.ifEmpty { route.conversationId },
            modifier = modifier,
        )
        return
    }

    var destination by remember(route.conversationId, binder) { mutableStateOf<ChatDestination?>(null) }
    LaunchedEffect(route, binder) {
        val refreshed = binder.resolveDestination(route)
        currentCoroutineContext().ensureActive()
        if (refreshed != null) destination = refreshed
    }

    val resolved = destination
    if (resolved == null) {
        // Scaffold-only: human title from the list when known; no raw uuid primary chrome. The
        // conversation is still resolving, so nothing here may claim encryption: a closed lock over a
        // channel that turns out to be public would tell the user a plaintext broadcast is private.
        ChatThreadScreen(
            screenState = ChatThreadScreenState(
                thread = ChatThreadRenderState.EMPTY,
                composer = ComposerState(maxBytes = ChatConstants.MAX_DIRECT_MESSAGE_LENGTH_BYTES),
                title = pendingTitle,
                isReady = false,
                isEncrypted = false,
            ),
            actions = idleChatThreadActions(onBack),
            modifier = modifier,
            dependencies = ChatThreadScreenDependencies(onLinkTap = onLinkTap),
        )
        return
    }

    val environment = remember(resolved) { binder.environment(resolved) }
    val conversationScroll = remember(pendingScroll, route.conversationId) {
        pendingScroll.forConversation(route.conversationId)
    }
    CompositionLocalProvider(LocalPendingChatScroll provides conversationScroll) {
        key(resolved.conversationID) {
            ChatThreadRoot(
                conversationID = resolved.conversationID,
                title = resolved.title.ifBlank { pendingTitle },
                environment = environment,
                connectionState = connectionState,
                onBack = onBack,
                modifier = modifier,
                onLinkTap = onLinkTap,
                channel = resolved.channel,
                contact = resolved.contact,
                onOpenContactChat = onOpenContactChat,
                onOpenContactInfo = onOpenContactInfo,
            )
        }
    }
}
