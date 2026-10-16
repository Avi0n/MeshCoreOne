package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.MessageItem
import one.meshcore.shared.chat.pathdetail.loadRepeatDetails
import one.meshcore.shared.chat.text.MentionUtilities
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_header_region
import one.meshcore.shared.resources.chats_channel_info_region_scoped_default
import one.meshcore.shared.resources.chats_connection_status_direct
import one.meshcore.shared.resources.chats_connection_status_flood_routing
import one.meshcore.shared.resources.chats_path_receiver_you
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.plainTextClipEntry
import org.jetbrains.compose.resources.stringResource

/**
 * The conversation thread surface: a `LazyColumn(reverseLayout = true)` timeline with stable per-message
 * keys (so an older page inserting at the visual top never disturbs the viewport — spec §9.3), a
 * near-bottom auto-scroll gate driven by state, a near-top pagination trigger, a state-token-driven scroll
 * effect (never a fire-and-forget delay), the composer input bar, and the long-press actions sheet. The
 * list engine and reverse-layout decision follow the shared chat ordering contract.
 *
 * Pure over [screenState], [actions], [environment], and [dependencies] so it renders identically for the
 * app wiring and the scroll benchmark; the host owns the view model. [ChatThreadEnvironment.Disconnected]
 * is the scaffold shown before a radio graph exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatThreadScreen(
    screenState: ChatThreadScreenState,
    actions: ChatThreadActions,
    modifier: Modifier = Modifier,
    environment: ChatThreadEnvironment = ChatThreadEnvironment.Disconnected,
    dependencies: ChatThreadScreenDependencies = ChatThreadScreenDependencies(),
) {
    val state = screenState.thread
    val composerState = screenState.composer
    val title = screenState.title
    val isReady = screenState.isReady
    val isEncrypted = screenState.isEncrypted
    val channel = screenState.channel
    val contact = screenState.contact
    val isNetworkOffline = screenState.isNetworkOffline
    val contactService = environment.contactService
    val radioId = environment.radioId
    val contactListInvalidations = environment.contactListInvalidations
    val mapSnapshotStore = environment.mapSnapshotStore
    val listState = rememberLazyListState()
    val overlays = remember { ThreadOverlayState() }
    var showChannelInfo by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val youLabel = stringResource(Res.string.chats_path_receiver_you)
    val scope = rememberCoroutineScope()
    val recentStore = environment.recentEmojisStore
    val frequentStore = environment.frequentEmojisStore
    var recentEmojis by remember { mutableStateOf(recentStore.recentEmojis) }
    var frequentEmojis by remember { mutableStateOf(frequentStore.recentEmojis) }
    LaunchedEffect(recentStore, frequentStore) {
        recentStore.load()
        frequentStore.load()
        recentEmojis = recentStore.recentEmojis
        frequentEmojis = frequentStore.recentEmojis
    }
    var savedContacts by remember { mutableStateOf<List<ContactDto>>(emptyList()) }
    ReloadSavedContactsEffect(
        contactService = contactService,
        radioId = radioId,
        contactListInvalidations = contactListInvalidations,
        onContacts = { savedContacts = it },
    )
    // A channel participant with no contact row is still mentionable, so the heard-sender index tops up the
    // contact book; a name already saved keeps its real row (iOS drops the synthetic duplicate the same way).
    val mentionCandidates = remember(savedContacts, state.channelSenders) {
        val savedNames = savedContacts.mapTo(mutableSetOf()) { it.name }
        savedContacts + state.channelSenders.filterNot { it.name in savedNames }
    }
    val mentionSuggestions = rememberMentionSuggestions(
        text = composerState.text,
        candidates = mentionCandidates,
        liveSenderOrder = state.channelSenderOrder,
    )

    // Under reverseLayout the list renders bottom-up, so the newest row (chronological last) must be index 0.
    val reversed = remember(state.items) { state.items.asReversed() }

    // Long-press sheet emoji row records recent-emoji usage and hits the reaction send/dedupe plumbing.
    // Badge taps open who-reacted (iOS `ReactionBadgesView.onSelect`); they do not re-send.
    val onReactToItem: (MessageItem, String) -> Unit = { item, emoji ->
        scope.launch {
            recentStore.recordUsage(emoji)
            frequentStore.recordUsage(emoji)
            recentEmojis = recentStore.recentEmojis
            frequentEmojis = frequentStore.recentEmojis
            sendReaction(
                item = item,
                emoji = emoji,
                environment = environment,
                fetchMessage = dependencies.fetchMessage,
                onFailed = actions.onReactionFailed,
            )
        }
    }

    NearBottomReporter(listState, actions.onNearBottomChanged)
    NearTopPaginationTrigger(
        listState = listState,
        hasMore = state.hasMoreMessages,
        isLoadingOlder = state.isLoadingOlder,
        onNearTop = actions.onNearTop,
    )
    ScrollCommandEffect(listState, state.scrollCommand, state.items)

    var isDividerVisible by remember { mutableStateOf(false) }
    var hasDismissedDividerButton by remember { mutableStateOf(false) }
    var offscreenMentionIds by remember { mutableStateOf<List<Uuid>>(emptyList()) }
    ThreadScrollAffordanceEffects(
        listState = listState,
        state = state,
        onMentionSeen = actions.onMentionSeen,
        onDividerVisibleChanged = { isDividerVisible = it },
        onOffscreenMentionsChanged = { offscreenMentionIds = it },
    )
    LaunchedEffect(state.newMessagesDividerMessageID) { hasDismissedDividerButton = false }
    val showDividerButton = state.newMessagesDividerMessageID != null &&
        !isDividerVisible &&
        !hasDismissedDividerButton

    // iOS `bestAvailableLocation`: the phone's own fix when known, else the connected radio's coordinates.
    // The candidate rows read it to show how far away each identity is.
    val bestAvailableLocation: () -> UserLocation? = {
        (environment.locationService()?.currentLocation?.value ?: environment.nodeCoordinate())
            ?.let { UserLocation(it.latitude, it.longitude) }
    }

    val channelInfoDeps = environment.channelInfoDependencies()
    // Header second line: a channel's effective flood region, a DM's routing mode, or nothing.
    val headerSubtitle = headerSubtitleText(
        threadHeaderSubtitle(channel, contact, channelInfoDeps?.deviceDefaultFloodScopeName?.invoke()),
    )
    // The top bar draws under the status bar and the composer owns the bottom inset (nav bar + IME), so the
    // Scaffold adds no window insets to the content itself; otherwise the composer would float above the
    // navigation bar instead of extending its container under it.
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            ChatThreadTopBar(
                title = title,
                subtitle = headerSubtitle,
                channel = channel,
                contact = contact,
                channelInfoDeps = channelInfoDeps,
                onShowChannelInfo = { showChannelInfo = true },
                onOpenContactInfo = dependencies.onOpenContactInfo,
                onBack = actions.onBack,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ChatThreadTimeline(
                state = state,
                title = title,
                channel = channel,
                contact = contact,
                listState = listState,
                reversed = reversed,
                actions = actions,
                linkOpensEnabled = overlays.selected == null,
                onLongPress = { overlays.selected = it },
                onLinkTap = dependencies.onLinkTap,
                mapSnapshotStore = mapSnapshotStore(),
                previews = dependencies.previews,
                onTapReaction = { item, emoji ->
                    overlays.reactionsRequest = ReactionDetailsRequest(item, emoji)
                },
                onOpenReactionDetails = { item ->
                    overlays.reactionsRequest = ReactionDetailsRequest(item)
                },
                translationPhase = dependencies.translationPhase,
                onTranslate = dependencies.onTranslate,
                messageAvailable = dependencies.messageAvailable,
                showDividerButton = showDividerButton,
                onDismissDivider = { hasDismissedDividerButton = true },
                offscreenMentionIds = offscreenMentionIds,
                mentionSuggestions = mentionSuggestions,
                onSelectMention = { mention ->
                    actions.onTextChange(MentionUtilities.insertMention(mention.name, composerState.text))
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            state.errorBannerMessage?.let { banner ->
                ThreadErrorBanner(message = banner, onDismiss = actions.onClearErrorBanner)
            }
            ChatThreadComposer(
                state = composerState,
                isReady = isReady,
                isEncrypted = isEncrypted,
                channel = channel,
                actions = actions,
                environment = environment,
            )
        }
    }

    val selectedLive = overlays.selected?.let { sel -> state.items.firstOrNull { it.id == sel.id } ?: sel }
    ThreadOverlays(
        overlays = overlays,
        selected = selectedLive,
        onLoadRepeats = { item ->
            scope.launch {
                overlays.repeatRows = null
                overlays.repeatRows = loadRepeatDetails { dependencies.fetchRepeats(item.id) }
            }
        },
        clipboardCopy = { text ->
            scope.launch {
                clipboard.setClipEntry(plainTextClipEntry(text))
            }
        },
        actions = actions,
        environment = environment,
        fetchMessage = dependencies.fetchMessage,
        fetchRepeats = dependencies.fetchRepeats,
        youLabel = youLabel,
        recentEmojis = recentEmojis,
        frequentEmojis = frequentEmojis,
        onReact = onReactToItem,
        onOpenContactChat = dependencies.onOpenContactChat,
        errorMessage = state.errorMessage,
        sendErrorMessage = state.sendErrorMessage,
        userLocation = bestAvailableLocation,
        isNetworkOffline = isNetworkOffline,
        translationPhase = dependencies.translationPhase,
        onSheetTranslate = dependencies.onSheetTranslate,
        onChannelSenderBlocked = dependencies.onChannelSenderBlocked,
    )

    ChatThreadChannelInfoCover(
        visible = showChannelInfo,
        channel = channel,
        dependencies = channelInfoDeps,
        encode = environment.qrEncode,
        onCopy = environment.copyToClipboard,
        onDeleted = {
            showChannelInfo = false
            dependencies.onChannelDeleted()
        },
        onCleared = {
            showChannelInfo = false
            dependencies.onChannelMessagesCleared()
        },
        onDismiss = {
            showChannelInfo = false
            dependencies.onChannelInfoDismiss()
        },
    )
}

/**
 * The header's second line, or null when the conversation has none. A channel names its effective flood
 * region and marks the radio's own default with "(default)"; a DM names its routing mode, where "Direct"
 * is the out-path mode rather than a claim of zero hops.
 */
@Composable
private fun headerSubtitleText(subtitle: ThreadHeaderSubtitle): String? = when (subtitle) {
    ThreadHeaderSubtitle.None -> null
    ThreadHeaderSubtitle.FloodRouting -> stringResource(Res.string.chats_connection_status_flood_routing)
    is ThreadHeaderSubtitle.Direct ->
        formattedStringResource(Res.string.chats_connection_status_direct, subtitle.hops)
    is ThreadHeaderSubtitle.Region -> {
        val regionDisplay = if (subtitle.isDeviceDefault) {
            formattedStringResource(Res.string.chats_channel_info_region_scoped_default, subtitle.name)
        } else {
            subtitle.name
        }
        formattedStringResource(Res.string.chats_channel_header_region, regionDisplay)
    }
}
