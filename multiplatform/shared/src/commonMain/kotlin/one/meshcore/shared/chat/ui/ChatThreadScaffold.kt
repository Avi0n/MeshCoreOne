package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.channel.ChannelInfoController
import one.meshcore.shared.channel.ui.ChannelAvatar
import one.meshcore.shared.channel.ui.ChannelInfoOverlay
import one.meshcore.shared.chat.ChatInitialScrollPolicy
import one.meshcore.shared.chat.ChatThreadRenderState
import one.meshcore.shared.chat.ComposerState
import one.meshcore.shared.chat.MessageItem
import one.meshcore.shared.chat.pathdetail.loadContacts
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.collectContactUiReloads
import one.meshcore.shared.contact.ui.ContactAvatar
import one.meshcore.shared.contact.ui.QrEncoder
import one.meshcore.shared.map.MapSnapshotStore
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_input_placeholder_direct_message
import one.meshcore.shared.resources.chats_input_placeholder_message
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.THREAD_HEADER_AVATAR_DP
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ReloadSavedContactsEffect(
    contactService: () -> ContactService?,
    radioId: () -> Uuid?,
    contactListInvalidations: Flow<Unit>,
    onContacts: (List<ContactDto>) -> Unit,
) {
    val currentContactService = contactService()
    val currentRadioId = radioId()
    LaunchedEffect(currentContactService, currentRadioId, contactListInvalidations) {
        suspend fun reload() {
            onContacts(loadContacts(currentContactService, currentRadioId))
        }
        reload()
        // iOS ChatConversationView reloads mention contacts off contactsVersion (0x80 auto-add).
        collectContactUiReloads(currentContactService, contactListInvalidations) { reload() }
    }
}

@Composable
internal fun ChatThreadTopBar(
    title: String,
    subtitle: String?,
    channel: ChannelDto?,
    contact: ContactDto?,
    channelInfoDeps: ChannelInfoController.Dependencies?,
    onShowChannelInfo: () -> Unit,
    onOpenContactInfo: (String) -> Unit,
    onBack: () -> Unit,
) {
    AppNestedTopBar(
        title = title,
        subtitle = subtitle,
        titleIcon = when {
            channel != null -> {
                { ChannelAvatar(channel, THREAD_HEADER_AVATAR_DP.dp) }
            }
            contact != null -> {
                { ContactAvatar(contact, THREAD_HEADER_AVATAR_DP.dp) }
            }
            else -> null
        },
        onTitleTap = when {
            channel != null && channelInfoDeps != null -> {
                { onShowChannelInfo() }
            }
            contact != null -> {
                { onOpenContactInfo(contact.id.value) }
            }
            else -> null
        },
        onBack = onBack,
        actions = {
            ThreadInfoAction(
                channel = channel,
                contact = contact,
                channelInfoDeps = channelInfoDeps,
                onShowChannelInfo = onShowChannelInfo,
                onOpenContactInfo = onOpenContactInfo,
            )
        },
    )
}

