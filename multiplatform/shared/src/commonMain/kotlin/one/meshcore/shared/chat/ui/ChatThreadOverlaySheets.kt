package one.meshcore.shared.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.chat.MessageActionAvailability
import one.meshcore.shared.chat.MessageFragment
import one.meshcore.shared.chat.MessageItem
import one.meshcore.shared.chat.launchAfterMessageActionsDismiss
import one.meshcore.shared.chat.messageActionsAvailability
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_action_show_original
import one.meshcore.shared.resources.chats_message_action_translate
import one.meshcore.shared.resources.chats_message_action_translating
import one.meshcore.shared.resources.chats_path_hop_unknown
import org.jetbrains.compose.resources.stringResource

private data class ThreadActionCallbackInputs(
    val item: MessageItem,
    val actions: ChatThreadActions,
    val overlayScope: CoroutineScope,
    val inlinePathDisplay: () -> MessagePathPresentation?,
    val loadInlinePath: suspend () -> MessagePathPresentation,
    val onSelectedChange: (MessageItem?) -> Unit,
    val onBlockSenderItemChange: (MessageItem?) -> Unit,
    val onSendDmItemChange: (MessageItem?) -> Unit,
    val onPathMapDisplayChange: (MessagePathPresentation?) -> Unit,
    val onRepeatItemChange: (MessageItem?) -> Unit,
    val onLoadRepeats: (MessageItem) -> Unit,
)

private data class ThreadMessageActionCallbacks(
    val onReply: (() -> Unit)?,
    val onSendAgain: (() -> Unit)?,
    val onBlockSender: (() -> Unit)?,
    val onSendDm: (() -> Unit)?,
    val onOpenPathMap: ((Uuid?) -> Unit)?,
    val onOpenRepeatDetails: (() -> Unit)?,
)

private fun threadMessageActionCallbacks(
    availability: MessageActionAvailability,
    inputs: ThreadActionCallbackInputs,
): ThreadMessageActionCallbacks {
    val direct = directMessageActionCallbacks(availability, inputs)
    val path = pathMessageActionCallbacks(availability, inputs)
    return direct.copy(
        onOpenPathMap = path.onOpenPathMap,
        onOpenRepeatDetails = path.onOpenRepeatDetails,
    )
}

private fun directMessageActionCallbacks(
    availability: MessageActionAvailability,
    inputs: ThreadActionCallbackInputs,
): ThreadMessageActionCallbacks {
    val item = inputs.item
    return ThreadMessageActionCallbacks(
        onReply = if (availability.canReply) {
            {
                inputs.actions.onReply(item.id)
                inputs.onSelectedChange(null)
            }
        } else {
            null
        },
        onSendAgain = if (availability.canSendAgain) {
            {
                inputs.actions.onSendAgain(item.id)
                inputs.onSelectedChange(null)
            }
        } else {
            null
        },
        onBlockSender = if (availability.canBlockSender) {
            {
                inputs.onSelectedChange(null)
                inputs.overlayScope.launchAfterMessageActionsDismiss { inputs.onBlockSenderItemChange(item) }
            }
        } else {
            null
        },
        onSendDm = if (availability.canSendDM) {
            {
                inputs.onSelectedChange(null)
                inputs.overlayScope.launchAfterMessageActionsDismiss { inputs.onSendDmItemChange(item) }
            }
        } else {
            null
        },
        onOpenPathMap = null,
        onOpenRepeatDetails = null,
    )
}

private fun pathMessageActionCallbacks(
    availability: MessageActionAvailability,
    inputs: ThreadActionCallbackInputs,
): ThreadMessageActionCallbacks {
    val item = inputs.item
    return ThreadMessageActionCallbacks(
        onReply = null,
        onSendAgain = null,
        onBlockSender = null,
        onSendDm = null,
        onOpenPathMap = if (availability.canViewPath || availability.canShowIncomingPaths) {
            { preferredArrivalID ->
                val snapshot = inputs.inlinePathDisplay()
                inputs.onSelectedChange(null)
                inputs.overlayScope.launchAfterMessageActionsDismiss {
                    inputs.onPathMapDisplayChange(
                        (snapshot ?: inputs.loadInlinePath()).copy(initialSelectionID = preferredArrivalID),
                    )
                }
            }
        } else {
            null
        },
        onOpenRepeatDetails = if (availability.canShowRepeatDetails) {
            {
                inputs.onLoadRepeats(item)
                inputs.onSelectedChange(null)
                inputs.overlayScope.launchAfterMessageActionsDismiss {
                    inputs.onRepeatItemChange(item)
                }
            }
        } else {
            null
        },
    )
}

