package one.meshcore.shared.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberPathPreviewSnapshotRenderer(): PathPreviewSnapshotRendering? {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        val renderer = MapLibreMapSnapshotRenderer(context)
        PathPreviewSnapshotRendering { request -> renderer.renderPath(request) }
    }
}
