package one.meshcore.shared.channel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.channel.ChannelActionError
import one.meshcore.shared.channel.ChannelInfoController
import one.meshcore.shared.channel.ChannelUri
import one.meshcore.shared.channel.displayName
import one.meshcore.shared.contact.ui.QrImage
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_default_name
import one.meshcore.shared.resources.chats_channel_info_clear_messages_button
import one.meshcore.shared.resources.chats_channel_info_clear_messages_confirm_message
import one.meshcore.shared.resources.chats_channel_info_clear_messages_confirm_title
import one.meshcore.shared.resources.chats_channel_info_delete_button
import one.meshcore.shared.resources.chats_channel_info_delete_confirm_message
import one.meshcore.shared.resources.chats_channel_info_delete_confirm_title
import one.meshcore.shared.resources.chats_channel_info_delete_footer
import one.meshcore.shared.resources.chats_channel_info_manual_sharing
import one.meshcore.shared.resources.chats_channel_info_manual_sharing_footer
import one.meshcore.shared.resources.chats_channel_info_scan_to_join
import one.meshcore.shared.resources.chats_channel_info_share_channel
import one.meshcore.shared.resources.chats_common_cancel
import one.meshcore.shared.resources.chats_row_pin
import one.meshcore.shared.resources.localizable_common_try_again
import one.meshcore.shared.settings.ui.SettingsToggleRow
import one.meshcore.shared.ui.ButtonIconLabel
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.FormErrorRow
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubTopPadding
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/** Which destructive action a Try Again retries; retrying the other one would destroy the wrong thing. */
private enum class DestructiveChannelAction { DELETE, CLEAR_MESSAGES }

/** Header channel-avatar diameter (the Material analog of the iOS 150-pt `ChannelAvatar`). */
private val CHANNEL_INFO_AVATAR_SIZE = 96.dp

/**
 * The channel-info sheet (the Kotlin analogue of the iOS `ChannelInfoSheet`): the type header, the
 * notification-level and pin quick actions, the flood-scope picker, the QR + secret share (private
 * channels only), and clear-messages / delete with confirmation dialogs. Driven by a [ChannelInfoController];
 * notification, pin, flood-scope persist, region discovery, and management land through
 * [ChannelInfoController.Dependencies]. Manage Regions and discovery results are full-screen
 * overlays hosted by [ChannelInfoOverlay] (iOS `navigationDestination`).
 */
