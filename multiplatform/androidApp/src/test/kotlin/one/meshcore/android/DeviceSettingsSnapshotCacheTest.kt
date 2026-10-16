package one.meshcore.android

import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.backup.RegionSource
import one.meshcore.shared.settings.LocationSettingsSnapshot
import one.meshcore.shared.settings.RadioDeviceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Settings composition reads this cache on the main thread. A miss must be null, never a
 * `runBlocking` Room query — that ANRs the Settings tap.
 */
class DeviceSettingsSnapshotCacheTest {
    @Test
    fun anEmptyCacheReturnsNullWithoutASuspendCall() {
        val cache = DeviceSettingsSnapshotCache()

        assertNull(cache.radio)
        assertNull(cache.location)
        assertNull(cache.connection)
        assertNull(cache.region)
        assertNull(cache.deviceRowId)
    }

    @Test
    fun aFilledCacheReturnsTheSameSnapshotsOnTheCallingThread() {
        val cache = DeviceSettingsSnapshotCache()
        val radio = RadioDeviceSnapshot(
            frequencyKHz = FREQUENCY_KHZ,
            bandwidthHz = BANDWIDTH_HZ,
            spreadingFactor = SPREADING_FACTOR,
            codingRate = CODING_RATE,
        )
        val location = LocationSettingsSnapshot(
            latitude = LATITUDE,
            longitude = LONGITUDE,
            advertLocationPolicyRaw = 0,
        )
        val region = RegionSelection(countryCode = COUNTRY_CODE, source = RegionSource.MANUAL)

        cache.radio = radio
        cache.location = location
        cache.region = region
        cache.deviceRowId = DEVICE_ROW_ID

        assertEquals(radio, cache.radio)
        assertEquals(location, cache.location)
        assertEquals(region, cache.region)
        assertEquals(DEVICE_ROW_ID, cache.deviceRowId)
    }

    @Test
    fun clearingRadioOwnedDropsDeviceRowsAndKeepsTheUserRegion() {
        val cache = DeviceSettingsSnapshotCache()
        cache.radio =
            RadioDeviceSnapshot(
                frequencyKHz = FREQUENCY_KHZ,
                bandwidthHz = BANDWIDTH_HZ,
                spreadingFactor = SPREADING_FACTOR,
                codingRate = CODING_RATE,
            )
        cache.region = RegionSelection(countryCode = COUNTRY_CODE, source = RegionSource.MANUAL)

        cache.clearRadioOwned()

        assertNull(cache.radio)
        assertEquals(COUNTRY_CODE, cache.region?.countryCode)
    }

    private companion object {
        const val FREQUENCY_KHZ = 869_525L
        const val BANDWIDTH_HZ = 250_000L
        const val SPREADING_FACTOR = 11
        const val CODING_RATE = 5
        const val LATITUDE = 51.5
        const val LONGITUDE = -0.12
        const val COUNTRY_CODE = "GB"
        const val DEVICE_ROW_ID = "device-row"
    }
}
