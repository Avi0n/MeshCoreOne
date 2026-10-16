package one.meshcore.shared.channel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.channel.ScanChannelController
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.navigation.MeshCoreDeepLink
import one.meshcore.shared.platformName
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_scan_camera_permission_message
import one.meshcore.shared.resources.chats_channel_info_region
import one.meshcore.shared.resources.chats_channel_info_secret_key
import one.meshcore.shared.resources.chats_create_private_channel_name
import one.meshcore.shared.resources.chats_join_from_message_hashtag_secret_mismatch
import one.meshcore.shared.resources.chats_join_private_join_button
import one.meshcore.shared.resources.chats_scan_qr_instruction
import one.meshcore.shared.resources.chats_scan_qr_open_settings
import one.meshcore.shared.resources.chats_scan_qr_permission_denied_message
import one.meshcore.shared.resources.chats_scan_qr_permission_denied_title
import one.meshcore.shared.resources.chats_scan_qr_scan_again
import one.meshcore.shared.resources.chats_scan_qr_title
import one.meshcore.shared.ui.EMPTY_STATE_ICON_SIZE
import one.meshcore.shared.ui.FormErrorRow
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.ScanPermissionDenied
import one.meshcore.shared.ui.ScanQrOverlayCaption
import one.meshcore.shared.ui.ScanQrViewfinderFrame
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubFormScroll
import org.jetbrains.compose.resources.stringResource

/**
 * The channel-QR scan screen: a camera preview that auto-captures the first code, a confirmation of the
 * decoded name and secret with Join / Scan again, and the permission-denied state. Driven by a
 * [ScanChannelController]. The host supplies the camera slot.
 */
@Composable
fun ChannelScanScreen(
    providers: ChannelFlowProviders,
    availableSlots: List<Int>,
    onBack: () -> Unit,
    onJoined: (ChannelDto?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val latestProviders = rememberUpdatedState(providers)
    val latestAvailableSlots = rememberUpdatedState(availableSlots)
    val controller = remember {
        ScanChannelController(
            ScanChannelController.Dependencies(
                { latestProviders.value.channelService() },
                { latestProviders.value.radioId() },
                { latestAvailableSlots.value },
                providers.floodPersistLogger,
            ),
        )
    }
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.successTick) { if (state.successTick > 0) providers.onScanHaptic() }

    ChannelFlowScaffold(stringResource(Res.string.chats_scan_qr_title), onBack, modifier) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val scanned = state.scanned
            when {
                state.permissionDenied -> PermissionDenied(providers.onOpenSettings)
                scanned != null -> ScanConfirmation(
                    channel = scanned,
                    isJoining = state.isJoining,
                    errorText = joinErrorText(state.error),
                    onJoin = { scope.launch { controller.join(onJoined) } },
                    onScanAgain = controller::scanAgain,
                )
                else -> Scanner(providers, controller::onDecoded, controller::onPermissionDenied)
            }
        }
    }
}

@Composable
private fun joinErrorText(error: one.meshcore.shared.channel.ScanChannelError?): String? =
    (error as? one.meshcore.shared.channel.ScanChannelError.Join)?.error?.message()

@Composable
private fun Scanner(providers: ChannelFlowProviders, onDecoded: (String) -> Unit, onPermissionDenied: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        providers.scanner(onDecoded, onPermissionDenied)
        ScanQrViewfinderFrame()
        Column(
            Modifier.fillMaxSize().padding(bottom = 50.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScanQrOverlayCaption(
                text = stringResource(Res.string.chats_scan_qr_instruction),
            )
        }
    }
}

@Composable
private fun ScanConfirmation(
    channel: MeshCoreDeepLink.Channel,
    isJoining: Boolean,
    errorText: String?,
    onJoin: () -> Unit,
    onScanAgain: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(bottom = 24.dp),
    ) {
        Column(
            preferenceHubFormScroll(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PreferenceHubSection {
                PreferenceHubValueRow(stringResource(Res.string.chats_create_private_channel_name), channel.name)
                PreferenceHubValueRow(
                    stringResource(Res.string.chats_channel_info_secret_key),
                    channel.secret.toUppercaseHex(),
                    monospace = true,
                )
                // A scanned code may carry a flood-scope region, mirroring the iOS scan confirmation's Region row.
                channel.regionScope?.let { region ->
                    PreferenceHubValueRow(stringResource(Res.string.chats_channel_info_region), region)
                }
            }
            // A `#name` hashtag whose secret is not the public name-hash joins a private channel of that name;
            // warn the same way iOS does before the join.
            if (channel.hasHashtagSecretMismatch) {
                PreferenceHubFooter(
                    stringResource(Res.string.chats_join_from_message_hashtag_secret_mismatch),
                )
            }
            errorText?.let { FormErrorRow(it) }
        }
        Button(
            onClick = onJoin,
            enabled = !isJoining,
            modifier = Modifier.fillMaxWidth().padding(horizontal = PreferenceHubHorizontalPadding),
        ) {
            if (isJoining) Spinner() else Text(stringResource(Res.string.chats_join_private_join_button))
        }
        OutlinedButton(
            onClick = onScanAgain,
            modifier = Modifier.fillMaxWidth().padding(horizontal = PreferenceHubHorizontalPadding),
        ) {
            Text(stringResource(Res.string.chats_scan_qr_scan_again))
        }
    }
}

@Composable
private fun PermissionDenied(onOpenSettings: () -> Unit) {
    ScanPermissionDenied(
        icon = CHANNEL_SCAN_PERMISSION_DENIED_ICON,
        glyphSize = EMPTY_STATE_ICON_SIZE,
        title = stringResource(Res.string.chats_scan_qr_permission_denied_title),
        message = stringResource(
            if (platformName() == "Android") {
                Res.string.android_scan_camera_permission_message
            } else {
                Res.string.chats_scan_qr_permission_denied_message
            },
        ),
        settingsLabel = stringResource(Res.string.chats_scan_qr_open_settings),
        onOpenSettings = onOpenSettings,
    )
}

/** Camera-denied empty uses the viewfinder glyph. */
internal val CHANNEL_SCAN_PERMISSION_DENIED_ICON get() = AppIcons.QrCodeScanner
