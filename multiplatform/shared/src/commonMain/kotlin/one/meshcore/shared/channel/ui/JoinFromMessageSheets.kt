package one.meshcore.shared.channel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.channel.ChannelJoinPhase
import one.meshcore.shared.channel.JoinChannelLinkController
import one.meshcore.shared.channel.JoinHashtagFromMessageController
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.navigation.MeshCoreDeepLink
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_common_ok
import one.meshcore.shared.resources.chats_join_from_message_description
import one.meshcore.shared.resources.chats_join_from_message_hashtag_secret_mismatch
import one.meshcore.shared.resources.chats_join_from_message_join_button
import one.meshcore.shared.resources.chats_join_from_message_loading
import one.meshcore.shared.resources.chats_join_from_message_no_device_description
import one.meshcore.shared.resources.chats_join_from_message_no_device_title
import one.meshcore.shared.resources.chats_join_from_message_no_slots_description
import one.meshcore.shared.resources.chats_join_from_message_no_slots_title
import one.meshcore.shared.resources.chats_join_from_message_region_scope
import one.meshcore.shared.ui.EMPTY_STATE_ICON_SIZE
import one.meshcore.shared.ui.FormErrorRow
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubTopPadding
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

private const val SECRET_TRUNCATE_HALF = 8

/**
 * The confirmation shown when a `meshcore://channel/add` link is tapped in a message (the Kotlin analogue of
 * the iOS `JoinChannelConfirmationSheet`). The tapped [channel] arrives from the link router; this sheet
 * runs the [JoinChannelLinkController] three-state gate and joins on confirm.
 */
