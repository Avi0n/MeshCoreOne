package one.meshcore.shared.map

import android.content.Context
import android.location.Address
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The Android [Geocoder], backed by the framework `android.location.Geocoder`. API 33+ uses the async
 * listener overload; older levels run the blocking overload on the IO dispatcher. The framework offers
 * no cancellation handle, so [cancelGeocode] is a no-op — [RegionResolver] already bounds the call
 * with its own timeout and abandons the result on cancellation.
 */
class AndroidGeocoder(private val context: Context, private val ioDispatcher: CoroutineDispatcher) : Geocoder {
    override suspend fun reverseGeocode(location: LatLng, preferredLocale: String?): GeocodeResult? {
        if (!android.location.Geocoder.isPresent()) return null
        val locale = preferredLocale
            ?.let { Locale.forLanguageTag(it.replace('_', '-')) }
            ?: Locale.getDefault()
        val geocoder = android.location.Geocoder(context, locale)
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            awaitFromLocation(geocoder, location)
        } else {
            withContext(ioDispatcher) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.latitude, location.longitude, SINGLE_RESULT)?.firstOrNull()
            }
        }
        return address?.let {
            GeocodeResult(
                countryCode = it.countryCode,
                administrativeArea = it.adminArea,
                subAdministrativeArea = it.subAdminArea,
            )
        }
    }

    override fun cancelGeocode() = Unit

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Suppress("RedundantSuspendModifier")
    private suspend fun awaitFromLocation(geocoder: android.location.Geocoder, location: LatLng): Address? =
        suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocation(
                location.latitude,
                location.longitude,
                SINGLE_RESULT,
                object : android.location.Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )
        }

    private companion object {
        const val SINGLE_RESULT = 1
    }
}
