package one.meshcore.shared.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.MapPreviewFragmentState
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.map.MapSnapshotLayout
import one.meshcore.shared.map.MapSnapshotRequest
import one.meshcore.shared.map.formatFixedFraction
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_action_open_map_link
import one.meshcore.shared.resources.map_preview_accessibility_hint
import one.meshcore.shared.resources.map_preview_accessibility_label
import one.meshcore.shared.resources.map_preview_retry_button_accessibility_label
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

private const val PREVIEW_ASPECT = 16f / 9f
private const val PREVIEW_COORDINATE_DIGITS = 5
private const val RETRY_PAD_DP = 8
internal const val MAP_PREVIEW_RETRY_BUTTON_DP = 40
private const val RETRY_GLYPH_DP = 20

internal val MAP_PREVIEW_RETRY_ICON get() = AppIcons.Refresh

/** Resolves a snapshot request key to a decoded thumbnail, or null while loading / after failure. */
typealias MapSnapshotImageResolver = (MapSnapshotRequest) -> ImageBitmap?

/**
 * Chat map-location thumbnail. Shows a cached MapLibre snapshot when available; otherwise a loading
 * skeleton (unresolved) or high-contrast coordinate fallback (failed / no image bytes) that opens the
 * map tab on tap.
 */
@Composable
fun MapPreviewFragment(
    state: MapPreviewFragmentState,
    onOpenMap: (LinkTarget.Coordinate) -> Unit,
    modifier: Modifier = Modifier,
    snapshotResolver: MapSnapshotImageResolver = { null },
    onRequestSnapshot: (MapSnapshotRequest) -> Unit = {},
    onRetry: (MapSnapshotRequest) -> Unit = {},
) {
    val a11yLabel = stringResource(Res.string.map_preview_accessibility_label)
    val a11yHint = stringResource(Res.string.map_preview_accessibility_hint)
    val retryA11y = stringResource(Res.string.map_preview_retry_button_accessibility_label)
    val openLabel = stringResource(Res.string.chats_message_action_open_map_link)
    val coordinateLabel = formatPreviewCoordinate(state.latitude, state.longitude)
    val request = MapSnapshotRequest.rounded(
        latitude = state.latitude,
        longitude = state.longitude,
        isDark = state.isDark,
        isOffline = state.isOffline,
    )
    val image = snapshotResolver(request)
    val showingFallback = state.isReady && image == null

    LaunchedEffect(request, state.isReady) {
        if (image == null) {
            onRequestSnapshot(request)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(PREVIEW_ASPECT)
            .clip(RoundedCornerShape(MapSnapshotLayout.CORNER_RADIUS_DP.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable {
                onOpenMap(LinkTarget.Coordinate(state.latitude, state.longitude))
            }
            .semantics {
                contentDescription = "$a11yLabel. $coordinateLabel. $a11yHint"
            },
    ) {
        when {
            image != null -> {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            state.isReady -> {
                FallbackContent(
                    coordinateLabel = coordinateLabel,
                    openLabel = openLabel,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
        }
        if (showingFallback) {
            FilledTonalIconButton(
                onClick = { onRetry(request) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(RETRY_PAD_DP.dp)
                    .size(MAP_PREVIEW_RETRY_BUTTON_DP.dp)
                    .semantics { contentDescription = retryA11y },
            ) {
                Icon(
                    imageVector = MAP_PREVIEW_RETRY_ICON,
                    contentDescription = null,
                    modifier = Modifier.size(RETRY_GLYPH_DP.dp),
                )
            }
        }
    }
}

@Composable
private fun FallbackContent(coordinateLabel: String, openLabel: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(12.dp),
    ) {
        Text(
            text = coordinateLabel,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = openLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun formatPreviewCoordinate(latitude: Double, longitude: Double): String =
    "${formatFixedFraction(latitude, PREVIEW_COORDINATE_DIGITS)}, " +
        formatFixedFraction(longitude, PREVIEW_COORDINATE_DIGITS)