@Composable
fun JoinChannelLinkSheet(
    channel: MeshCoreDeepLink.Channel,
    providers: ChannelFlowProviders,
    onDismiss: () -> Unit,
    onJoined: (ChannelDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val latestProviders = rememberUpdatedState(providers)
    val controller = remember(channel) {
        JoinChannelLinkController(
            channel,
            JoinChannelLinkController.Dependencies(
                { latestProviders.value.channelService() },
                { latestProviders.value.radioId() },
                { latestProviders.value.maxChannels() },
                providers.floodPersistLogger,
            ),
        )
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val haptics = rememberAppHaptics()
    val channelService = providers.channelService()
    val radioId = providers.radioId()
    val maxChannels = providers.maxChannels()
    LaunchedEffect(controller, channelService, radioId, maxChannels) { controller.load() }
    // The iOS `JoinChannelConfirmationSheet` error feedback; success fires through the join callback below.
    LaunchedEffect(state.error) { if (state.error != null) haptics.failure() }

    val regionScopeText =
        channel.regionScope?.let { formattedStringResource(Res.string.chats_join_from_message_region_scope, it) }
    val mismatchWarning =
        if (channel.hasHashtagSecretMismatch) {
            stringResource(Res.string.chats_join_from_message_hashtag_secret_mismatch)
        } else {
            null
        }

    JoinFromMessagePhase(
        phase = state.phase,
        targetName = channel.name,
        isJoining = state.isJoining,
        errorText = state.error?.message(),
        description = truncatedSecret(channel),
        onDismiss = onDismiss,
        onJoin = {
            scope.launch {
                controller.join {
                    haptics.success()
                    onJoined(it)
                }
            }
        },
        modifier = modifier,
        regionScopeText = regionScopeText,
        mismatchWarning = mismatchWarning,
    )
}

/**
 * The confirmation shown when a hashtag is tapped in a message (the Kotlin analogue of the iOS
 * `JoinHashtagFromMessageView`): [rawChannelName] is the tapped `#name`. Runs the
 * [JoinHashtagFromMessageController] gate and joins on confirm.
 */
@Composable
fun JoinHashtagFromMessageSheet(
    rawChannelName: String,
    providers: ChannelFlowProviders,
    onDismiss: () -> Unit,
    onJoined: (ChannelDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val latestProviders = rememberUpdatedState(providers)
    val controller = remember(rawChannelName) {
        JoinHashtagFromMessageController(
            rawChannelName,
            JoinHashtagFromMessageController.Dependencies(
                { latestProviders.value.channelService() },
                { latestProviders.value.radioId() },
                { latestProviders.value.maxChannels() },
            ),
        )
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val haptics = rememberAppHaptics()
    val channelService = providers.channelService()
    val radioId = providers.radioId()
    val maxChannels = providers.maxChannels()
    LaunchedEffect(controller, channelService, radioId, maxChannels) { controller.load() }
    LaunchedEffect(state.error) { if (state.error != null) haptics.failure() }

    JoinFromMessagePhase(
        phase = state.phase,
        targetName = controller.fullChannelName,
        isJoining = state.isJoining,
        errorText = state.error?.message(),
        description = stringResource(Res.string.chats_join_from_message_description),
        onDismiss = onDismiss,
        onJoin = {
            scope.launch {
                controller.join {
                    haptics.success()
                    onJoined(it)
                }
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun JoinFromMessagePhase(
    phase: ChannelJoinPhase,
    targetName: String,
    isJoining: Boolean,
    errorText: String?,
    description: String,
    onDismiss: () -> Unit,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
    regionScopeText: String? = null,
    mismatchWarning: String? = null,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = PreferenceHubHorizontalPadding)
            .padding(top = PreferenceHubTopPadding, bottom = JOIN_FROM_MESSAGE_BOTTOM_PADDING),
    ) {
        when (phase) {
            ChannelJoinPhase.LOADING -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(Res.string.chats_join_from_message_loading))
                }
            }
            ChannelJoinPhase.MISSING_DEVICE -> {
                UnavailableState(
                    title = stringResource(Res.string.chats_join_from_message_no_device_title),
                    message = formattedStringResource(
                        Res.string.chats_join_from_message_no_device_description,
                        targetName,
                    ),
                    icon = joinFromMessageUnavailableIcon(isMissingDevice = true),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.chats_common_ok))
                }
            }
            ChannelJoinPhase.NO_SLOTS -> {
                UnavailableState(
                    title = stringResource(Res.string.chats_join_from_message_no_slots_title),
                    message = formattedStringResource(
                        Res.string.chats_join_from_message_no_slots_description,
                        targetName,
                    ),
                    icon = joinFromMessageUnavailableIcon(isMissingDevice = false),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.chats_common_ok))
                }
            }
            ChannelJoinPhase.READY -> {
                ConfirmState(
                    targetName = targetName,
                    description = description,
                    errorText = errorText,
                    regionScopeText = regionScopeText,
                    mismatchWarning = mismatchWarning,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onJoin, enabled = !isJoining, modifier = Modifier.fillMaxWidth()) {
                    if (isJoining) {
                        Spinner()
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    }
                    Text(formattedStringResource(Res.string.chats_join_from_message_join_button, targetName))
                }
            }
        }
    }
}

@Composable
private fun ConfirmState(
    targetName: String,
    description: String,
    errorText: String?,
    regionScopeText: String? = null,
    mismatchWarning: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(targetName, style = MaterialTheme.typography.headlineSmall)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        regionScopeText?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        mismatchWarning?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        errorText?.let { FormErrorRow(it) }
    }
}

@Composable
private fun UnavailableState(title: String, message: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
        )
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** iOS join-from-message unavailable: antenna slash vs `number.circle.fill`. */
internal fun joinFromMessageUnavailableIcon(isMissingDevice: Boolean): ImageVector =
    if (isMissingDevice) AppIcons.CellTowerOff else AppIcons.LooksOne

private val JOIN_FROM_MESSAGE_BOTTOM_PADDING = 24.dp

/** The link secret as `first8…last8` uppercase hex, matching the iOS confirmation subtitle. */
private fun truncatedSecret(channel: MeshCoreDeepLink.Channel): String {
    val hex = channel.secret.toUppercaseHex()
    if (hex.length < SECRET_TRUNCATE_HALF * 2) return hex
    return "${hex.take(SECRET_TRUNCATE_HALF)}…${hex.takeLast(SECRET_TRUNCATE_HALF)}"
}
