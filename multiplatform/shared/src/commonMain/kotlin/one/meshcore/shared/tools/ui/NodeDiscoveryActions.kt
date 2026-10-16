package one.meshcore.shared.tools.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.tools.NodeDiscoveryResult
import one.meshcore.shared.tools.NodeDiscoveryViewModel
import one.meshcore.shared.tools.NodeDiscoveryWorkspace

internal data class NodeDiscoveryActions(
    val scan: () -> Unit,
    val stop: () -> Unit,
    val add: (NodeDiscoveryResult) -> Unit,
)

internal fun nodeDiscoveryActions(
    scope: CoroutineScope,
    model: NodeDiscoveryViewModel,
    workspace: NodeDiscoveryWorkspace?,
    onLocalAddSuccess: () -> Unit,
): NodeDiscoveryActions = if (workspace != null) {
    NodeDiscoveryActions(workspace::scan, workspace::stopScan, workspace::addNode)
} else {
    NodeDiscoveryActions(
        scan = { model.scan(scope) },
        stop = { model.stopScan() },
        add = { row ->
            scope.launch {
                model.addNode(row)
                if (model.state.value.isAdded(row)) onLocalAddSuccess()
            }
        },
    )
}
