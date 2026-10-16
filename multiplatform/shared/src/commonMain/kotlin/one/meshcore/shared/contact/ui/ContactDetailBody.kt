@file:OptIn(ExperimentalTime::class)

package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactDetailLogic
import one.meshcore.shared.contact.ContactPingResult
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_detail_send_message
import one.meshcore.shared.resources.localizable_accessibility_requires_radio_connection
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.radioConnectionHint
import one.meshcore.shared.ui.rememberMinuteTickSeconds
import org.jetbrains.compose.resources.stringResource
import kotlin.time.ExperimentalTime

internal data class ContactDetailBodyState(
    val contact: ContactDto,
    val showFromDirectChat: Boolean,
    val isRadioReady: Boolean,
    val enablement: ContactDetailLogic.ActionEnablement,
    val allowDelete: Boolean,
    val isPinging: Boolean,
    val pingResult: ContactPingResult?,
    val pathStatus: String?,
    val isDiscoveringPath: Boolean,
    val discoverySecondsRemaining: Int?,
    val shareAdvertBusy: Boolean,
    val shareAdvertError: String?,
    val shareAdvertSuccess: Boolean,
    val isOffline: Boolean,
    val isSavingAvatar: Boolean,
)

internal data class ContactDetailBodyActions(
    val onCancelDiscovery: (() -> Unit)?,
    val onSendMessage: () -> Unit,
    val onEditPath: () -> Unit,
    val onShare: () -> Unit,
    val onShareViaAdvert: () -> Unit,
    val onShowTelemetry: (() -> Unit)?,
    val onShowSavedHistory: (() -> Unit)?,
    val onJoinRoom: (() -> Unit)?,
    val onManagement: (() -> Unit)?,
    val onOpenInMaps: (() -> Unit)?,
    val onExpandMap: () -> Unit,
    val resolveHopName: (ByteArray) -> String?,
    val onPing: (() -> Unit)?,
    val onToggleFavorite: () -> Unit,
    val onRequestBlock: () -> Unit,
    val onSaveNickname: (String) -> Unit,
    val onDiscoverPath: () -> Unit,
    val onResetPath: () -> Unit,
    val onClearMessages: () -> Unit,
    val onDelete: () -> Unit,
    val onAvatarPicked: (ByteArray) -> Unit,
    val onAvatarRemoved: () -> Unit,
)

@Composable
internal fun ContactDetailBody(
    state: ContactDetailBodyState,
    actions: ContactDetailBodyActions,
    modifier: Modifier = Modifier,
) {
    var nicknameDraft by remember(
        state.contact.id,
        state.contact.nickname,
    ) {
        mutableStateOf(state.contact.nickname.orEmpty())
    }
    var isEditingNickname by remember(state.contact.id) { mutableStateOf(false) }
    val radioHint = if (!state.isRadioReady) {
        stringResource(Res.string.localizable_accessibility_requires_radio_connection)
    } else {
        null
    }
    val typeLabel = state.contact.contactType.kindLabel()
    val type = state.contact.contactType
    val actionFlags = remember(type, state.contact.isBlocked, state.showFromDirectChat) {
        ContactDetailLogic.actionFlags(type, state.contact.isBlocked, state.showFromDirectChat)
    }
    val nowSeconds by rememberMinuteTickSeconds()

    Column(
        modifier = modifier.padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        ProfileHeader(
            contact = state.contact,
            typeLabel = typeLabel,
            isSavingAvatar = state.isSavingAvatar,
            onAvatarPicked = actions.onAvatarPicked,
            onAvatarRemoved = actions.onAvatarRemoved,
        )
        if (actionFlags.sendMessage) {
            Button(
                onClick = actions.onSendMessage,
                enabled = state.enablement.sendMessage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SECTION_HORIZONTAL)
                    .radioConnectionHint(state.isRadioReady, radioHint.orEmpty()),
            ) {
                Icon(
                    imageVector = AppIcons.Chat,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp),
                )
                Text(stringResource(Res.string.contacts_detail_send_message))
            }
        }
        ContactDetailInfoSection(
            contact = state.contact,
            isEditingNickname = isEditingNickname,
            nicknameDraft = nicknameDraft,
            onNicknameDraft = { nicknameDraft = it },
            onStartEdit = {
                nicknameDraft = state.contact.nickname.orEmpty()
                isEditingNickname = true
            },
            onCancelEdit = {
                nicknameDraft = state.contact.nickname.orEmpty()
                isEditingNickname = false
            },
            onSaveNickname = {
                actions.onSaveNickname(nicknameDraft)
                isEditingNickname = false
            },
            nowSeconds = nowSeconds,
        )
        ContactDetailLocationSection(
            contact = state.contact,
            isOffline = state.isOffline,
            onExpandMap = actions.onExpandMap,
            onOpenInMaps = actions.onOpenInMaps,
        )
        ContactDetailPathSection(
            contact = state.contact,
            resolveHopName = actions.resolveHopName,
            isDiscoveringPath = state.isDiscoveringPath,
            discoverySecondsRemaining = state.discoverySecondsRemaining,
            onCancelDiscovery = actions.onCancelDiscovery,
            enablement = state.enablement,
            onDiscoverPath = actions.onDiscoverPath,
            onResetPath = actions.onResetPath,
            onEditPath = actions.onEditPath,
            radioHint = radioHint,
            pathStatus = state.pathStatus,
        )
        ContactDetailTechnicalSection(contact = state.contact, typeLabel = typeLabel)
        ContactDetailUtilitySection(
            contact = state.contact,
            actionFlags = actionFlags,
            enablement = state.enablement,
            radioHint = radioHint,
            onJoinRoom = actions.onJoinRoom,
            onShowTelemetry = actions.onShowTelemetry,
            onShowSavedHistory = actions.onShowSavedHistory,
            onManagement = actions.onManagement,
            onToggleFavorite = actions.onToggleFavorite,
            onRequestBlock = actions.onRequestBlock,
            onShare = actions.onShare,
            shareAdvertBusy = state.shareAdvertBusy,
            shareAdvertSuccess = state.shareAdvertSuccess,
            shareAdvertError = state.shareAdvertError,
            onShareViaAdvert = actions.onShareViaAdvert,
            onPing = actions.onPing,
            isPinging = state.isPinging,
            pingResult = state.pingResult,
        )
        ContactDetailDangerSection(
            actionFlags = actionFlags,
            allowDelete = state.allowDelete,
            typeLabel = typeLabel,
            enablement = state.enablement,
            radioHint = radioHint,
            onClearMessages = actions.onClearMessages,
            onDelete = actions.onDelete,
        )
    }
}
