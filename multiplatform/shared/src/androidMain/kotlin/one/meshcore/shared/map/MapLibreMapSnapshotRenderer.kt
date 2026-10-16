package one.meshcore.shared.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import one.meshcore.shared.map.ui.MapPinSprites
import one.meshcore.shared.map.ui.pathPointBitmap
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * MapLibre [MapSnapshotter] chat thumbnails with a dropped-pin composite. Must run on the main
 * looper (Snapshotter requirement); [render] hops to [Dispatchers.Main] for that.
 */
class MapLibreMapSnapshotRenderer(context: Context) : MapSnapshotRendering {
    private val appContext = context.applicationContext

    override suspend fun render(request: MapSnapshotRequest): ByteArray? = withContext(Dispatchers.Main.immediate) {
        MapLibre.getInstance(appContext)
        val styleUrl = MapStyleSelection.STANDARD.styleUrl(
            isDarkMode = request.isDark,
            isOffline = request.isOffline,
        )
        val camera = CameraPosition.Builder()
            .target(LatLng(request.latitude, request.longitude))
            .zoom(MapSnapshotLayout.ZOOM_LEVEL)
            .build()
        val options = MapSnapshotter.Options(
            MapSnapshotLayout.WIDTH_PX,
            MapSnapshotLayout.HEIGHT_PX,
        )
            .withPixelRatio(MapSnapshotLayout.PIXEL_RATIO)
            .withStyleBuilder(Style.Builder().fromUri(styleUrl))
            .withCameraPosition(camera)
            .withLogo(false)

        val snapshot = takeSnapshot(options) ?: return@withContext null
        val withPin = compositePin(
            snapshot = snapshot,
            latitude = request.latitude,
            longitude = request.longitude,
        )
        encodePng(withPin)
    }

