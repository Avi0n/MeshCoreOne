// AndroidOnly: WP-312 MapLibre Native snapshot adapter with provider attribution enabled.
package com.meshcoreone.android.core.maps

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.snapshotter.MapSnapshotter

class MapLibreSnapshotRenderer(
    context: Context,
    private val widthPixels: Int,
    private val heightPixels: Int,
) : MapSnapshotRenderer<Bitmap> {
    private val appContext = context.applicationContext

    init {
        require(widthPixels > 0 && heightPixels > 0)
    }

    override suspend fun render(request: MapSnapshotRequest): Bitmap? = withContext(Dispatchers.Main.immediate) {
        if (!MapLibreRuntime.isSupported || request.isOffline) return@withContext null
        MapLibre.getInstance(appContext)
        suspendCancellableCoroutine { continuation ->
            val options = MapSnapshotter.Options(widthPixels, heightPixels)
                .withStyle(MapLibreOpenFreeMap.STYLE_URI)
                .withCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(request.latitude, request.longitude))
                        .zoom(14.0)
                        .build(),
                )
                .withLogo(false)
                .withAttribution(true)
            val snapshotter = MapSnapshotter(appContext, options)
            val finished = AtomicBoolean(false)
            continuation.invokeOnCancellation {
                finished.set(true)
                if (Looper.myLooper() == Looper.getMainLooper()) snapshotter.cancel()
                else Handler(Looper.getMainLooper()).post { snapshotter.cancel() }
            }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            snapshotter.start(
                { snapshot -> if (finished.compareAndSet(false, true)) continuation.resume(snapshot.bitmap) },
                { reason ->
                    Logger.getLogger("com.meshcoreone.android.MapSnapshot").warning("MapLibre snapshot failed: $reason")
                    if (finished.compareAndSet(false, true)) continuation.resume(null)
                },
            )
        }
    }
}
