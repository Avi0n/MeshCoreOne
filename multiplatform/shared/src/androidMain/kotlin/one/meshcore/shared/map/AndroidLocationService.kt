package one.meshcore.shared.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.settings.PersistentLogger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Android [LocationService], backed by the framework [LocationManager] (no Play Services
 * dependency). Callers that need current grant state after the user toggles system Settings call
 * [refreshAuthorization].
 *
 * [requestSystemPermission] is the runtime prompt, which needs an Activity and so is supplied by the
 * host (the same launcher the onboarding permission step uses). Without it the service can only read
 * the grant, and a user who declined during onboarding has no way back.
 */
class AndroidLocationService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val requestSystemPermission: () -> Unit = {},
    /** True after this install has already shown the location prompt (onboarding or in-app). */
    private val hasAskedLocation: () -> Boolean = { false },
) : LocationService {
    private val _authorizationStatus = MutableStateFlow(readAuthorization())
    override val authorizationStatus: StateFlow<LocationAuthorizationStatus> = _authorizationStatus.asStateFlow()

    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    override val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    private val _isRequestingLocation = MutableStateFlow(false)
    override val isRequestingLocation: StateFlow<Boolean> = _isRequestingLocation.asStateFlow()

    private var hasPrompted = false

    /** Re-reads the grant into [authorizationStatus]; call before relying on [isAuthorized]. */
    override fun refreshAuthorization() {
        _authorizationStatus.value = readAuthorization()
    }

    /**
     * Prompts once per process while location is ungranted, mirroring the iOS guard on
     * `.notDetermined`. Android reports no "asked and denied" state without an Activity, so the
     * once-per-process latch stands in for it: the system silently drops a repeat prompt after a
     * permanent denial, and re-asking on every screen entry would be noise either way.
     */
    override fun requestPermissionIfNeeded() {
        refreshAuthorization()
        if (!shouldPromptForLocation(_authorizationStatus.value, hasPrompted)) return
        hasPrompted = true
        requestSystemPermission()
    }

    @Suppress("TooGenericExceptionCaught")
    override fun requestLocation() {
        scope.launch {
            try {
                requestCurrentLocation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PersistentLogger(scope, category = "LocationService")
                    .warning("Location request failed: ${error::class.simpleName}")
            }
        }
    }

    override suspend fun requestCurrentLocation(timeout: Duration): LatLng {
        refreshAuthorization()
        if (!_authorizationStatus.value.isAuthorized) {
            if (_authorizationStatus.value == LocationAuthorizationStatus.NOT_DETERMINED) {
                requestPermissionIfNeeded()
                val decided = withTimeoutOrNull(PERMISSION_DECISION_TIMEOUT) {
                    authorizationStatus.first { it != LocationAuthorizationStatus.NOT_DETERMINED }
                }
                if (decided == null || !decided.isAuthorized) {
                    refreshAuthorization()
                    throw LocationServiceError.NotAuthorized(_authorizationStatus.value)
                }
            } else {
                throw LocationServiceError.NotAuthorized(_authorizationStatus.value)
            }
        }
        _isRequestingLocation.value = true
        try {
            val fix = withTimeoutOrNull(timeout) { awaitSingleFix() }
                ?: throw LocationServiceError.LocationTimeout
            val latLng = LatLng(fix.latitude, fix.longitude)
            _currentLocation.value = latLng
            return latLng
        } finally {
            _isRequestingLocation.value = false
        }
    }

    // The grant is checked in requestCurrentLocation before this runs.
    @SuppressLint("MissingPermission")
    @Suppress("RedundantSuspendModifier")
    private suspend fun awaitSingleFix(): Location = suspendCancellableCoroutine { continuation ->
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val provider = manager?.let(::bestEnabledProvider)
        if (manager == null || provider == null) {
            continuation.resumeWithException(LocationServiceError.RequestFailed(NO_PROVIDER_REASON))
            return@suspendCancellableCoroutine
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                if (location != null) {
                    continuation.resume(location)
                } else {
                    continuation.resumeWithException(LocationServiceError.RequestFailed(NO_FIX_REASON))
                }
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }
            }
            manager.requestLocationUpdates(
                provider,
                SINGLE_SHOT_MIN_TIME_MS,
                SINGLE_SHOT_MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper(),
            )
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
        }
    }

    private fun bestEnabledProvider(manager: LocationManager): String? = PROVIDER_PREFERENCE.firstOrNull {
        manager.allProviders.contains(it) && manager.isProviderEnabled(it)
    }

    private fun readAuthorization(): LocationAuthorizationStatus =
        context.locationAuthorizationStatus(hasAsked = hasAskedLocation())

    private companion object {
        /** `LocationManager.FUSED_PROVIDER` is API 31+; the string value itself works on all levels. */
        const val FUSED_PROVIDER_COMPAT = "fused"

        val PROVIDER_PREFERENCE = listOf(
            FUSED_PROVIDER_COMPAT,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        const val SINGLE_SHOT_MIN_TIME_MS = 0L
        const val SINGLE_SHOT_MIN_DISTANCE_M = 0f

        // Internal failure reasons for LocationServiceError.RequestFailed; the region resolver
        // swallows them, so they never reach the UI.
        const val NO_PROVIDER_REASON = "no enabled location provider"
        const val NO_FIX_REASON = "no location fix"
        val PERMISSION_DECISION_TIMEOUT = 30.seconds
    }
}
