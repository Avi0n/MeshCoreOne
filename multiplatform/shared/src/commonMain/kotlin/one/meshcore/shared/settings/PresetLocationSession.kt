package one.meshcore.shared.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.isAuthorized
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * GPS resolve host for Settings → Radio → Location. Compose observes the flows; policy decides
 * whether a result is committed, when to wait for authorization, and when to open Settings.
 */
class PresetLocationSession {
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _showOpenSettingsAlert = MutableStateFlow(false)
    val showOpenSettingsAlert: StateFlow<Boolean> = _showOpenSettingsAlert.asStateFlow()

    private val _isResolving = MutableStateFlow(false)
    val isResolving: StateFlow<Boolean> = _isResolving.asStateFlow()

    private val mutex = Mutex()

    fun clearError() {
        _errorMessage.value = null
    }

    fun dismissOpenSettingsAlert() {
        _showOpenSettingsAlert.value = false
    }

    suspend fun resolveOnAppear(host: PresetLocationHost) {
        val authorized = host.locationService.isAuthorized
        if (!PresetLocationPolicy.shouldResolveOnAppear(authorized, host.current()?.source)) return
        mutex.withLock { perform(PresetLocationPolicy.ResolveKind.APPEAR, host) }
    }

    suspend fun useMyLocation(host: PresetLocationHost) {
        mutex.withLock { perform(PresetLocationPolicy.ResolveKind.USER_INITIATED, host) }
    }

    private suspend fun perform(kind: PresetLocationPolicy.ResolveKind, host: PresetLocationHost) {
        _isResolving.value = true
        try {
            when (kind) {
                PresetLocationPolicy.ResolveKind.APPEAR -> runResolve(kind, host)
                PresetLocationPolicy.ResolveKind.USER_INITIATED -> runUserInitiatedResolve(host)
            }
        } finally {
            _isResolving.value = false
        }
    }

    private suspend fun runUserInitiatedResolve(host: PresetLocationHost) {
        when (PresetLocationPolicy.useMyLocationAction(host.locationService.authorizationStatus.value)) {
            PresetLocationPolicy.UseMyLocationAction.OPEN_SETTINGS -> _showOpenSettingsAlert.value = true
            PresetLocationPolicy.UseMyLocationAction.RESOLVE ->
                runResolve(PresetLocationPolicy.ResolveKind.USER_INITIATED, host)
            PresetLocationPolicy.UseMyLocationAction.WAIT_FOR_AUTHORIZATION -> waitForAuthorizationThenResolve(host)
        }
    }

    private suspend fun waitForAuthorizationThenResolve(host: PresetLocationHost) {
        try {
            host.locationService.requestCurrentLocation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Status is the source of truth after timeout, deny, or a location miss.
        }
        when (PresetLocationPolicy.actionAfterAuthorizationWait(host.locationService.authorizationStatus.value)) {
            PresetLocationPolicy.AfterAuthorizationWait.RESOLVE ->
                runResolve(PresetLocationPolicy.ResolveKind.USER_INITIATED, host)
            PresetLocationPolicy.AfterAuthorizationWait.OPEN_SETTINGS -> _showOpenSettingsAlert.value = true
            PresetLocationPolicy.AfterAuthorizationWait.NONE -> Unit
        }
    }

    private suspend fun runResolve(kind: PresetLocationPolicy.ResolveKind, host: PresetLocationHost) {
        if (host.locationService.isRequestingLocation.value) {
            waitForLocationSlot(host.locationService)
        }
        if (host.locationService.isRequestingLocation.value) return

        val result = host.resolve()
        val current = host.current()
        val next = PresetLocationPolicy.committedSelection(current, result, kind)
        if (next != current) host.setSelection(next)

        if (result == null &&
            PresetLocationPolicy.shouldPresentLookupMiss(kind, requestInProgress = false)
        ) {
            _errorMessage.value = host.lookupMissMessage
        }
    }

    private suspend fun waitForLocationSlot(locationService: LocationService) {
        val deadline = TimeSource.Monotonic.markNow() + LOCATION_SLOT_WAIT
        while (locationService.isRequestingLocation.value && TimeSource.Monotonic.markNow() < deadline) {
            delay(LOCATION_SLOT_POLL)
        }
    }

    private companion object {
        val LOCATION_SLOT_WAIT: Duration = 10.seconds
        val LOCATION_SLOT_POLL: Duration = 50.milliseconds
    }
}

/**
 * Live inputs the location session needs for one Radio / Location page. [setSelection] persists
 * and updates the caller's held [RegionSelection].
 */
data class PresetLocationHost(
    val locationService: LocationService,
    val current: () -> RegionSelection?,
    val setSelection: (RegionSelection?) -> Unit,
    val resolve: suspend () -> RegionSelection?,
    val lookupMissMessage: String,
)
