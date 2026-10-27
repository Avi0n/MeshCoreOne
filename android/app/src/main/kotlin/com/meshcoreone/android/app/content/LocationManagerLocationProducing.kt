// PortedFrom: MC1/Services/LocationService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.content

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import com.meshcoreone.android.core.services.content.GeoCoordinate
import com.meshcoreone.android.core.services.content.LocationAuthorizationStatus
import com.meshcoreone.android.core.services.content.LocationProducing
import com.meshcoreone.android.core.services.content.LocationServiceError
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

interface LocationPermissionResultReporting {
    fun reportPermissionResult(granted: Boolean)
}

fun interface LocationPermissionRequesting {
    fun requestPermission()
}

object UnavailableLocationPermissionRequester : LocationPermissionRequesting {
    override fun requestPermission(): Nothing = throw LocationServiceError.PermissionRequestUnavailable
}

class LocationManagerLocationProducing(
    private val context: Context,
    private val permissionRequester: LocationPermissionRequesting = UnavailableLocationPermissionRequester,
) : LocationProducing, LocationPermissionResultReporting, AutoCloseable {
    private val lock = Any()
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private var determined = false
    private var closed = false
    private var active: Request? = null
    private var lastCoordinate: GeoCoordinate? = null
    private val mutableStatus = MutableStateFlow(readStatus())
    override val authorizationStatus: StateFlow<LocationAuthorizationStatus> = mutableStatus.asStateFlow()
    val currentLocation: GeoCoordinate? get() = synchronized(lock) { lastCoordinate }

    private class Request(
        val continuation: CancellableContinuation<GeoCoordinate>,
        val signal: CancellationSignal = CancellationSignal(),
    )

    init {
        if (isAuthorized(mutableStatus.value)) determined = true
    }

    private fun readStatus(): LocationAuthorizationStatus {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return when {
            (fine || coarse) && background -> LocationAuthorizationStatus.AUTHORIZED_ALWAYS
            fine || coarse -> LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE
            determined -> LocationAuthorizationStatus.DENIED
            else -> LocationAuthorizationStatus.NOT_DETERMINED
        }
    }

    private fun isAuthorized(status: LocationAuthorizationStatus): Boolean =
        status == LocationAuthorizationStatus.AUTHORIZED_ALWAYS ||
            status == LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE

    fun refreshAuthorization() {
        val pending = synchronized(lock) {
            if (closed) return
            val status = readStatus()
            if (isAuthorized(status)) determined = true
            mutableStatus.value = status
            active.takeIf { !isAuthorized(status) }
        }
        if (pending != null) complete(
            pending,
            Result.failure(LocationServiceError.NotAuthorized(mutableStatus.value)),
        )
    }

    override fun requestPermissionIfNeeded() {
        refreshAuthorization()
        synchronized(lock) {
            if (closed) throw LocationServiceError.RequestFailed("Location producer is closed")
        }
        if (mutableStatus.value == LocationAuthorizationStatus.NOT_DETERMINED) {
            permissionRequester.requestPermission()
        }
    }

    override fun reportPermissionResult(granted: Boolean) {
        synchronized(lock) { determined = true }
        refreshAuthorization()
    }

    // Permission is checked immediately below and a revoke-after-check SecurityException is
    // handled. Lint does not propagate the OR guard through this coroutine callback bridge.
    @SuppressLint("MissingPermission")
    override suspend fun requestLocation(): GeoCoordinate {
        refreshAuthorization()
        val manager = locationManager
            ?: throw LocationServiceError.RequestFailed("LocationManager system service unavailable")
        return suspendCancellableCoroutine { continuation ->
            val request = Request(continuation)
            val failure = synchronized(lock) {
                when {
                    closed -> LocationServiceError.RequestFailed("Location producer is closed")
                    active != null -> LocationServiceError.RequestInProgress
                    !isAuthorized(mutableStatus.value) -> LocationServiceError.NotAuthorized(mutableStatus.value)
                    else -> {
                        active = request
                        null
                    }
                }
            }
            if (failure != null) {
                continuation.resumeWith(Result.failure(failure))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { cancel(request) }
            if (!owns(request)) return@suspendCancellableCoroutine
            try {
                if (
                    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                ) {
                    val provider = listOf(
                        LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
                    ).firstOrNull { manager.hasProvider(it) && manager.isProviderEnabled(it) }
                    if (provider == null) {
                        complete(request, Result.failure(LocationServiceError.RequestFailed("Location provider disabled")))
                        return@suspendCancellableCoroutine
                    }
                    manager.getCurrentLocation(
                        provider, request.signal, Executor { it.run() },
                    ) { location: Location? ->
                        refreshAuthorization()
                        if (!owns(request)) return@getCurrentLocation
                        val result = if (location == null) {
                            Result.failure(LocationServiceError.RequestFailed("No location fix available"))
                        } else {
                            Result.success(GeoCoordinate(location.latitude, location.longitude))
                        }
                        complete(request, result)
                    }
                } else {
                    refreshAuthorization()
                    complete(request, Result.failure(LocationServiceError.NotAuthorized(mutableStatus.value)))
                }
            } catch (_: SecurityException) {
                synchronized(lock) { determined = true }
                refreshAuthorization()
                complete(request, Result.failure(LocationServiceError.NotAuthorized(mutableStatus.value)))
            } catch (error: IllegalArgumentException) {
                complete(request, Result.failure(LocationServiceError.RequestFailed(error.message ?: "Invalid location provider")))
            }
        }
    }

    private fun owns(request: Request): Boolean = synchronized(lock) { active === request }

    private fun complete(request: Request, result: Result<GeoCoordinate>) {
        val claimed = synchronized(lock) {
            if (active !== request) false else {
                active = null
                if (!closed && request.continuation.isActive) {
                    result.getOrNull()?.let { lastCoordinate = it }
                }
                true
            }
        }
        if (claimed) {
            request.signal.cancel()
            request.continuation.resumeWith(result)
        }
    }

    private fun cancel(request: Request) {
        val claimed = synchronized(lock) {
            if (active !== request) false else {
                active = null
                true
            }
        }
        if (claimed) {
            request.signal.cancel()
            request.continuation.cancel(CancellationException("Location request cancelled"))
        }
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            active
        }
        pending?.let(::cancel)
    }
}
