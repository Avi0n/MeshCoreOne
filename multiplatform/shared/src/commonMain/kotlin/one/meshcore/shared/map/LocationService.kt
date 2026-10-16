package one.meshcore.shared.map

import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.runExceptionFallback
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * App-wide location permission and one-shot fix manager used by map, LOS, and region resolution. Android
 * supplies the Fused Location Provider adapter, while tests use fakes. Mirrors iOS `LocationService` (an
 * `@Observable @MainActor` class) with observable state exposed as [StateFlow]s.
 *
 * `requestCurrentLocation` is the async one-shot: it prompts for permission if needed, then resolves a
 * fix within [timeout], or throws a [LocationServiceError]. All error cases are typed rather than
 * carrying English text — the iOS enum hardcodes English strings, a gap the UI layer closes here by
 * routing each case through `L10n`.
 */
interface LocationService {
    val authorizationStatus: StateFlow<LocationAuthorizationStatus>
    val currentLocation: StateFlow<LatLng?>
    val isRequestingLocation: StateFlow<Boolean>

    /**
     * Re-reads the OS grant into [authorizationStatus]. Call on resume after the user may have
     * changed location permission in system Settings. Default is a no-op; Android refreshes.
     */
    fun refreshAuthorization() {}

    /** Request when-in-use authorization if the status is still undetermined. */
    fun requestPermissionIfNeeded()

    /** Fire-and-forget one-shot update when authorized; otherwise requests permission. */
    fun requestLocation()

    /** Async one-shot fix within [timeout]; prompts for permission if needed. Throws [LocationServiceError]. */
    suspend fun requestCurrentLocation(timeout: Duration = DEFAULT_LOCATION_TIMEOUT): LatLng
}

/** Whether location use is currently authorized (when-in-use or always). */
val LocationService.isAuthorized: Boolean
    get() = authorizationStatus.value.isAuthorized

/** The default one-shot request timeout, matching the iOS `requestCurrentLocation(timeout:)` default. */
val DEFAULT_LOCATION_TIMEOUT: Duration = 10.seconds

/**
 * The best location to center on: the phone's own fix when there is one, else the connected radio's
 * reported position. Mirrors iOS `AppState.bestAvailableLocation`, so the locate control still frames
 * something useful when location is denied or no fix has arrived.
 */
fun bestAvailableLocation(phoneLocation: LatLng?, nodeLocation: LatLng?): LatLng? = phoneLocation ?: nodeLocation

/**
 * Coordinate for a locate tap. LOS asks for a fresh fix and ignores radio fallback
 * (`freshFixOnly`). Other maps frame the last-known phone or radio position at once and only
 * request a fix when both are missing (iOS `centerOnUserLocation`).
 */
suspend fun locateTapCoordinate(known: LatLng?, locationService: LocationService?, freshFixOnly: Boolean): LatLng? {
    if (!freshFixOnly && known != null) return known
    val service = locationService ?: return if (freshFixOnly) null else known
    return runExceptionFallback({ if (freshFixOnly) null else known }) {
        service.requestCurrentLocation()
    }
}

/** Location authorization state, mirroring the iOS `CLAuthorizationStatus` cases the service reacts to. */
enum class LocationAuthorizationStatus {
    NOT_DETERMINED,
    RESTRICTED,
    DENIED,
    AUTHORIZED_WHEN_IN_USE,
    AUTHORIZED_ALWAYS,
}

/** Authorized for use (when-in-use or always). */
val LocationAuthorizationStatus.isAuthorized: Boolean
    get() = this == LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE ||
        this == LocationAuthorizationStatus.AUTHORIZED_ALWAYS

/** Permission has been decided (any status other than not-determined). */
val LocationAuthorizationStatus.hasBeenRequested: Boolean
    get() = this != LocationAuthorizationStatus.NOT_DETERMINED

/** Denied or restricted. */
val LocationAuthorizationStatus.isDenied: Boolean
    get() = this == LocationAuthorizationStatus.DENIED || this == LocationAuthorizationStatus.RESTRICTED

/**
 * Whether `requestPermissionIfNeeded` should raise the system prompt. Mirrors the iOS guard on
 * `.notDetermined`. A [DENIED] status (asked and refused) must open Settings, not prompt again.
 */
fun shouldPromptForLocation(status: LocationAuthorizationStatus, hasPrompted: Boolean): Boolean =
    status == LocationAuthorizationStatus.NOT_DETERMINED && !hasPrompted

/**
 * A location request failure. Typed cases resolved to a localized message in the UI layer (the Kotlin
 * analogue of the iOS `LocationServiceError`, whose English strings are a localization gap this port
 * does not replicate). Mirrors the iOS cases.
 */
sealed class LocationServiceError : Exception() {
    /** Not authorized; carries the status that blocked the request. */
    data class NotAuthorized(val status: LocationAuthorizationStatus) : LocationServiceError()

    /** A location or permission request is already in progress. */
    data object RequestInProgress : LocationServiceError()

    /** Timed out waiting for the permission decision. */
    data object PermissionTimeout : LocationServiceError()

    /** Timed out waiting for a location fix. */
    data object LocationTimeout : LocationServiceError()

    /** The platform location request failed; carries the underlying message. */
    data class RequestFailed(val reason: String) : LocationServiceError()
}
