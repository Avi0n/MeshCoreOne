package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.bestAvailableLocation
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_connect
import one.meshcore.shared.resources.tools_cli_not_connected
import one.meshcore.shared.resources.tools_cli_not_connected_description
import one.meshcore.shared.theme.AppColorSchemePreference
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.stringResource

/** The Settings → Maps basemap appearance for the node maps, or `system` when no preferences store is wired. */
@Composable
internal fun ShellDependencies.resolvedMapColorScheme(): AppColorSchemePreference =
    mapDisplayPreferences?.mapColorScheme?.collectAsStateWithLifecycle()?.value ?: AppColorSchemePreference.SYSTEM

/**
 * The responder's position for a key-prefix collision tiebreak: the phone's own fix when known, else the
 * connected radio's coordinates. Mirrors iOS `AppState.bestAvailableLocation`, the same order the map,
 * distance sort and trace hops read.
 */
internal fun ShellDependencies.bestAvailableUserLocation(): UserLocation? = bestAvailableLocation(
    phoneLocation = locationService?.currentLocation?.value,
    nodeLocation = locationSettingsSnapshot()
        ?.takeIf { it.hasLocation }
        ?.let { LatLng(it.latitude, it.longitude) },
)?.let { UserLocation(it.latitude, it.longitude) }

@Composable
internal fun LoadingCenter(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun NotConnectedPlaceholder(onConnect: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NOT_CONNECTED_SPACING),
        ) {
            Text(stringResource(Res.string.tools_cli_not_connected))
            Text(stringResource(Res.string.tools_cli_not_connected_description))
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.remotenodes_remote_nodes_auth_connect))
            }
        }
    }
}

internal val NOT_CONNECTED_SPACING = 8.dp

/**
 * Contact-detail overlay for a session that may still be loading. A missing session shows the connect
 * placeholder unless [missing] supplies another empty state, such as offline demo charts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectedSessionSheet(
    title: String,
    session: ConnectedSessionLoad,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    missing: (@Composable (PaddingValues) -> Unit)? = null,
    content: @Composable (RemoteNodeSessionDto, PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            ContactDetailOverlayTopBar(
                title = title,
                onDismiss = onDismiss,
            )
        },
    ) { innerPadding ->
        when (val resolved = session) {
            ConnectedSessionLoad.Loading -> LoadingCenter(Modifier.padding(innerPadding))
            ConnectedSessionLoad.Missing -> {
                val missingContent = missing
                if (missingContent == null) {
                    NotConnectedPlaceholder(
                        onConnect = onConnect,
                        modifier = Modifier.padding(innerPadding),
                    )
                } else {
                    missingContent(innerPadding)
                }
            }
            is ConnectedSessionLoad.Ready -> content(resolved.session, innerPadding)
        }
    }
}