@Composable
internal fun ThreadMessageActionsOverlay(
    overlays: ThreadOverlayState,
    selected: MessageItem?,
    onLoadRepeats: (MessageItem) -> Unit,
    clipboardCopy: (String) -> Unit,
    actions: ChatThreadActions,
    environment: ChatThreadEnvironment,
    fetchMessage: suspend (Uuid) -> MessageDto?,
    fetchRepeats: suspend (Uuid) -> List<MessageRepeatDto>,
    youLabel: String,
    recentEmojis: List<String>,
    frequentEmojis: List<String>,
    onReact: (MessageItem, String) -> Unit,
    userLocation: () -> UserLocation?,
    isNetworkOffline: Boolean,
    translationPhase: (Uuid) -> MessageTranslationPhase?,
    onSheetTranslate: (MessageItem) -> Unit,
) {
    val onSelectedChange: (MessageItem?) -> Unit = overlays::select
    val onBlockSenderItemChange: (MessageItem?) -> Unit = { overlays.blockSenderItem = it }
    val onSendDmItemChange: (MessageItem?) -> Unit = { overlays.sendDmItem = it }
    val onPathMapDisplayChange: (MessagePathPresentation?) -> Unit = { overlays.pathMapDisplay = it }
    val onRepeatItemChange = overlays::showRepeat
    val contactService = environment.contactService
    val radioId = environment.radioId
    val localNodeName = environment.localNodeName
    val nodeCoordinate = environment.nodeCoordinate
    val replyWithQuote = environment.replyWithQuote()
    val locationService = environment.locationService
    val fetchDiscoveredRepeaters = environment.fetchDiscoveredRepeaters
    val overlayScope = rememberCoroutineScope()
    val unknownHopLabel = stringResource(Res.string.chats_path_hop_unknown)
    selected?.let { item ->
        var messageDetails by remember(item.id) { mutableStateOf<MessageDto?>(null) }
        var inlinePathDisplay by remember(item.id) { mutableStateOf<MessagePathPresentation?>(null) }
        LaunchedEffect(item.id, item.actionAvailability.canViewPath) {
            messageDetails = fetchMessage(item.id)
        }
        val availability = messageActionsAvailability(item.actionAvailability, messageDetails)
        suspend fun loadInlinePath(): MessagePathPresentation = loadThreadPathPresentation(
            fetches = ThreadPathFetches(
                messageId = item.id,
                fetchMessage = fetchMessage,
                fetchRepeats = fetchRepeats,
                contactService = contactService(),
                radioId = radioId(),
                fetchDiscoveredRepeaters = fetchDiscoveredRepeaters,
            ),
            place = ThreadPathPlace(
                userLocation = userLocation(),
                nodeCoordinate = nodeCoordinate(),
                currentLocation = locationService()?.currentLocation?.value,
                localNodeName = localNodeName(),
                youLabel = youLabel,
                unknownHopLabel = unknownHopLabel,
            ),
        )
        LaunchedEffect(
            item.id,
            availability.canShowRepeatDetails,
            availability.canViewPath,
            availability.canShowIncomingPaths,
            contactService(),
            radioId(),
        ) {
            if (availability.canShowRepeatDetails) {
                onLoadRepeats(item)
            }
            if (availability.canViewPath || availability.canShowIncomingPaths) {
                inlinePathDisplay = loadInlinePath()
            }
        }
        val callbacks = threadMessageActionCallbacks(
            availability = availability,
            inputs = ThreadActionCallbackInputs(
                item = item,
                actions = actions,
                overlayScope = overlayScope,
                inlinePathDisplay = { inlinePathDisplay },
                loadInlinePath = { loadInlinePath() },
                onSelectedChange = onSelectedChange,
                onBlockSenderItemChange = onBlockSenderItemChange,
                onSendDmItemChange = onSendDmItemChange,
                onPathMapDisplayChange = onPathMapDisplayChange,
                onRepeatItemChange = onRepeatItemChange,
                onLoadRepeats = onLoadRepeats,
            ),
        )
        MessageActionsSheet(
            messageDetails = messageDetails,
            pathPresentation = inlinePathDisplay,
            isNetworkOffline = isNetworkOffline,
            item = item,
            replyWithQuote = replyWithQuote,
            onCopy = {
                val original = rawText(item)
                val chrome = translationPhase(item.id)
                clipboardCopy(
                    if (chrome is MessageTranslationPhase.Showing) {
                        chrome.translatedText
                    } else {
                        original
                    },
                )
            },
            onTranslate = { onSheetTranslate(item) },
            translateLabel = when (val chrome = translationPhase(item.id)) {
                MessageTranslationPhase.InProgress ->
                    stringResource(Res.string.chats_message_action_translating)
                is MessageTranslationPhase.Showing ->
                    stringResource(Res.string.chats_message_action_show_original)
                else -> stringResource(Res.string.chats_message_action_translate)
            },
            onDelete = { actions.onDelete(item.id) },
            onDismiss = { onSelectedChange(null) },
            onReply = callbacks.onReply,
            onSendAgain = callbacks.onSendAgain,
            onBlockSender = callbacks.onBlockSender,
            onSendDm = callbacks.onSendDm,
            onOpenPathMap = callbacks.onOpenPathMap,
            onOpenRepeatDetails = callbacks.onOpenRepeatDetails,
            recentEmojis = recentEmojis,
            frequentEmojis = frequentEmojis,
            onReact = { emoji -> onReact(item, emoji) },
        )
    }
}

private fun rawText(item: MessageItem): String = item.content.filterIsInstance<MessageFragment.Text>()
    .joinToString("\n") { it.payload.formattedText?.displayString ?: it.payload.raw }
