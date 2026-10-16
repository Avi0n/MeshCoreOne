package one.meshcore.shared.app

import one.meshcore.shared.connection.ConnectionUiState
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.remotenode.ui.RemoteAdminWorkspaces
import one.meshcore.shared.tools.NodeDiscoveryWorkspace
import one.meshcore.shared.tools.TracePathWorkspace

/**
 * Pill inputs and the navigation owner torn down with the connection.
 * [navigation] has no default: omitting it would carry a destination staged against one
 * radio into the next radio's session.
 */
data class AppStateRuntime(
    val connectionUi: ConnectionUiState,
    val navigation: NavigationCoordinator,
    val nodeDiscoveryWorkspace: NodeDiscoveryWorkspace? = null,
    val tracePathWorkspace: TracePathWorkspace? = null,
    val remoteAdminWorkspaces: RemoteAdminWorkspaces? = null,
)
