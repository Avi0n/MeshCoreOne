package one.meshcore.shared.map

import androidx.compose.runtime.Composable
import one.meshcore.shared.model.Uuid
import kotlin.math.roundToInt

/**
 * Cache key for a Path Details thumbnail. Width is bucketed so a one-point layout change
 * does not render again. Dark and offline are separate axes, matching iOS
 * `MessagePathPreviewSnapshot.Key`.
 */
data class PathPreviewSnapshotKey(
    val arrivalId: String,
    val isDark: Boolean,
    val isOffline: Boolean,
    val widthBucket: Int,
)

/** Geometry and pixel size for one path thumbnail. Not the chat-card [MapSnapshotRequest]. */
data class PathPreviewSnapshotRequest(
    val points: List<MapPoint>,
    val lines: List<MapLine>,
    val region: MapCameraRegion?,
    val widthPx: Int,
    val heightPx: Int,
    val isDark: Boolean,
    val isOffline: Boolean,
)

fun interface PathPreviewSnapshotRendering {
    suspend fun render(request: PathPreviewSnapshotRequest): ByteArray?
}

object PathPreviewSnapshot {
    const val WIDTH_BUCKET_DP = 8
    const val PREVIEW_HEIGHT_DP = 160

    /** Nearest [WIDTH_BUCKET_DP] dp, matching iOS `bucketedWidth`. Zero when the width is not usable. */
    fun bucketedWidthDp(widthDp: Float): Int {
        if (!widthDp.isFinite() || widthDp <= 0f) return 0
        return (widthDp / WIDTH_BUCKET_DP).roundToInt() * WIDTH_BUCKET_DP
    }

    fun key(arrivalId: Uuid, isDark: Boolean, isOffline: Boolean, widthDp: Float): PathPreviewSnapshotKey =
        PathPreviewSnapshotKey(
            arrivalId = arrivalId.value,
            isDark = isDark,
            isOffline = isOffline,
            widthBucket = bucketedWidthDp(widthDp),
        )
}

/** Android renders through MapLibre; desktop has no snapshotter and returns null. */
@Composable
expect fun rememberPathPreviewSnapshotRenderer(): PathPreviewSnapshotRendering?
