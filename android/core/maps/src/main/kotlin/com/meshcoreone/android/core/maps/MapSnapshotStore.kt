// PortedFrom: MC1/Services/MapSnapshotStore.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Map/MapSnapshotRequest.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.maps

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs
import kotlin.math.floor

data class MapSnapshotRequest private constructor(
    val latitude: Double,
    val longitude: Double,
    val isDark: Boolean,
    val isOffline: Boolean,
) {
    val coordinate = GeoPoint(latitude, longitude)
    val cacheKey: String = "$latitude,$longitude,$isDark,$isOffline"

    companion object {
        operator fun invoke(
            latitude: Double,
            longitude: Double,
            isDark: Boolean,
            isOffline: Boolean,
        ): MapSnapshotRequest {
            fun normalized(value: Double): Double {
                // Swift's default rounded() breaks exact ties away from zero, not to even.
                val scaled = abs(value * 100_000.0)
                val integral = floor(scaled)
                val magnitude = integral + if (scaled - integral >= 0.5) 1.0 else 0.0
                val rounded = (if (value < 0.0) -magnitude else magnitude) / 100_000.0
                return if (rounded == 0.0) 0.0 else rounded
            }
            return MapSnapshotRequest(
                latitude = normalized(latitude),
                longitude = normalized(longitude),
                isDark = isDark && !isOffline,
                isOffline = isOffline,
            )
        }
    }
}

interface MapSnapshotRenderer<T : Any> {
    suspend fun render(request: MapSnapshotRequest): T?
}

class MapSnapshotStore<T : Any>(
    private val renderer: MapSnapshotRenderer<T>,
    private val scope: CoroutineScope,
    private val costOf: (T) -> Long,
    private val cacheCountLimit: Int = 50,
    private val cacheCostLimitBytes: Long = 50L * 1024L * 1024L,
    private val failedSetSizeLimit: Int = 200,
) : AutoCloseable {
    private data class Entry<T>(val value: T, val cost: Long)

    private val lock = Any()
    private val semaphore = Semaphore(1)
    private val entries = linkedMapOf<MapSnapshotRequest, Entry<T>>()
    private val inFlight = mutableMapOf<MapSnapshotRequest, Long>()
    private val failed = linkedSetOf<MapSnapshotRequest>()
    private val jobs = mutableSetOf<Job>()
    private var totalCost = 0L
    private var generation = 0L
    private var closed = false

    private val _resolutions = MutableSharedFlow<MapSnapshotRequest>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val resolutions: SharedFlow<MapSnapshotRequest> = _resolutions.asSharedFlow()

    fun value(request: MapSnapshotRequest): T? = synchronized(lock) { entries[request]?.value }

    fun isResolved(request: MapSnapshotRequest): Boolean =
        synchronized(lock) { request in entries || request in failed }

    fun request(request: MapSnapshotRequest) {
        val generationAtStart = synchronized(lock) {
            check(!closed) { "Map snapshot store is closed" }
            if (request in entries || request in failed || request in inFlight) return
            inFlight[request] = generation
            generation
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                semaphore.withPermit {
                    val rendered = renderer.render(request)
                    synchronized(lock) {
                        if (generation == generationAtStart) {
                            if (rendered == null) insertFailure(request) else insertValue(request, rendered)
                            _resolutions.tryEmit(request)
                        }
                    }
                }
            } finally {
                synchronized(lock) {
                    if (inFlight[request] == generationAtStart) inFlight.remove(request)
                }
            }
        }
        synchronized(lock) {
            jobs += job
            job.invokeOnCompletion { synchronized(lock) { jobs.remove(job) } }
            if (closed) job.cancel()
        }
        job.start()
    }

    fun retry(request: MapSnapshotRequest) {
        synchronized(lock) {
            if (!failed.remove(request)) return
            _resolutions.tryEmit(request)
        }
    }

    fun clearFailures() {
        synchronized(lock) {
            val invalidated = failed.toList()
            failed.clear()
            invalidated.forEach(_resolutions::tryEmit)
        }
    }

    fun clear() {
        synchronized(lock) {
            if (closed) return
            generation += 1
            val invalidated = (entries.keys + failed + inFlight.keys).distinct()
            entries.clear()
            failed.clear()
            inFlight.clear()
            totalCost = 0
            invalidated.forEach(_resolutions::tryEmit)
        }
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            generation += 1
            jobs.toList().also {
                jobs.clear()
                inFlight.clear()
                entries.clear()
                failed.clear()
                totalCost = 0
            }
        }
        active.forEach { it.cancel() }
    }

    private fun insertFailure(request: MapSnapshotRequest) {
        failed += request
        while (failed.size > failedSetSizeLimit) {
            val evicted = failed.first()
            failed.remove(evicted)
            _resolutions.tryEmit(evicted)
        }
    }

    private fun insertValue(request: MapSnapshotRequest, value: T) {
        val cost = costOf(value).coerceAtLeast(0L)
        entries.remove(request)?.let { totalCost -= it.cost }
        entries[request] = Entry(value, cost)
        totalCost += cost
        while (entries.size > 1 && (entries.size > cacheCountLimit || totalCost > cacheCostLimitBytes)) {
            val evicted = entries.entries.first()
            entries.remove(evicted.key)
            totalCost -= evicted.value.cost
            _resolutions.tryEmit(evicted.key)
        }
    }
}
