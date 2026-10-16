package one.meshcore.shared.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import one.meshcore.shared.runExceptionFallback

/**
 * Process-lifetime cache + bounded render queue for chat map thumbnails. Mirrors iOS
 * `MapSnapshotStore`: ready/failed/in-flight sets, FIFO image eviction, and a resolution stream so
 * thread rebuilds flip [one.meshcore.shared.chat.MessageBuildInputs.isMapPreviewReady].
 *
 * When [renderer] and [scope] are both set, [request] enqueues async GL work. Without a renderer
 * (desktop unit tests, pre-wire shells) [request] marks ready immediately so cards never stay on a
 * permanent skeleton.
 */
class MapSnapshotStore(private val renderer: MapSnapshotRendering? = null, private val scope: CoroutineScope? = null) {
    private val imageBytes = linkedMapOf<MapSnapshotRequest, ByteArray>()
    private var totalImageCostBytes: Int = 0
    private val failed = linkedSetOf<MapSnapshotRequest>()
    private val inFlight = linkedSetOf<MapSnapshotRequest>()
    private val _resolutions = MutableSharedFlow<MapSnapshotRequest>(extraBufferCapacity = RESOLUTION_BUFFER)
    val resolutions: SharedFlow<MapSnapshotRequest> = _resolutions.asSharedFlow()
    private val renderSemaphore = Semaphore(MAX_CONCURRENT)
    private var clearGeneration: Long = 0L
    private val snapshotRender = SnapshotRender()

    fun isResolved(request: MapSnapshotRequest): Boolean = imageBytes.containsKey(request) ||
        failed.contains(request) ||
        // Immediate-ready path (no renderer) only tracks ready keys without bytes.
        readyWithoutImage.contains(request)

    fun isReady(request: MapSnapshotRequest): Boolean =
        imageBytes.containsKey(request) || readyWithoutImage.contains(request)

    fun isFailed(request: MapSnapshotRequest): Boolean = failed.contains(request)

    fun imageBytes(request: MapSnapshotRequest): ByteArray? = imageBytes[request]

    /**
     * Enqueue a render. Dedupes against ready, failed, and in-flight. Without a platform renderer the
     * request is marked ready immediately (coordinate-card fallback).
     */
    fun request(request: MapSnapshotRequest) {
        if (isResolved(request) || inFlight.contains(request)) return
        inFlight.add(request)
        val activeRenderer = renderer
        val activeScope = scope
        if (activeRenderer == null || activeScope == null) {
            markReady(request)
            return
        }
        activeScope.launch {
            snapshotRender.perform(request, activeRenderer)
        }
    }

    fun markReady(request: MapSnapshotRequest, bytes: ByteArray? = null) {
        inFlight.remove(request)
        failed.remove(request)
        if (bytes != null) {
            snapshotRender.cache(request, bytes)
            readyWithoutImage.remove(request)
        } else {
            imageBytes.remove(request)?.let { totalImageCostBytes -= it.size }
            if (readyWithoutImage.size >= CACHE_LIMIT) {
                val oldest = readyWithoutImage.firstOrNull()
                if (oldest != null) readyWithoutImage.remove(oldest)
            }
            readyWithoutImage.add(request)
        }
        _resolutions.tryEmit(request)
    }

    fun markFailed(request: MapSnapshotRequest) {
        inFlight.remove(request)
        readyWithoutImage.remove(request)
        imageBytes.remove(request)?.let { totalImageCostBytes -= it.size }
        if (failed.size >= FAILED_LIMIT) {
            val oldest = failed.firstOrNull()
            if (oldest != null) failed.remove(oldest)
        }
        failed.add(request)
        _resolutions.tryEmit(request)
    }

    /**
     * Drops a single failed key so the next [request] re-attempts. Wired to the retry control on the
     * chat thumbnail fallback.
     */
    fun retry(request: MapSnapshotRequest) {
        if (!failed.remove(request)) return
        _resolutions.tryEmit(request)
    }

    fun clearFailures() {
        val cleared = failed.toList()
        failed.clear()
        for (request in cleared) {
            _resolutions.tryEmit(request)
        }
    }

    fun clear() {
        clearGeneration += 1
        val toInvalidate = buildSet {
            addAll(imageBytes.keys)
            addAll(failed)
            addAll(readyWithoutImage)
        }
        imageBytes.clear()
        totalImageCostBytes = 0
        failed.clear()
        readyWithoutImage.clear()
        inFlight.clear()
        for (request in toInvalidate) {
            _resolutions.tryEmit(request)
        }
    }

    private val readyWithoutImage = linkedSetOf<MapSnapshotRequest>()

    private inner class SnapshotRender {
        suspend fun perform(request: MapSnapshotRequest, activeRenderer: MapSnapshotRendering) {
            val generationAtStart = clearGeneration
            try {
                renderSemaphore.withPermit {
                    val bytes = runExceptionFallback({ null }) {
                        activeRenderer.render(request)
                    }
                    if (clearGeneration != generationAtStart) return
                    if (bytes != null) {
                        markReady(request, bytes)
                    } else {
                        markFailed(request)
                    }
                }
            } finally {
                inFlight.remove(request)
            }
        }

        fun cache(request: MapSnapshotRequest, bytes: ByteArray) {
            imageBytes.remove(request)?.let { totalImageCostBytes -= it.size }
            imageBytes[request] = bytes
            totalImageCostBytes += bytes.size
            while (imageBytes.size > 1 &&
                (imageBytes.size > CACHE_LIMIT || totalImageCostBytes > CACHE_COST_LIMIT_BYTES)
            ) {
                val evicted = imageBytes.entries.firstOrNull() ?: break
                imageBytes.remove(evicted.key)
                totalImageCostBytes -= evicted.value.size
                _resolutions.tryEmit(evicted.key)
            }
        }
    }

    private companion object {
        const val CACHE_LIMIT = 50
        const val CACHE_COST_LIMIT_BYTES = 50 * 1024 * 1024
        const val FAILED_LIMIT = 200
        const val RESOLUTION_BUFFER = 64
        const val MAX_CONCURRENT = 1
    }
}

/**
 * True on the offline-to-online edge. Callers must then [MapSnapshotStore.clearFailures] so a
 * thumbnail that failed while offline can be requested again (iOS `ChatTimeline.applyEnvInputs`).
 */
fun shouldClearMapSnapshotFailures(wasOffline: Boolean, isOffline: Boolean): Boolean = wasOffline && !isOffline
