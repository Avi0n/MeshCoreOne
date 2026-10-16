package one.meshcore.shared.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.pathdetail.MessagePathDisplay
import one.meshcore.shared.chat.pathdetail.MessagePathMapBuilder
import one.meshcore.shared.map.PathPreviewSnapshot
import one.meshcore.shared.map.PathPreviewSnapshotKey
import one.meshcore.shared.map.PathPreviewSnapshotRendering
import one.meshcore.shared.map.PathPreviewSnapshotRequest
import one.meshcore.shared.map.SnrBadgeFormatter
import one.meshcore.shared.map.decodeMapSnapshotImage
import one.meshcore.shared.map.rememberPathPreviewSnapshotRenderer
import one.meshcore.shared.map.resolveMapIsDark
import one.meshcore.shared.map.ui.LocalMapColorSchemePreference
import one.meshcore.shared.map.ui.PathDistanceBanner
import one.meshcore.shared.map.ui.rememberSnrBadgeFormatter
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_path_accessibility_view_on_map
import one.meshcore.shared.resources.chats_path_unavailable_title
import one.meshcore.shared.resources.map_preview_retry_button_accessibility_label
import one.meshcore.shared.theme.LocalUseDarkTheme
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/** Static path thumbnail in the message actions sheet. The expand chip opens the live map. */
@Composable
internal fun MessagePathCompactPreview(
    presentation: MessagePathPresentation,
    selectedArrivalID: Uuid?,
    isOffline: Boolean,
    onExpand: () -> Unit,
) {
    val selectedID = selectedArrivalID ?: presentation.arrivals.firstOrNull()?.arrival?.id
    val display = presentation.displayFor(selectedID)
    val formatter = rememberSnrBadgeFormatter()
    val plot = remember(display, formatter) { MessagePathMapBuilder.build(display, formatter) }
    if (!MessagePathMapBuilder.showsPathMap(plot, display.hopCount)) return
    PathPreviewSnapshotTile(
        presentation = presentation,
        selectedID = selectedID,
        display = display,
        plot = plot,
        formatter = formatter,
        isOffline = isOffline,
        onExpand = onExpand,
    )
}

