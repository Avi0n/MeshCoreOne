package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.NavigationEventState
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.chat.pathdetail.loadContacts
import one.meshcore.shared.chat.text.HashtagChannelRef
import one.meshcore.shared.chat.text.HashtagTapResult
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.text.MentionTapEvaluator
import one.meshcore.shared.chat.text.MentionTapResult
import one.meshcore.shared.chat.text.resolveHashtagTap
import one.meshcore.shared.chat.ui.PendingChatScroll
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.SendAdvertShortcutOutcome
import one.meshcore.shared.navigation.SendMessageShortcutDialog
import one.meshcore.shared.navigation.SettingsDestination
import one.meshcore.shared.navigation.ToolSelection
import one.meshcore.shared.navigation.consumePendingScrollRequest
import one.meshcore.shared.navigation.consumeSendAdvert
import one.meshcore.shared.navigation.consumeSendMessage
import one.meshcore.shared.navigation.navigateToChannel
import one.meshcore.shared.navigation.navigateToContactDetail
import one.meshcore.shared.navigation.sendMessageShortcutDialog
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_common_ok
import one.meshcore.shared.resources.chats_tip_device_menu_message
import one.meshcore.shared.resources.chats_tip_device_menu_title
import one.meshcore.shared.resources.localizable_common_cancel
import one.meshcore.shared.resources.localizable_error_advertisement_send_failed
import one.meshcore.shared.resources.localizable_error_intent_invalid_recipient
import one.meshcore.shared.resources.localizable_error_intent_message_too_long
import one.meshcore.shared.resources.localizable_error_intent_not_connected
import one.meshcore.shared.resources.tools_intent_advert_confirm
import one.meshcore.shared.resources.tools_intent_advert_reach_flood
import one.meshcore.shared.resources.tools_intent_advert_reach_zero_hop
import one.meshcore.shared.resources.tools_intent_advert_short_title
import one.meshcore.shared.resources.tools_intent_advert_title
import one.meshcore.shared.resources.tools_intent_send_confirm
import one.meshcore.shared.resources.tools_intent_send_short_title
import one.meshcore.shared.resources.tools_intent_send_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun shellNetworkOffline(deps: ShellDependencies): Boolean {
    val reported = deps.networkOffline?.collectAsStateWithLifecycle()?.value
    return reported ?: deps.isNetworkOffline()
}

@Composable
internal fun ShellSendMessageDialogs(deps: ShellDependencies, scope: CoroutineScope) {
    val pendingSendMessage by deps.coordinator.pendingSendMessage.collectAsStateWithLifecycle()
    var sendMessageDialog by remember { mutableStateOf(SendMessageShortcutDialog.None) }
    pendingSendMessage?.let { request ->
        val recipient = request.targetLabel?.takeIf { it.isNotBlank() } ?: request.targetId
        ConfirmAlertDialog(
            title = stringResource(Res.string.tools_intent_send_title),
            message = stringResource(Res.string.tools_intent_send_confirm, recipient),
            confirmLabel = stringResource(Res.string.tools_intent_send_short_title),
            dismissLabel = stringResource(Res.string.localizable_common_cancel),
            onConfirm = {
                scope.launch {
                    val outcome = deps.enqueueSendMessage(request)
                    deps.coordinator.consumeSendMessage()
                    sendMessageDialog = sendMessageShortcutDialog(outcome)
                }
            },
            onDismiss = deps.coordinator::consumeSendMessage,
        )
    }
    if (sendMessageDialog != SendMessageShortcutDialog.None) {
        val errorText = when (sendMessageDialog) {
            SendMessageShortcutDialog.TooLong ->
                stringResource(Res.string.localizable_error_intent_message_too_long)
            SendMessageShortcutDialog.InvalidRecipient ->
                stringResource(Res.string.localizable_error_intent_invalid_recipient)
            SendMessageShortcutDialog.NotConnected ->
                stringResource(Res.string.localizable_error_intent_not_connected)
            SendMessageShortcutDialog.None -> ""
        }
        AcknowledgedAlertDialog(
            title = stringResource(Res.string.tools_intent_send_title),
            message = errorText,
            confirmLabel = stringResource(Res.string.chats_common_ok),
            onDismiss = { sendMessageDialog = SendMessageShortcutDialog.None },
        )
    }
}