@Composable
internal fun ChatThreadTimeline(
    state: ChatThreadRenderState,
    title: String,
    channel: ChannelDto?,
    contact: ContactDto?,
    listState: LazyListState,
    reversed: List<MessageItem>,
    actions: ChatThreadActions,
    linkOpensEnabled: Boolean,
    onLongPress: (MessageItem) -> Unit,
    onLinkTap: (LinkTarget) -> Unit,
    mapSnapshotStore: MapSnapshotStore?,
    previews: MessagePreviewActions,
    onTapReaction: (MessageItem, String) -> Unit,
    onOpenReactionDetails: (MessageItem) -> Unit,
    translationPhase: (Uuid) -> MessageTranslationPhase?,
    onTranslate: (MessageItem) -> Unit,
    messageAvailable: (Uuid) -> Boolean,
    showDividerButton: Boolean,
    onDismissDivider: () -> Unit,
    offscreenMentionIds: List<Uuid>,
    mentionSuggestions: List<ContactDto>,
    onSelectMention: (ContactDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        when {
            state.firstSnapshot is ChatInitialScrollPolicy.FirstSnapshotDecision.Withhold ||
                state.isInitialLoading -> CenteredProgress()
            state.isLoaded && state.items.isEmpty() ->
                EmptyThread(title = title, channel = channel, contact = contact)
            else -> {
                LaunchedEffect(state.firstSnapshot) {
                    if (state.firstSnapshot is ChatInitialScrollPolicy.FirstSnapshotDecision.Present) {
                        actions.onDividerTargetConsumed()
                    }
                }
                ThreadList(
                    reversed = reversed,
                    state = state,
                    listState = listState,
                    onLongPress = onLongPress,
                    onRetry = actions.onRetry,
                    onLinkTap = { target ->
                        // iOS shouldSuppressOpen: a long-press that opens the actions sheet
                        // also fires the preview-card click. Drop that open.
                        if (linkOpensEnabled) onLinkTap(target)
                    },
                    mapSnapshotStore = mapSnapshotStore,
                    previews = previews,
                    onTapReaction = onTapReaction,
                    onOpenReactionDetails = onOpenReactionDetails,
                    translationPhase = translationPhase,
                    onTranslate = onTranslate,
                    messageAvailable = messageAvailable,
                )
            }
        }
        ChatVerticalScrollbar(
            listState = listState,
            reverseLayout = true,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (showDividerButton) {
                ScrollToDividerAffordance(
                    onClick = {
                        actions.onScrollToDivider()
                        onDismissDivider()
                    },
                )
            }
            if (offscreenMentionIds.isNotEmpty()) {
                ScrollToMentionAffordance(
                    unseenCount = offscreenMentionIds.size,
                    onClick = { actions.onScrollToMention(offscreenMentionIds.first()) },
                )
            }
            ScrollToBottomAffordance(
                unreadCount = state.unreadWhileScrolledUp,
                onClick = actions.onScrollToBottom,
                isVisible = !state.isAtBottom,
            )
        }
        MentionSuggestionOverlay(
            suggestions = mentionSuggestions,
            onSelect = onSelectMention,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
internal fun ChatThreadComposer(
    state: ComposerState,
    isReady: Boolean,
    isEncrypted: Boolean,
    channel: ChannelDto?,
    actions: ChatThreadActions,
    environment: ChatThreadEnvironment,
) {
    ChatInputBar(
        state = state,
        isReady = isReady,
        isEncrypted = isEncrypted,
        placeholder = if (channel == null) {
            stringResource(Res.string.chats_input_placeholder_direct_message)
        } else {
            stringResource(Res.string.chats_input_placeholder_message)
        },
        onTextChange = actions.onTextChange,
        onSend = actions.onSend,
        onFocus = actions.onPinToBottom,
        modifier = Modifier.fillMaxWidth(),
        leading = {
            ChatShareLeading(
                onInsertShared = actions.onInsertShared,
                selfPublicKey = environment.selfPublicKey,
                localNodeName = environment.localNodeName,
                nodeCoordinate = environment.nodeCoordinate,
                locationService = environment.locationService,
                contactService = environment.contactService,
                radioId = environment.radioId,
                hashSize = environment.deviceHashSize()?.takeIf { it > 0 } ?: 1,
            )
        },
    )
}

@Composable
internal fun ChatThreadChannelInfoCover(
    visible: Boolean,
    channel: ChannelDto?,
    dependencies: ChannelInfoController.Dependencies?,
    encode: QrEncoder,
    onCopy: (String) -> Unit,
    onDeleted: () -> Unit,
    onCleared: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible && channel != null && dependencies != null) {
        key(channel.id) {
            ChannelInfoOverlay(
                channel = channel,
                dependencies = dependencies,
                encode = encode,
                onCopy = onCopy,
                onDeleted = onDeleted,
                onCleared = onCleared,
                onDismiss = onDismiss,
            )
        }
    }
}
