package one.meshcore.shared.channel.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.channel.ChannelInfoController
import one.meshcore.shared.channel.displayName
import one.meshcore.shared.contact.ui.QrEncoder
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_default_name
import one.meshcore.shared.settings.ui.RegionDiscoveryResultsScreen
import one.meshcore.shared.settings.ui.RegionManagementScreen
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen host for [ChannelInfoSheet] so a channel thread toolbar can present mute/favorite/flood/share
 * as a nested destination instead of a clipped iOS-height sheet.
 */
@Composable
fun ChannelInfoOverlay(
    channel: ChannelDto,
    dependencies: ChannelInfoController.Dependencies,
    encode: QrEncoder,
    onCopy: (String) -> Unit,
    onDeleted: () -> Unit,
    onCleared: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentDependencies by rememberUpdatedState(dependencies)
    val controller = remember(channel) { ChannelInfoController(channel) { currentDependencies } }
    val state by controller.state.collectAsStateWithLifecycle()
    var showRegionManagement by remember(channel) { mutableStateOf(false) }
    val unnamedTemplate = stringResource(Res.string.chats_channel_default_name)

    FullScreenOverlay(
        onDismiss = onDismiss,
        title = channel.displayName { formatTemplate(unnamedTemplate, listOf(it)) },
    ) { padding ->
        ChannelInfoSheet(
            channel = channel,
            controller = controller,
            encode = encode,
            onCopy = onCopy,
            onDeleted = onDeleted,
            onCleared = onCleared,
            onManageRegions = { showRegionManagement = true },
            modifier = Modifier.padding(padding),
        )
    }

    val discovered = state.discoveredRegions
    if (discovered != null) {
        FullScreenOverlay(onDismiss = { controller.regions.dismissDiscoveryResults() }) {
            RegionDiscoveryResultsScreen(
                discoveredRegions = discovered,
                onAdd = { selected -> scope.launch { controller.regions.confirmDiscoveredRegions(selected) } },
                onBack = { controller.regions.dismissDiscoveryResults() },
            )
        }
    } else if (showRegionManagement) {
        FullScreenOverlay(onDismiss = { showRegionManagement = false }) {
            RegionManagementScreen(
                knownRegions = controller.knownRegions(),
                onBack = { showRegionManagement = false },
                onRemoveRegion = { controller.regions.removeRegion(it) },
                onAddRegion = { controller.regions.addRegion(it) },
                onDiscover = { controller.regions.runRegionDiscovery() },
                discoverMessage = { controller.regions.regionDiscoverMessage(it) },
            )
        }
    }
}
