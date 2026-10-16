package one.meshcore.shared.channel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.channel.ChannelOptionsController
import one.meshcore.shared.channel.ChannelOptionsState
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_options_create_private_description
import one.meshcore.shared.resources.chats_channel_options_create_private_title
import one.meshcore.shared.resources.chats_channel_options_footer_has_public
import one.meshcore.shared.resources.chats_channel_options_footer_no_slots
import one.meshcore.shared.resources.chats_channel_options_join_hashtag_description
import one.meshcore.shared.resources.chats_channel_options_join_hashtag_title
import one.meshcore.shared.resources.chats_channel_options_join_private_description
import one.meshcore.shared.resources.chats_channel_options_join_private_title
import one.meshcore.shared.resources.chats_channel_options_join_public_description
import one.meshcore.shared.resources.chats_channel_options_join_public_title
import one.meshcore.shared.resources.chats_channel_options_loading
import one.meshcore.shared.resources.chats_channel_options_scan_qr_description
import one.meshcore.shared.resources.chats_channel_options_scan_qr_title
import one.meshcore.shared.resources.chats_channel_options_section_private
import one.meshcore.shared.resources.chats_channel_options_section_public
import one.meshcore.shared.resources.chats_channel_options_title
import one.meshcore.shared.resources.contacts_add_error_not_connected
import one.meshcore.shared.resources.localizable_common_status_disconnected
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.PlaceholderPane
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

private enum class ChannelOption { CREATE_PRIVATE, JOIN_PRIVATE, SCAN_QR, JOIN_PUBLIC, JOIN_HASHTAG }