    /**
     * Path Details thumbnail: base tiles framed to the route, then a solid path stroke and
     * bottom-anchored pins. Logo stays off; a failed snapshot returns null so the sheet can retry.
     */
    suspend fun renderPath(request: PathPreviewSnapshotRequest): ByteArray? = withContext(Dispatchers.Main.immediate) {
        if (request.widthPx < 1 || request.heightPx < 1) return@withContext null
        val pins = request.points.filter { it.pinStyle != PinStyle.BADGE }
        val first = pins.firstOrNull()
        if (first == null) return@withContext null
        MapLibre.getInstance(appContext)
        val styleUrl = MapStyleSelection.STANDARD.styleUrl(
            isDarkMode = request.isDark,
            isOffline = request.isOffline,
        )
        val base = MapSnapshotter.Options(request.widthPx, request.heightPx)
            .withPixelRatio(PATH_SNAPSHOT_PIXEL_RATIO)
            .withStyleBuilder(Style.Builder().fromUri(styleUrl))
            .withLogo(false)
        val bounds = if (pins.size > 1) request.region?.validatedBounds() else null
        val options = if (bounds != null) {
            base.withRegion(
                LatLngBounds.from(
                    bounds.ne.latitude,
                    bounds.ne.longitude,
                    bounds.sw.latitude,
                    bounds.sw.longitude,
                ),
            )
        } else {
            base.withCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(first.coordinate.latitude, first.coordinate.longitude))
                    .zoom(MapSnapshotLayout.ZOOM_LEVEL)
                    .build(),
            )
        }
        val snapshot = takeSnapshot(options)
        if (snapshot == null) return@withContext null
        val density = appContext.resources.displayMetrics.density
        val withPath = compositePath(snapshot, request, density)
        encodePng(withPath)
    }

    private suspend fun takeSnapshot(options: MapSnapshotter.Options): MapSnapshot? {
        val snapshotter = MapSnapshotter(appContext, options)
        return try {
            awaitSnapshot(snapshotter)
        } finally {
            snapshotter.cancel()
        }
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun awaitSnapshot(snapshotter: MapSnapshotter): MapSnapshot? =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                snapshotter.cancel()
            }
            snapshotter.start(
                { snapshot ->
                    if (continuation.isActive) {
                        continuation.resume(snapshot)
                    }
                },
                { _ ->
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                },
            )
        }

    private fun compositePin(snapshot: MapSnapshot, latitude: Double, longitude: Double): Bitmap {
        val source = snapshot.bitmap
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true) ?: source
        val canvas = Canvas(mutable)
        val point = snapshot.pixelForLatLng(LatLng(latitude, longitude))
        drawDroppedPin(canvas, point.x, point.y)
        return mutable
    }

    private fun drawDroppedPin(canvas: Canvas, x: Float, y: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PIN_COLOR
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = PIN_OUTLINE_WIDTH
        }
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x - PIN_HALF_WIDTH, y - PIN_HEIGHT + PIN_HEAD_RADIUS)
            quadTo(x - PIN_HALF_WIDTH, y - PIN_HEIGHT, x, y - PIN_HEIGHT)
            quadTo(x + PIN_HALF_WIDTH, y - PIN_HEIGHT, x + PIN_HALF_WIDTH, y - PIN_HEIGHT + PIN_HEAD_RADIUS)
            close()
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        val headCenterY = y - PIN_HEIGHT + PIN_HEAD_RADIUS * PIN_HEAD_CENTER_FRACTION
        canvas.drawCircle(x, headCenterY, PIN_HEAD_HOLE, hole)
    }

    private fun compositePath(snapshot: MapSnapshot, request: PathPreviewSnapshotRequest, density: Float): Bitmap {
        val source = snapshot.bitmap
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true) ?: source
        val canvas = Canvas(mutable)
        val sprites = MapPinSprites(density)
        val casing = pathStroke(Color.WHITE, PATH_CASING_WIDTH_DP * density, PATH_CASING_ALPHA)
        val line = pathStroke(PATH_LINE_COLOR, PATH_LINE_WIDTH_DP * density, OPAQUE_ALPHA)
        fun draw(paint: Paint) {
            for (segment in request.lines) {
                val path = segmentPath(snapshot, segment) ?: continue
                canvas.drawPath(path, paint)
            }
        }
        draw(casing)
        draw(line)
        for (point in request.points) {
            val bitmap = sprites.pathPointBitmap(point) ?: continue
            val pixel = snapshot.pixelForLatLng(LatLng(point.coordinate.latitude, point.coordinate.longitude))
            canvas.drawBitmap(bitmap, pixel.x - bitmap.width / 2f, pixel.y - bitmap.height, null)
        }
        return mutable
    }

    private fun segmentPath(snapshot: MapSnapshot, segment: MapLine): Path? {
        if (segment.coordinates.size < 2) return null
        val path = Path()
        segment.coordinates.forEachIndexed { index, coordinate ->
            val pixel = snapshot.pixelForLatLng(LatLng(coordinate.latitude, coordinate.longitude))
            if (index == 0) path.moveTo(pixel.x, pixel.y) else path.lineTo(pixel.x, pixel.y)
        }
        return path
    }

    private fun pathStroke(color: Int, width: Float, alpha: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.alpha = alpha
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private fun encodePng(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream(bitmap.byteCount.coerceAtLeast(MIN_PNG_BUFFER))
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, stream)
        return stream.toByteArray()
    }

    private companion object {
        const val PIN_COLOR = 0xFFE53935.toInt()
        const val PIN_HALF_WIDTH = 14f
        const val PIN_HEIGHT = 36f
        const val PIN_HEAD_RADIUS = 12f
        const val PIN_HEAD_HOLE = 4.5f
        const val PIN_HEAD_CENTER_FRACTION = 0.35f
        const val PIN_OUTLINE_WIDTH = 2f
        const val PNG_QUALITY = 100
        const val MIN_PNG_BUFFER = 8 * 1024
        const val PATH_SNAPSHOT_PIXEL_RATIO = 1f
        const val PATH_CASING_WIDTH_DP = 6f
        const val PATH_LINE_WIDTH_DP = 3f
        const val PATH_CASING_ALPHA = 204
        const val OPAQUE_ALPHA = 255
        const val PATH_LINE_COLOR = 0xFF007AFF.toInt()
    }
}