@Composable
internal fun ShellSendAdvertDialogs(deps: ShellDependencies, scope: CoroutineScope) {
    val pendingSendAdvert by deps.coordinator.pendingSendAdvert.collectAsStateWithLifecycle()
    var sendAdvertDialog by remember { mutableStateOf<SendAdvertShortcutOutcome?>(null) }
    pendingSendAdvert?.let { request ->
        val reach = stringResource(
            if (request.flood) {
                Res.string.tools_intent_advert_reach_flood
            } else {
                Res.string.tools_intent_advert_reach_zero_hop
            },
        )
        ConfirmAlertDialog(
            title = stringResource(Res.string.tools_intent_advert_title),
            message = stringResource(Res.string.tools_intent_advert_confirm, reach),
            confirmLabel = stringResource(Res.string.tools_intent_advert_short_title),
            dismissLabel = stringResource(Res.string.localizable_common_cancel),
            onConfirm = {
                scope.launch {
                    val outcome = deps.sendAdvertShortcut(request.flood)
                    deps.coordinator.consumeSendAdvert()
                    if (outcome != SendAdvertShortcutOutcome.Sent) sendAdvertDialog = outcome
                }
            },
            onDismiss = deps.coordinator::consumeSendAdvert,
        )
    }
    sendAdvertDialog?.let { outcome ->
        val errorText = when (outcome) {
            SendAdvertShortcutOutcome.NotConnected ->
                stringResource(Res.string.localizable_error_intent_not_connected)
            SendAdvertShortcutOutcome.Failed ->
                stringResource(Res.string.localizable_error_advertisement_send_failed)
            SendAdvertShortcutOutcome.Sent -> ""
        }
        AcknowledgedAlertDialog(
            title = stringResource(Res.string.tools_intent_advert_title),
            message = errorText,
            confirmLabel = stringResource(Res.string.chats_common_ok),
            onDismiss = { sendAdvertDialog = null },
        )
    }
}

@Composable
internal fun ShellStagedSheets(deps: ShellDependencies, sheet: ShellSheet?, onSheet: (ShellSheet?) -> Unit) {
    val pendingContactLink by deps.coordinator.pendingContactLink.collectAsStateWithLifecycle()
    val pendingChannelLink by deps.coordinator.pendingChannelLink.collectAsStateWithLifecycle()
    val pendingHashtagJoin by deps.coordinator.pendingHashtagJoin.collectAsStateWithLifecycle()
    val pendingRoomAuth by deps.coordinator.pendingRoomAuth.collectAsStateWithLifecycle()
    val radioSheetEpoch by deps.coordinator.radioSheetEpoch.collectAsStateWithLifecycle()
    LaunchedEffect(pendingContactLink) {
        val link = pendingContactLink
        if (link != null) {
            onSheet(ShellSheet.ConfirmAddContact(link))
        } else if (sheet is ShellSheet.ConfirmAddContact) {
            onSheet(null)
        }
    }
    LaunchedEffect(pendingChannelLink) {
        val link = pendingChannelLink
        if (link != null) {
            onSheet(ShellSheet.JoinChannelLink(link))
        } else if (sheet is ShellSheet.JoinChannelLink) {
            onSheet(null)
        }
    }
    LaunchedEffect(pendingHashtagJoin) {
        val tag = pendingHashtagJoin
        if (tag != null) {
            onSheet(ShellSheet.JoinHashtagFromMessage(tag))
        } else if (sheet is ShellSheet.JoinHashtagFromMessage) {
            onSheet(null)
        }
    }
    LaunchedEffect(pendingRoomAuth) {
        val sessionId = pendingRoomAuth
        if (sessionId != null) {
            onSheet(ShellSheet.RoomAuth(sessionId))
        } else if (sheet is ShellSheet.RoomAuth) {
            onSheet(null)
        }
    }
    LaunchedEffect(radioSheetEpoch) {
        if (radioSheetEpoch == 0) return@LaunchedEffect
        val current = sheet
        if (current != null && current.dismissOnRadioTeardown) {
            deps.coordinator.clearStagingFor(current)
            onSheet(null)
        }
    }
}