/**
 * The channel create/join hub (the Kotlin analogue of the iOS `ChannelOptionsSheet`): it loads the channel
 * state through a [ChannelOptionsController], gates each option on the free slots / public-channel presence,
 * and swaps in the chosen sub-flow. A completed sub-flow reports the resulting channel via [onChannelChanged]
 * and dismisses the whole hub, matching the iOS `onChannelCreated` + `dismiss()`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelOptionsSheet(
    providers: ChannelFlowProviders,
    onDismiss: () -> Unit,
    onChannelChanged: (ChannelDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val latestProviders = rememberUpdatedState(providers)
    val controller = remember(scope) {
        ChannelOptionsController(
            ChannelOptionsController.Dependencies(
                channelService = { latestProviders.value.channelService() },
                radioId = { latestProviders.value.radioId() },
                maxChannels = { latestProviders.value.maxChannels() },
            ),
        )
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val channelService = providers.channelService()
    val radioId = providers.radioId()
    val maxChannels = providers.maxChannels()
    LaunchedEffect(controller, channelService, radioId, maxChannels) { controller.load() }

    var selected by remember { mutableStateOf<ChannelOption?>(null) }
    val complete: (ChannelDto?) -> Unit = { channel ->
        if (channel != null) onChannelChanged(channel)
        onDismiss()
    }

    val back = { selected = null }
    // Create/join/scan replace the hub (iOS NavigationStack push). Intercept system Back so it
    // returns to the options list instead of dismissing New Channel.
    if (selected != null) {
        val nestedBackState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(
            state = nestedBackState,
            isBackEnabled = true,
            onBackCompleted = {
                when (channelOptionsPopChild(selected != null)) {
                    ChannelOptionsPop.HUB -> selected = null
                    ChannelOptionsPop.SHEET -> Unit
                }
            },
        )
    }
    val slots = state.availableSlots
    when (selected) {
        null -> ChannelOptionsHub(state, onSelect = { selected = it }, onCancel = onDismiss, modifier = modifier)
        ChannelOption.CREATE_PRIVATE ->
            CreateChannelScreen(providers, slots, onBack = back, onComplete = complete, modifier = modifier)
        ChannelOption.JOIN_PRIVATE ->
            JoinPrivateChannelScreen(providers, slots, onBack = back, onJoined = complete, modifier = modifier)
        ChannelOption.SCAN_QR ->
            ChannelScanScreen(providers, slots, onBack = back, onJoined = complete, modifier = modifier)
        ChannelOption.JOIN_PUBLIC ->
            JoinPublicChannelScreen(providers, onBack = back, onJoined = complete, modifier = modifier)
        ChannelOption.JOIN_HASHTAG ->
            JoinHashtagChannelScreen(providers, slots, onBack = back, onJoined = complete, modifier = modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelOptionsHub(
    state: ChannelOptionsState,
    onSelect: (ChannelOption) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            AppNestedTopBar(
                title = stringResource(Res.string.chats_channel_options_title),
                onBack = onCancel,
            )
        },
    ) { innerPadding ->
        // Until the channel list has been read the free slots are unknown, not empty: rendering the
        // gated list first tells a user with room left that every slot is in use, as iOS avoids by
        // showing its progress view.
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(Res.string.chats_channel_options_loading))
                }
            }
            return@Scaffold
        }
        if (state.loadUnavailable) {
            PlaceholderPane(
                title = stringResource(Res.string.localizable_common_status_disconnected),
                subtitle = stringResource(Res.string.contacts_add_error_not_connected),
                icon = AppIcons.CellTowerOff,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(PreferenceHubSectionGap),
        ) {
            item {
                PreferenceHubSection(
                    title = stringResource(Res.string.chats_channel_options_section_private),
                    applyHorizontalPadding = false,
                ) {
                    ChannelOptionRow(
                        icon = AppIcons.Lock,
                        title = stringResource(Res.string.chats_channel_options_create_private_title),
                        description = stringResource(Res.string.chats_channel_options_create_private_description),
                        enabled = !state.noAvailableSlots,
                        onClick = { onSelect(ChannelOption.CREATE_PRIVATE) },
                    )
                    ChannelOptionRow(
                        icon = AppIcons.Key,
                        title = stringResource(Res.string.chats_channel_options_join_private_title),
                        description = stringResource(Res.string.chats_channel_options_join_private_description),
                        enabled = !state.noAvailableSlots,
                        onClick = { onSelect(ChannelOption.JOIN_PRIVATE) },
                    )
                    ChannelOptionRow(
                        icon = AppIcons.QrCodeScanner,
                        title = stringResource(Res.string.chats_channel_options_scan_qr_title),
                        description = stringResource(Res.string.chats_channel_options_scan_qr_description),
                        enabled = !state.noAvailableSlots,
                        onClick = { onSelect(ChannelOption.SCAN_QR) },
                    )
                }
            }
            item {
                PreferenceHubSection(
                    title = stringResource(Res.string.chats_channel_options_section_public),
                    applyHorizontalPadding = false,
                ) {
                    ChannelOptionRow(
                        icon = AppIcons.Public,
                        title = stringResource(Res.string.chats_channel_options_join_public_title),
                        description = stringResource(Res.string.chats_channel_options_join_public_description),
                        enabled = !state.hasPublicChannel,
                        onClick = { onSelect(ChannelOption.JOIN_PUBLIC) },
                    )
                    ChannelOptionRow(
                        icon = AppIcons.Tag,
                        title = stringResource(Res.string.chats_channel_options_join_hashtag_title),
                        description = stringResource(Res.string.chats_channel_options_join_hashtag_description),
                        enabled = !state.noAvailableSlots,
                        onClick = { onSelect(ChannelOption.JOIN_HASHTAG) },
                    )
                }
                val footer = when {
                    state.noAvailableSlots -> stringResource(Res.string.chats_channel_options_footer_no_slots)
                    state.hasPublicChannel -> stringResource(Res.string.chats_channel_options_footer_has_public)
                    else -> null
                }
                if (footer != null) {
                    PreferenceHubFooter(footer, applyHorizontalPadding = false)
                }
            }
        }
    }
}

@Composable
private fun ChannelOptionRow(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val contentAlpha = if (enabled) 1f else 0.38f
    val iconTint = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = contentAlpha)
    val labelColor = scheme.onSurface.copy(alpha = contentAlpha)
    ListItem(
        headlineContent = { Text(title, color = labelColor) },
        supportingContent = { Text(description, color = labelColor) },
        leadingContent = { Icon(imageVector = icon, contentDescription = null, tint = iconTint) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    )
}

/** A centered caption used by the sub-screens for the "scan to join" / description lines. */
@Composable
internal fun CenteredCaption(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
