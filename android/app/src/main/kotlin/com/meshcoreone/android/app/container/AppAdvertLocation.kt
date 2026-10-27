// PortedFrom: MC1/State/AppState+DeviceActions.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.datastore.DevicePreferenceSnapshot
import com.meshcoreone.android.core.datastore.GPSSource
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.services.content.LocationProducing
import com.meshcoreone.android.core.services.content.LocationService
import com.meshcoreone.android.core.services.content.LocationServiceError
import com.meshcoreone.android.core.services.device.SettingsService
import java.util.UUID
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException

class AppAdvertLocation(
    private val preferences: suspend (UUID) -> DevicePreferenceSnapshot,
    producing: LocationProducing,
    private val refreshAuthorization: () -> Unit = {},
) {
    private val location = LocationService(object : LocationProducing by producing {
        override fun requestPermissionIfNeeded(): Nothing =
            throw LocationServiceError.NotAuthorized(producing.authorizationStatus.value)
    })

    internal suspend fun gpsSource(device: DeviceDTO?): GPSSource? {
        if (device == null || !device.sharesLocationPublicly) return null
        val value = preferences(device.id)
        return value.gpsSource.takeIf { value.autoUpdateLocation }
    }

    internal suspend fun prepare(
        device: () -> DeviceDTO?,
        settings: SettingsService,
        requireCurrent: () -> Unit,
    ) {
        val source = gpsSource(device()) ?: return
        requireCurrent()
        if (device()?.sharesLocationPublicly != true) return
        try {
            when (source) {
                GPSSource.PHONE -> {
                    refreshAuthorization()
                    val fix = location.requestCurrentLocation()
                    requireCurrent()
                    if (gpsSource(device()) == source && device()?.sharesLocationPublicly == true) {
                        requireCurrent()
                        settings.setLocationVerified(fix.latitude, fix.longitude)
                    }
                }
                GPSSource.DEVICE -> {
                    val state = settings.getDeviceGPSState()
                    requireCurrent()
                    if (!state.isEnabled && gpsSource(device()) == source && device()?.sharesLocationPublicly == true) {
                        requireCurrent()
                        settings.setDeviceGPSEnabledVerified(true)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LocationServiceError) {
            report(failure)
        } catch (failure: SettingsServiceException) {
            report(failure)
        }
    }

    private fun report(failure: Exception) {
        // Refresh is best effort in the source; never put coordinates or raw firmware errors into speech/log copy.
        Logger.getLogger("com.mc1.Shortcuts").warning(
            "Pre-advert location refresh unavailable (${failure.javaClass.simpleName})",
        )
    }
}
