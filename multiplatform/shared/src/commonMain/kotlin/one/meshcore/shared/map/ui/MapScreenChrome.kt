@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package one.meshcore.shared.map.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_tabs_map
import one.meshcore.shared.resources.map_controls_refresh
import one.meshcore.shared.ui.AppRootTopBar
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

private val MAP_REFRESH_SPINNER_SIZE = 24.dp
private val MAP_REFRESH_SPINNER_STROKE = 2.dp

@Composable
private fun MapRefreshAction(state: MapScreenState) {
    Box(contentAlignment = Alignment.Center) {
        AppIconButton(
            imageVector = AppIcons.Refresh,
            contentDescription = stringResource(Res.string.map_controls_refresh),
            onClick = {
                state.userInitiatedContactReload = true
                state.contactReloadKey++
            },
            enabled = !state.contactsLoadChrome,
            modifier = Modifier.alpha(if (state.contactsLoadChrome) 0f else 1f),
        )
        if (state.contactsLoadChrome) {
            CircularProgressIndicator(
                modifier = Modifier.size(MAP_REFRESH_SPINNER_SIZE),
                strokeWidth = MAP_REFRESH_SPINNER_STROKE,
            )
        }
    }
}

@Composable
internal fun MapScreenScaffold(
    state: MapScreenState,
    scope: CoroutineScope,
    modifier: Modifier,
    inputs: MapScreenInputs,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppRootTopBar(
                title = stringResource(Res.string.localizable_tabs_map),
                actions = { MapRefreshAction(state) },
            )
        },
    ) { padding ->
        MapCanvas(
            state = state,
            scope = scope,
            contentPadding = padding,
            inputs = inputs,
        )
    }
}