@Composable
internal fun rememberShellBackState(
    sheet: ShellSheet?,
    currentTab: AppTab,
    selectedTool: ToolSelection?,
    selectedSetting: SettingsDestination?,
    backStack: List<*>?,
    onSheet: (ShellSheet?) -> Unit,
    onSystemBack: () -> Unit,
    onClearStaging: (ShellSheet) -> Unit,
): NavigationEventState<NavigationEventInfo.None> {
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = state,
        isBackEnabled = shellCanHandleBack(sheet, currentTab, selectedTool, selectedSetting, backStack),
        onBackCompleted = {
            val openSheet = sheet
            if (openSheet != null) {
                val parent = openSheet.backParent
                if (parent != null) {
                    onSheet(parent)
                } else {
                    onClearStaging(openSheet)
                    onSheet(null)
                }
            } else {
                onSystemBack()
            }
        },
    )
    return state
}

internal fun coordinatorChatScroll(coordinator: NavigationCoordinator): PendingChatScroll = object : PendingChatScroll {
    override val requests = coordinator.pendingScrollTarget
    override fun peek() = requests.value
    override fun consume(requestID: Uuid): Boolean = coordinator.consumePendingScrollRequest(requestID)
}

internal fun CoroutineScope.handleShellLinkTap(
    target: LinkTarget,
    deps: ShellDependencies,
    current: ShellSheet?,
    onSheet: (ShellSheet?) -> Unit,
) {
    when (target) {
        is LinkTarget.Mention -> launch {
            val next = openMentionLink(target, deps)
            if (next != null) onSheet(next)
        }
        is LinkTarget.Hashtag -> launch {
            val next = openHashtagLink(target, deps)
            if (next != null) onSheet(next)
        }
        else -> onSheet(dispatchLinkTap(target, deps, current))
    }
}

internal suspend fun openMentionLink(target: LinkTarget.Mention, deps: ShellDependencies): ShellSheet? {
    val contacts = loadContacts(deps.contactService(), deps.currentRadioId())
    return when (
        val result = MentionTapEvaluator.evaluate(
            name = target.name,
            contacts = contacts,
            connectedDeviceName = deps.localNodeName.value,
        )
    ) {
        is MentionTapResult.NavigateToContact -> {
            deps.coordinator.navigateToContactDetail(result.contact.id.value)
            null
        }
        is MentionTapResult.Picker -> ShellSheet.MentionPicker(
            name = result.name,
            matches = result.matches,
            isSelfMention = result.isSelfMention,
        )
    }
}

internal suspend fun openHashtagLink(target: LinkTarget.Hashtag, deps: ShellDependencies): ShellSheet? {
    val radio = deps.currentRadioId()
    val result = resolveHashtagTap(rawName = target.name, hasRadio = radio != null) {
        val channels = radio?.let { deps.conversationListStore()?.fetchChannels(it) }.orEmpty()
        channels.map { HashtagChannelRef(id = it.id.value, name = it.name) }
    }
    return when (result) {
        is HashtagTapResult.OpenChannel -> {
            deps.coordinator.navigateToChannel(result.channelId)
            null
        }
        is HashtagTapResult.StageJoin -> ShellSheet.JoinHashtagFromMessage(result.fullName)
        HashtagTapResult.Invalid -> null
    }
}

@Composable
internal fun DeviceMenuCoachMark(
    connectionState: DeviceConnectionState,
    coachMarkStore: CoachMarkStore?,
    showsIndicator: Boolean,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    if (coachMarkStore == null || !showsIndicator) return
    if (connectionState != DeviceConnectionState.READY) return
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(coachMarkStore) {
        visible = !coachMarkStore.hasSeen(CoachMarkStore.DEVICE_MENU)
    }
    if (!visible) return
    CoachMarkBanner(
        title = stringResource(Res.string.chats_tip_device_menu_title),
        message = stringResource(Res.string.chats_tip_device_menu_message),
        onDismiss = {
            visible = false
            scope.launch { coachMarkStore.markSeen(CoachMarkStore.DEVICE_MENU) }
        },
        modifier = modifier,
    )
}
