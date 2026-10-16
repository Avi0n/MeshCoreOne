package one.meshcore.shared.tools.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_noise_floor_collecting_data
import one.meshcore.shared.resources.tools_noise_floor_collecting_data_description
import one.meshcore.shared.resources.tools_noise_floor_error_disconnected
import one.meshcore.shared.resources.tools_noise_floor_error_unable_to_read
import one.meshcore.shared.resources.tools_noise_floor_not_connected_description
import one.meshcore.shared.resources.tools_rx_log_not_connected
import one.meshcore.shared.tools.NoiseFloorPaneKind
import one.meshcore.shared.tools.NoiseFloorReading
import one.meshcore.shared.tools.NoiseFloorViewModel
import one.meshcore.shared.tools.noiseFloorPaneKind
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Tools → Noise Floor: polls [fetchReading] on an interval, shows current dBm/quality, min/avg/max,
 * last RSSI/SNR, trend, and a hand-rolled Canvas sparkline of the series. [sessionOwner] and [radioId]
 * keep samples from different radios separate; the process workspace retains a same-radio history.
 */
@Composable
fun NoiseFloorScreen(
    fetchReading: (suspend () -> NoiseFloorReading?)?,
    sessionOwner: Flow<MeshEvent>?,
    radioId: Uuid?,
    modifier: Modifier = Modifier,
    workspace: NoiseFloorViewModel? = null,
) {
    val scope = rememberCoroutineScope()
    val currentFetch by rememberUpdatedState(fetchReading)
    val currentOwner by rememberUpdatedState(sessionOwner)
    val currentRadio by rememberUpdatedState(radioId)
    val model = workspace ?: remember {
        NoiseFloorViewModel(
            parentScope = scope,
            dependencies = NoiseFloorViewModel.Dependencies(
                fetchReading = { currentFetch },
                sessionOwner = { currentOwner },
                radioId = { currentRadio },
            ),
        )
    }
    val unableToReadLabel = stringResource(Res.string.tools_noise_floor_error_unable_to_read)
    val disconnectedLabel = stringResource(Res.string.tools_noise_floor_error_disconnected)
    val messages = remember(disconnectedLabel, unableToReadLabel) {
        NoiseFloorViewModel.Messages(disconnectedLabel, unableToReadLabel)
    }
    LaunchedEffect(model, messages) { model.updateMessages(messages) }
    DisposableEffect(model, workspace) {
        onDispose { if (workspace == null) model.close() }
    }
    LifecycleResumeEffect(model, fetchReading, sessionOwner to radioId) {
        val mount = model.shown(messages)
        onPauseOrDispose { model.hidden(mount) }
    }
    val state by model.state.collectAsStateWithLifecycle()
    val readings = state.readings
    val errorMessage = state.errorMessage
    val current = readings.lastOrNull()

    when (
        noiseFloorPaneKind(
            hasFetcher = fetchReading != null,
            hasReading = current != null,
            errorMessage = errorMessage,
        )
    ) {
        NoiseFloorPaneKind.DISCONNECTED -> {
            NoiseFloorEmptyPane(
                icon = AppIcons.CellTowerOff,
                title = stringResource(Res.string.tools_rx_log_not_connected),
                description = stringResource(Res.string.tools_noise_floor_not_connected_description),
                modifier = modifier,
            )
        }
        NoiseFloorPaneKind.COLLECTING -> {
            NoiseFloorEmptyPane(
                icon = AppIcons.CellTower,
                title = stringResource(Res.string.tools_noise_floor_collecting_data),
                description = stringResource(Res.string.tools_noise_floor_collecting_data_description),
                modifier = modifier,
            )
        }
        NoiseFloorPaneKind.ERROR -> {
            NoiseFloorEmptyPane(
                icon = AppIcons.CellTower,
                title = stringResource(Res.string.tools_noise_floor_error_unable_to_read),
                description = errorMessage ?: stringResource(Res.string.tools_noise_floor_error_unable_to_read),
                modifier = modifier,
            )
        }
        NoiseFloorPaneKind.CONTENT -> NoiseFloorContent(
            readings = readings,
            chartStartEpochMs = requireNotNull(state.chartStartEpochMs),
            reading = requireNotNull(current),
            errorMessage = errorMessage,
            modifier = modifier,
        )
    }
}
