package one.meshcore.shared.tools.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import one.meshcore.shared.tools.NodeDiscoveryViewModel
import one.meshcore.shared.tools.NodeDiscoveryWorkspace
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Composable
internal fun rememberNodeDiscoveryModel(
    dependencies: NodeDiscoveryViewModel.Dependencies,
    workspace: NodeDiscoveryWorkspace?,
    isRadioReady: Boolean,
): NodeDiscoveryViewModel {
    if (workspace != null) {
        return workspace.bind(
            NodeDiscoveryWorkspace.Messages(
                notConnected = dependencies.notConnectedMessage,
                scanFailed = dependencies.scanFailedMessage(),
                unknownNode = dependencies.unknownNodeLabel(),
                contactError = dependencies.errorText,
            ),
        )
    }
    val current by rememberUpdatedState(dependencies)
    val model = remember {
        NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { filter, prefixOnly -> current.sendDiscover(filter, prefixOnly) },
                events = { current.events() },
                contactService = { current.contactService() },
                discoveredNodeStore = { current.discoveredNodeStore() },
                radioId = { current.radioId() },
                isRadioConnected = { current.isRadioConnected() },
                maxContacts = { current.maxContacts() },
                notConnectedMessage = { current.notConnectedMessage(it) },
                scanFailedMessage = { current.scanFailedMessage() },
                unknownNodeLabel = { current.unknownNodeLabel() },
                errorText = { current.errorText(it) },
            ),
            nowEpochSeconds = { Clock.System.now().epochSeconds },
        )
    }
    DisposableEffect(model) { onDispose { model.close() } }
    LaunchedEffect(
        model,
        dependencies.events(),
        dependencies.contactService(),
        dependencies.discoveredNodeStore(),
        dependencies.radioId(),
        isRadioReady,
    ) {
        model.stopScan(clearOwnedState = true)
    }
    return model
}
