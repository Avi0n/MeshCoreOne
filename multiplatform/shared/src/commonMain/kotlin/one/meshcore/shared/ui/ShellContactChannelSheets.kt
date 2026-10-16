package one.meshcore.shared.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.channel.ui.ChannelFlowProviders
import one.meshcore.shared.channel.ui.ChannelOptionsSheet
import one.meshcore.shared.channel.ui.JoinChannelLinkSheet
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.ui.AddContactRoot
import one.meshcore.shared.contact.ui.ContactQrShareSheet
import one.meshcore.shared.contact.ui.ContactScanRoot
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.clearPendingChannelLink
import one.meshcore.shared.navigation.clearPendingContactLink
import one.meshcore.shared.navigation.navigateToChannel
import one.meshcore.shared.navigation.navigateToContactDetail
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_join_from_message_title
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShellContactChannelSheets(
    sheet: ShellSheet,
    deps: ShellDependencies,
    onSheetChange: (ShellSheet?) -> Unit,
    dismiss: () -> Unit,
    coordinator: NavigationCoordinator,
    channelProviders: ChannelFlowProviders,
    sheetScope: CoroutineScope,
) {
    when (sheet) {
        ShellSheet.AddContact -> AddContactRoot(
            contactService = deps.contactService,
            radioId = deps.currentRadioId,
            isRadioConnected = { deps.connectionState.value.isConnected },
            maxContacts = deps.maxContacts,
            now = deps.nowSeconds,
            onScanRequested = { onSheetChange(ShellSheet.ScanContact) },
            onCancel = dismiss,
            onAdded = dismiss,
            readClipboard = deps.readClipboard,
        )

        ShellSheet.ScanContact -> ContactScanRoot(
            contactService = deps.contactService,
            radioId = deps.currentRadioId,
            isRadioConnected = { deps.connectionState.value.isConnected },
            maxContacts = deps.maxContacts,
            now = deps.nowSeconds,
            onScanned = { contact ->
                dismiss()
                coordinator.navigateToContactDetail(contact.id.value)
            },
            onBack = { onSheetChange(ShellSheet.AddContact) },
            scanner = deps.contactScanner,
            onOpenSettings = deps.openAppSettings,
            onScanSuccess = deps.scanHaptic,
        )

        ShellSheet.ShareMyContact -> ShareMyContactSheet(deps, onDone = dismiss)

        is ShellSheet.ShareContact -> ContactQrShareSheet(
            contactName = sheet.contact.displayName,
            publicKey = sheet.contact.publicKey,
            contactType = sheet.contact.contactType,
            onDone = dismiss,
            encode = deps.qrEncode,
            onCopy = deps.copyToClipboard,
            onShare = { text, subject -> deps.sharePlainText(text, subject) },
        )

        ShellSheet.ChannelOptions -> ChannelOptionsSheet(
            providers = channelProviders,
            onDismiss = {
                // iOS `ChatsConversationSheets` reloads on Channel Options dismiss so a still-
                // composed list (wide sidebar, cancel, or create/join) sees the new slot.
                sheetScope.launch { deps.onConversationsChanged() }
                dismiss()
            },
            onChannelChanged = { channel ->
                dismiss()
                coordinator.navigateToChannel(channel.id.value)
            },
        )

        is ShellSheet.ConfirmAddContact -> ConfirmAddContactSheet(
            contact = sheet.contact,
            deps = deps,
            onDone = {
                dismiss()
                coordinator.clearPendingContactLink()
            },
            onAdded = { contactId ->
                dismiss()
                coordinator.clearPendingContactLink()
                if (contactId != null) coordinator.navigateToContactDetail(contactId)
            },
        )

        is ShellSheet.JoinChannelLink -> {
            val onJoinDismiss = {
                dismiss()
                coordinator.clearPendingChannelLink()
            }
            FullScreenOverlay(
                onDismiss = onJoinDismiss,
                title = stringResource(Res.string.chats_join_from_message_title),
            ) { innerPadding ->
                JoinChannelLinkSheet(
                    channel = sheet.channel,
                    providers = channelProviders,
                    onDismiss = onJoinDismiss,
                    onJoined = { channel ->
                        dismiss()
                        coordinator.clearPendingChannelLink()
                        coordinator.navigateToChannel(channel.id.value)
                    },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }

        is ShellSheet.MentionPicker -> MentionPickerSheetHost(
            sheet = sheet,
            deps = deps,
            onDismiss = dismiss,
            onOpenContact = { contactId ->
                deps.coordinator.navigateToContactDetail(contactId)
                dismiss()
            },
        )
        else -> Unit
    }
}
