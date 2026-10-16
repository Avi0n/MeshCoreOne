package one.meshcore.shared.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import one.meshcore.shared.chat.ConversationListViewModel
import one.meshcore.shared.chat.NewChatViewModel
import one.meshcore.shared.chat.refreshFailedSendIndicators
import one.meshcore.shared.chat.reload
import one.meshcore.shared.chat.shouldRefreshFailedSendIndicators
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_accessibility_offline_announcement
import one.meshcore.shared.ui.AnnounceOnAppear
import one.meshcore.shared.ui.rememberMinuteTickSeconds
import one.meshcore.shared.ui.shouldAnnounceCachedData
import org.jetbrains.compose.resources.stringResource
import kotlin.time.ExperimentalTime

/**
 * Reloads the chats list on each ingest, block, or delete bump. A preview read fault is ignored so
 * the next bump still reloads.
 */
internal suspend fun collectConversationListInvalidations(
    invalidations: Flow<Unit>,
    reload: suspend () -> Unit,
    prewarm: suspend () -> Unit,
) {
    invalidations.collect {
        try {
            reload()
            prewarm()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A preview Room read must not kill later list reloads.
        }
    }
}

/**
 * The composition root wiring the Chats tab to a [ConversationListViewModel]. A `null` store or radio
 * id renders the disconnected, empty list. The new-chat picker reads the same contact service; a picked
 * contact opens its DM through [ConversationListInputs.onConversationTap].
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun ConversationListRoot(inputs: ConversationListInputs, modifier: Modifier = Modifier) {
    val currentInputs by rememberUpdatedState(inputs)
    val store = inputs.store
    val contactService = inputs.contactService
    val radioId = inputs.radioId
    val connectionState = inputs.connectionState
    val onConversationTap = inputs.onConversationTap
    val onStartNewChat = inputs.onStartNewChat
    val selectedRoute = inputs.selectedRoute
    val onNewChannel = inputs.onNewChannel
    val onConversationDeleted = inputs.onConversationDeleted
    val listInvalidations = inputs.listInvalidations
    val prewarmConversationPreviews = inputs.prewarmConversationPreviews
    val prefetchConversation = inputs.prefetchConversation
    val statusEvents = inputs.statusEvents
    val roomEvents = inputs.roomEvents
    val scope = rememberCoroutineScope()
    val state by connectionState.collectAsStateWithLifecycle()
    val currentStore = store()
    val currentRadioId = radioId()

    val chatErrorMessages = rememberChatErrorMessages()
    val currentChatErrorMessages by rememberUpdatedState(chatErrorMessages)
    val viewModel = remember(scope) {
        ConversationListViewModel(
            dependencies = ConversationListViewModel.Dependencies(
                errorMessages = { currentChatErrorMessages },
                store = { currentInputs.store() },
                radioId = { currentInputs.radioId() },
                isReady = { currentInputs.connectionState.value == DeviceConnectionState.READY },
                channelService = { currentInputs.channelService() },
                clearChannelNotifications = { channelIndex, radioID ->
                    currentInputs.clearChannelNotifications(channelIndex, radioID)
                },
                updateBadge = { currentInputs.updateBadge() },
            ),
        )
    }

    val currentContactService = contactService()
    val newChatViewModel = remember {
        NewChatViewModel(
            dependencies = NewChatViewModel.Dependencies(
                contactService = { currentInputs.contactService() },
                radioId = { currentInputs.radioId() },
            ),
        )
    }

    val listState by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(listState.snapshotGeneration, listState.hasLoadedOnce, listState.retainingDirectContactIDs) {
        if (listState.hasLoadedOnce) {
            currentInputs.onSnapshotRefreshed(listState.snapshot.all, listState.retainingDirectContactIDs)
        }
    }

    // A single timestamp anchor for the rows' relative-age labels; live ticking is not needed here.
    val nowSeconds by rememberMinuteTickSeconds()

    // Reload when the connection owner or state changes so an in-place graph replacement cannot
    // leave another radio's snapshot visible.
    LaunchedEffect(viewModel, state, currentStore, currentRadioId) {
        viewModel.reload()
        prewarmConversationPreviews(viewModel.state.value.snapshot.all)
    }
    // Drop a failed-send badge after a retry ACK without a full list reload (iOS
    // `refreshFailedSendIndicators` on status-resolved when a badge is already showing).
    // Key the live-session boolean rather than the rung so Connected→Ready stays bound; the concrete
    // contact service still rebinds both event sources when the connection graph is replaced in place.
    val canCollectThreadEvents = state.canCollectThreadEvents
    LaunchedEffect(
        canCollectThreadEvents,
        viewModel,
        currentRadioId,
        currentContactService,
        statusEvents,
        roomEvents,
    ) {
        if (!canCollectThreadEvents) return@LaunchedEffect
        launch {
            statusEvents().collect { event ->
                if (viewModel.shouldRefreshFailedSendIndicators(event)) {
                    viewModel.refreshFailedSendIndicators()
                }
            }
        }
        launch {
            roomEvents().collect { event ->
                if (viewModel.shouldRefreshFailedSendIndicators(event)) {
                    viewModel.refreshFailedSendIndicators()
                }
            }
        }
    }
    // Ingest, block, and contact delete (iOS SyncCoordinator version bumps).
    LaunchedEffect(listInvalidations) {
        collectConversationListInvalidations(
            invalidations = listInvalidations,
            reload = { viewModel.reload() },
            prewarm = { prewarmConversationPreviews(viewModel.state.value.snapshot.all) },
        )
    }
    // Demo fixture seed is async after first composition; retry until the store/radio are live so
    // last-message previews bind (LIST-PREVIEW). Caps attempts so an empty offline graph is not a loop.
    LaunchedEffect(Unit) {
        repeat(STORE_READY_RETRY_COUNT) {
            if (store() != null && radioId() != null) {
                viewModel.reload()
                prewarmConversationPreviews(viewModel.state.value.snapshot.all)
                return@LaunchedEffect
            }
            delay(STORE_READY_RETRY_MS)
        }
    }

    // Nothing on the list marks its threads as stale, so an arriving screen has to say so, as iOS does
    // from ChatListActions.announceOfflineStateIfNeeded.
    AnnounceOnAppear(
        announce = shouldAnnounceCachedData(state, currentRadioId != null),
        message = stringResource(Res.string.chats_accessibility_offline_announcement),
    )

    ConversationListScreen(
        viewModel = viewModel,
        newChatViewModel = newChatViewModel,
        newChatServiceOwner = currentContactService,
        newChatRadioId = currentRadioId,
        isReady = state == DeviceConnectionState.READY,
        nowSeconds = nowSeconds,
        onConversationTap = { conversation ->
            scope.launch { prefetchConversation(conversation) }
            onConversationTap(conversation)
        },
        onStartChat = onStartNewChat,
        modifier = modifier,
        selectedRoute = selectedRoute,
        onNewChannel = onNewChannel,
        onConversationDeleted = onConversationDeleted,
    )
}

private const val STORE_READY_RETRY_COUNT = 20
private const val STORE_READY_RETRY_MS = 250L