@Composable
private fun PathPreviewSnapshotTile(
    presentation: MessagePathPresentation,
    selectedID: Uuid?,
    display: MessagePathDisplay,
    plot: MessagePathMapBuilder.Plot,
    formatter: SnrBadgeFormatter,
    isOffline: Boolean,
    onExpand: () -> Unit,
) {
    val isDark = resolveMapIsDark(LocalMapColorSchemePreference.current, LocalUseDarkTheme.current)
    val renderer = rememberPathPreviewSnapshotRenderer()
    val cache = remember { PathPreviewTileCache() }
    var lastImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var retryGeneration by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(PathPreviewSnapshot.PREVIEW_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        val widthBucket = PathPreviewSnapshot.bucketedWidthDp(maxWidth.value)
        val selectedKey = selectedID?.let { id ->
            PathPreviewSnapshot.key(id, isDark = isDark, isOffline = isOffline, widthDp = widthBucket.toFloat())
        }
        LaunchedEffect(presentation, selectedID, isDark, isOffline, widthBucket, retryGeneration, renderer) {
            prefetchPathPreviewImages(
                renderer = renderer,
                presentation = presentation,
                selectedID = selectedID,
                frame = PathPreviewFrame(
                    isDark = isDark,
                    isOffline = isOffline,
                    widthBucket = widthBucket,
                    widthPx = with(density) { widthBucket.dp.roundToPx() },
                    heightPx = with(density) { PathPreviewSnapshot.PREVIEW_HEIGHT_DP.dp.roundToPx() },
                    formatter = formatter,
                ),
                cache = cache,
            )?.let { lastImage = it }
        }
        val ready = selectedKey?.let { cache.images[it] }
        val failedKey = selectedKey?.takeIf { ready == null && it in cache.failed }
        val shown = pathPreviewShownBitmap(ready = ready, selectionFailed = failedKey != null, previous = lastImage)
        if (renderer == null && shown == null) {
            Text(
                text = stringResource(Res.string.chats_path_unavailable_title),
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (failedKey != null) {
            val retryLabel = stringResource(Res.string.map_preview_retry_button_accessibility_label)
            IconButton(
                onClick = {
                    cache.failed.remove(failedKey)
                    retryGeneration += 1
                },
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Refresh,
                    contentDescription = retryLabel,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            IconButton(onClick = onExpand) {
                Icon(
                    imageVector = AppIcons.Map,
                    contentDescription = stringResource(Res.string.chats_path_accessibility_view_on_map),
                )
            }
        }
        if (plot.totalPathDistanceMeters != null || plot.isDistanceIncomplete) {
            PathDistanceBanner(
                hopCount = display.hopCount,
                totalPathDistanceMeters = plot.totalPathDistanceMeters,
                isDistanceIncomplete = plot.isDistanceIncomplete,
                showsHopCount = false,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Keep the selected thumbnail; hide a failed selection instead of a stale route. */
internal fun pathPreviewShownBitmap(
    ready: ImageBitmap?,
    selectionFailed: Boolean,
    previous: ImageBitmap?,
): ImageBitmap? = when {
    ready != null -> ready
    selectionFailed -> null
    else -> previous
}

private class PathPreviewTileCache {
    val images = mutableStateMapOf<PathPreviewSnapshotKey, ImageBitmap>()
    val failed = mutableStateListOf<PathPreviewSnapshotKey>()
}

private data class PathPreviewFrame(
    val isDark: Boolean,
    val isOffline: Boolean,
    val widthBucket: Int,
    val widthPx: Int,
    val heightPx: Int,
    val formatter: SnrBadgeFormatter,
)

private suspend fun prefetchPathPreviewImages(
    renderer: PathPreviewSnapshotRendering?,
    presentation: MessagePathPresentation,
    selectedID: Uuid?,
    frame: PathPreviewFrame,
    cache: PathPreviewTileCache,
): ImageBitmap? {
    val rendering = renderer
    if (rendering == null || frame.widthBucket < 1) return null
    val sized = frame.copy(
        widthPx = frame.widthPx.coerceAtLeast(1),
        heightPx = frame.heightPx.coerceAtLeast(1),
    )
    var newest: ImageBitmap? = null
    for (arrival in pathPreviewPrefetchOrder(selectedID, presentation.arrivals)) {
        val bitmap = renderPathPreviewArrival(rendering, arrival, sized, cache) ?: continue
        newest = bitmap
    }
    return newest
}

private suspend fun renderPathPreviewArrival(
    rendering: PathPreviewSnapshotRendering,
    arrival: ArrivalPathDisplay,
    frame: PathPreviewFrame,
    cache: PathPreviewTileCache,
): ImageBitmap? {
    val key = PathPreviewSnapshot.key(
        arrival.arrival.id,
        isDark = frame.isDark,
        isOffline = frame.isOffline,
        widthDp = frame.widthBucket.toFloat(),
    )
    val arrivalPlot = if (cache.images.containsKey(key)) {
        null
    } else {
        MessagePathMapBuilder.build(arrival.display, frame.formatter)
    }
    val drawable = arrivalPlot != null &&
        MessagePathMapBuilder.showsPathMap(arrivalPlot, arrival.display.hopCount)
    val bitmap = if (drawable) {
        rendering.render(
            PathPreviewSnapshotRequest(
                points = arrivalPlot.points,
                lines = arrivalPlot.lines,
                region = arrivalPlot.region,
                widthPx = frame.widthPx,
                heightPx = frame.heightPx,
                isDark = frame.isDark,
                isOffline = frame.isOffline,
            ),
        )?.let { decodeMapSnapshotImage(it) }
    } else {
        null
    }
    if (bitmap != null) {
        cache.failed.remove(key)
        cache.images[key] = bitmap
    } else if (drawable && key !in cache.failed) {
        cache.failed.add(key)
    }
    return bitmap
}

/** Selected arrival first, then the rest, matching iOS prefetch order. */
internal fun pathPreviewPrefetchOrder(
    selectedID: Uuid?,
    arrivals: List<ArrivalPathDisplay>,
): List<ArrivalPathDisplay> {
    val selected = arrivals.firstOrNull { it.arrival.id == selectedID } ?: return arrivals
    return listOf(selected) + arrivals.filter { it.arrival.id != selected.arrival.id }
}
