package one.meshcore.shared.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import one.meshcore.shared.channel.ui.ChannelFlowProviders
import one.meshcore.shared.settings.PersistentLogger

// The host owns the shared dismiss scope and channel providers. Each family renders its own sheets.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShellSheetHost(
    deps: ShellDependencies,
    sheet: ShellSheet?,
    onSheetChange: (ShellSheet?) -> Unit,
    onLinkTap: (one.meshcore.shared.chat.text.LinkTarget) -> Unit = {},
) {
    if (sheet == null) return
    val coordinator = deps.coordinator
    val dismiss = { onSheetChange(null) }
    val sheetScope = rememberCoroutineScope()
    val channelProviders = remember(deps, sheetScope) {
        ChannelFlowProviders(
            channelService = deps.channelService,
            radioId = deps.currentRadioId,
            maxChannels = deps.maxChannels,
            encode = deps.qrEncode,
            scanner = deps.channelScanner,
            onCopy = deps.copyToClipboard,
            onOpenSettings = deps.openAppSettings,
            onScanHaptic = deps.scanHaptic,
            floodPersistLogger = PersistentLogger(sheetScope, category = "ChannelJoinFloodScopeApplier"),
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        when (sheet) {
            ShellSheet.AddContact,
            ShellSheet.ScanContact,
            ShellSheet.ShareMyContact,
            is ShellSheet.ShareContact,
            ShellSheet.ChannelOptions,
            is ShellSheet.ConfirmAddContact,
            is ShellSheet.JoinChannelLink,
            is ShellSheet.MentionPicker,
            -> ShellContactChannelSheets(
                sheet = sheet,
                deps = deps,
                onSheetChange = onSheetChange,
                dismiss = dismiss,
                coordinator = coordinator,
                channelProviders = channelProviders,
                sheetScope = sheetScope,
            )

            is ShellSheet.JoinHashtagFromMessage,
            is ShellSheet.EditPath,
            ShellSheet.ConnectRadio,
            is ShellSheet.RoomAuth,
            is ShellSheet.RoomConversation,
            -> ShellPathRoomSheets(
                sheet = sheet,
                deps = deps,
                dismiss = dismiss,
                coordinator = coordinator,
                channelProviders = channelProviders,
                onLinkTap = onLinkTap,
            )

            is ShellSheet.NodeAuth,
            is ShellSheet.NodeStatus,
            is ShellSheet.TelemetryHistory,
            is ShellSheet.NodeTelemetry,
            is ShellSheet.NodeCli,
            is ShellSheet.NodeSettings,
            is ShellSheet.RoomStatus,
            is ShellSheet.RoomSettings,
            -> ShellNodeSheets(
                sheet = sheet,
                deps = deps,
                onSheetChange = onSheetChange,
                dismiss = dismiss,
                coordinator = coordinator,
            )
        }
    }
}