@Composable
fun ChannelInfoSheet(
    channel: ChannelDto,
    controller: ChannelInfoController,
    encode: one.meshcore.shared.contact.ui.QrEncoder,
    onCopy: (String) -> Unit,
    onDeleted: () -> Unit,
    onCleared: () -> Unit,
    onManageRegions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var lastDestructiveAction by remember { mutableStateOf<DestructiveChannelAction?>(null) }

    val isPrivate = channel.index != 0 && channel.secret.any { it.toInt() != 0 }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = PreferenceHubTopPadding, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = PreferenceHubHorizontalPadding),
        ) {
            ChannelAvatar(channel = channel, size = CHANNEL_INFO_AVATAR_SIZE)
            val unnamedTemplate = stringResource(Res.string.chats_channel_default_name)
            Text(
                channel.displayName { formatTemplate(unnamedTemplate, listOf(it)) },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                channel.typeLabel(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        NotificationLevelPicker(state.notificationLevel) { level ->
            scope.launch { controller.setNotificationLevel(level) }
        }

        PreferenceHubSection {
            SettingsToggleRow(
                label = stringResource(Res.string.chats_row_pin),
                checked = state.isPinned,
                isRadioReady = true,
                onCheckedChange = { on -> scope.launch { controller.setPinned(on) } },
            )
        }

        FloodScopeSection(
            items = controller.floodScopeItems(),
            knownRegionsEmpty = controller.knownRegions().isEmpty(),
            isDiscovering = state.isDiscoveringRegions,
            discoveryMessage = state.discoveryMessage,
            canDiscover = true,
            onDiscover = { scope.launch { controller.regions.discoverNearbyRegions() } },
            onManage = onManageRegions,
            onSelect = { scope.launch { controller.selectFloodScope(it) } },
        )

        if (isPrivate) {
            PreferenceHubSection(title = stringResource(Res.string.chats_channel_info_share_channel)) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(PreferenceHubCardPadding),
                ) {
                    QrImage(
                        payload = ChannelUri.export(channel.name, channel.secret, state.floodScope),
                        encode = encode,
                    )
                    CenteredCaption(stringResource(Res.string.chats_channel_info_scan_to_join))
                }
            }
            PreferenceHubSection(title = stringResource(Res.string.chats_channel_info_manual_sharing)) {
                ShareSecretSection(
                    secretHex = channel.secret.toUppercaseHex(),
                    onCopy = onCopy,
                )
            }
            PreferenceHubFooter(stringResource(Res.string.chats_channel_info_manual_sharing_footer))
        }

        state.error?.let { error ->
            FormErrorRow(error.message(), Modifier.padding(horizontal = PreferenceHubHorizontalPadding))
            // iOS offers Try Again beside a failed delete. The retry re-runs the action that failed
            // rather than always deleting, so a failed clear-messages cannot become a delete.
            lastDestructiveAction?.takeUnless { error == ChannelActionError.PinSaveFailed }?.let { action ->
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            when (action) {
                                DestructiveChannelAction.DELETE -> controller.delete(onDeleted)
                                DestructiveChannelAction.CLEAR_MESSAGES -> controller.clearMessages(onCleared)
                            }
                        }
                    },
                    enabled = !state.isActionInProgress,
                    modifier = Modifier.padding(horizontal = PreferenceHubHorizontalPadding).fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.localizable_common_try_again))
                }
            }
        }

        OutlinedButton(
            onClick = { showClearConfirm = true },
            enabled = !state.isActionInProgress,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.padding(horizontal = PreferenceHubHorizontalPadding).fillMaxWidth(),
        ) {
            ButtonIconLabel(
                title = stringResource(Res.string.chats_channel_info_clear_messages_button),
                icon = AppIcons.HighlightOff,
            )
        }
        OutlinedButton(
            onClick = { showDeleteConfirm = true },
            enabled = !state.isActionInProgress,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.padding(horizontal = PreferenceHubHorizontalPadding).fillMaxWidth(),
        ) {
            ButtonIconLabel(
                title = stringResource(Res.string.chats_channel_info_delete_button),
                icon = AppIcons.Delete,
            )
        }
        PreferenceHubFooter(stringResource(Res.string.chats_channel_info_delete_footer))
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = stringResource(Res.string.chats_channel_info_clear_messages_confirm_title),
            message = stringResource(Res.string.chats_channel_info_clear_messages_confirm_message),
            confirmLabel = stringResource(Res.string.chats_channel_info_clear_messages_button),
            onConfirm = {
                showClearConfirm = false
                lastDestructiveAction = DestructiveChannelAction.CLEAR_MESSAGES
                scope.launch { controller.clearMessages(onCleared) }
            },
            onDismiss = { showClearConfirm = false },
        )
    }
    if (showDeleteConfirm) {
        ConfirmDialog(
            title = stringResource(Res.string.chats_channel_info_delete_confirm_title),
            message = stringResource(Res.string.chats_channel_info_delete_confirm_message),
            confirmLabel = stringResource(Res.string.chats_channel_info_delete_button),
            onConfirm = {
                showDeleteConfirm = false
                lastDestructiveAction = DestructiveChannelAction.DELETE
                scope.launch { controller.delete(onDeleted) }
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmAlertDialog(
        title = title,
        message = message,
        confirmLabel = confirmLabel,
        dismissLabel = stringResource(Res.string.chats_common_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmColor = MaterialTheme.colorScheme.error,
    )
}
