package one.meshcore.shared.settings

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.isAuthorized

/**
 * The connected radio as a self-advert sees it: the identity the per-radio location preferences are keyed
 * by, the advert policy that decides whether a position is shared at all, the writes a pre-advert refresh
 * performs, and the transmit itself. Mirrors the slice of the Swift `SettingsService` and
 * `AdvertisementService` that `AppState.sendSelfAdvert` drives.
 */
interface SelfAdvertRadio {
    /** Device-row id scoping the per-radio location preferences; `null` when the row is unknown. */
    suspend fun deviceId(): String?

    /** Whether the advert policy carries a position at all (Swift `Device.sharesLocationPublicly`). */
    suspend fun sharesLocationPublicly(): Boolean

    /** Writes a coordinate and proves it stuck, leaving the on-board GPS alone (`setLocationVerified`). */
    suspend fun writeCoordinate(latitude: Double, longitude: Double)

    /** The on-board GPS capability and state. */
    suspend fun deviceGpsState(): DeviceGpsState

    /** Switches the on-board GPS on and proves it stuck. */
    suspend fun enableDeviceGps()

    /** Queues the advert on the radio. */
    suspend fun sendAdvertisement(flood: Boolean)
}

/**
 * Broadcasts a self-advert, refreshing the node's position first when the radio's advert policy and the
 * user's per-radio preference both allow it. Mirrors Swift `AppState.sendSelfAdvert`: without the refresh
 * a radio whose owner asked for auto-update broadcasts whatever coordinate it last held, which can be
 * hours and kilometres stale.
 */
class SelfAdvertSender(
    private val preferences: DeviceLocationPreferences,
    private val locationService: () -> LocationService?,
    private val onRefreshFailure: suspend (Throwable) -> Unit = {},
) {
    /**
     * [allowLocationPrompt] false keeps the refresh to already-authorized location, for callers that
     * cannot present a permission dialog and would otherwise stall on one that can never resolve.
     */
    suspend fun send(radio: SelfAdvertRadio, flood: Boolean, allowLocationPrompt: Boolean) {
        val source = gpsSource(radio)
        if (source != null) refreshLocation(radio, source, allowLocationPrompt)
        radio.sendAdvertisement(flood)
    }

    /**
     * The GPS to refresh from, or `null` when the radio's advert policy or the user's per-radio preference
     * disables auto-update. Either gate closed means no location work happens at all: both are privacy
     * controls, and neither may be inferred from the other.
     */
    private suspend fun gpsSource(radio: SelfAdvertRadio): GpsSource? {
        val deviceId = radio.deviceId()
        if (deviceId == null || !radio.sharesLocationPublicly()) return null
        return if (preferences.isAutoUpdateLocationEnabled(deviceId)) preferences.gpsSource(deviceId) else null
    }

    /** An Exception is reported and the advert still goes out. Cancellation and an Error stop the advert. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun refreshLocation(radio: SelfAdvertRadio, source: GpsSource, allowLocationPrompt: Boolean) {
        try {
            when (source) {
                GpsSource.PHONE -> writePhoneFix(radio, allowLocationPrompt)
                GpsSource.DEVICE -> if (!radio.deviceGpsState().isEnabled) radio.enableDeviceGps()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onRefreshFailure(error)
        }
    }

    private suspend fun writePhoneFix(radio: SelfAdvertRadio, allowLocationPrompt: Boolean) {
        val service = locationService() ?: return
        val fix = if (allowLocationPrompt || service.isAuthorized) {
            freshOrCachedFix(service)
        } else {
            service.currentLocation.value
        }
        if (fix != null) radio.writeCoordinate(fix.latitude, fix.longitude)
    }

    /**
     * The one-shot fix, falling back to the last cached one when the request fails with an Exception.
     * Cancellation and an Error propagate, so a cached fix is not advertised in their place.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun freshOrCachedFix(service: LocationService): LatLng = try {
        service.requestCurrentLocation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        service.currentLocation.value ?: throw error
    }
}
