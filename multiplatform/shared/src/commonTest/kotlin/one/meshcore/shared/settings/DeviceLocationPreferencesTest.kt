package one.meshcore.shared.settings

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DEVICE_A = "8C4A1E2F-0000-4000-8000-000000000001"
private const val DEVICE_B = "8C4A1E2F-0000-4000-8000-000000000002"

class DeviceLocationPreferencesTest {
    @Test
    fun defaultsToManualPositionFromThePhone() = runTest {
        val prefs = DeviceLocationPreferences(InMemoryPreferencesStore())
        assertFalse(prefs.isAutoUpdateLocationEnabled(DEVICE_A))
        assertEquals(GpsSource.PHONE, prefs.gpsSource(DEVICE_A))
        assertFalse(prefs.hasSetGpsSource(DEVICE_A))
    }

    @Test
    fun scopesEachSettingToItsOwnRadio() = runTest {
        val prefs = DeviceLocationPreferences(InMemoryPreferencesStore())
        prefs.setAutoUpdateLocationEnabled(true, DEVICE_A)
        prefs.setGpsSource(GpsSource.DEVICE, DEVICE_A)

        assertTrue(prefs.isAutoUpdateLocationEnabled(DEVICE_A))
        assertEquals(GpsSource.DEVICE, prefs.gpsSource(DEVICE_A))
        assertFalse(prefs.isAutoUpdateLocationEnabled(DEVICE_B))
        assertEquals(GpsSource.PHONE, prefs.gpsSource(DEVICE_B))
    }

    @Test
    fun usesTheSameKeyStringsAsTheSwiftStore() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = DeviceLocationPreferences(store)
        prefs.setAutoUpdateLocationEnabled(true, DEVICE_A)
        prefs.setGpsSource(GpsSource.DEVICE, DEVICE_A)

        assertEquals(true, store.snapshot()["device.$DEVICE_A.autoUpdateLocation"])
        assertEquals("device", store.snapshot()["device.$DEVICE_A.gpsSource"])
    }

    @Test
    fun distinguishesAnExplicitPhoneChoiceFromNoChoice() = runTest {
        val prefs = DeviceLocationPreferences(InMemoryPreferencesStore())
        prefs.setGpsSource(GpsSource.PHONE, DEVICE_A)
        assertTrue(prefs.hasSetGpsSource(DEVICE_A))
        assertEquals(GpsSource.PHONE, prefs.gpsSource(DEVICE_A))
    }

    @Test
    fun keepingAutomaticLocationOffDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = DeviceLocationPreferences(store)
        assertFalse(prefs.isAutoUpdateLocationEnabled(DEVICE_A))
        assertEquals(GpsSource.PHONE, prefs.gpsSource(DEVICE_A))
        assertFalse(prefs.hasSetGpsSource(DEVICE_A))
        assertFalse(prefs.isAutoUpdateLocationEnabled(DEVICE_B))
        assertTrue(store.snapshot().isEmpty())

        prefs.setAutoUpdateLocationEnabled(false, DEVICE_A)

        assertFalse(prefs.isAutoUpdateLocationEnabled(DEVICE_A))
        assertEquals(GpsSource.PHONE, prefs.gpsSource(DEVICE_A))
        assertFalse(prefs.hasSetGpsSource(DEVICE_A))
        assertFalse(prefs.isAutoUpdateLocationEnabled(DEVICE_B))
        assertEquals(GpsSource.PHONE, prefs.gpsSource(DEVICE_B))
        assertFalse(prefs.hasSetGpsSource(DEVICE_B))
        val snap = store.snapshot()
        assertEquals(false, snap["device.$DEVICE_A.autoUpdateLocation"])
        assertEquals(null, snap["device.$DEVICE_A.gpsSource"])
        assertEquals(null, snap["device.$DEVICE_B.autoUpdateLocation"])
        assertEquals(null, snap["device.$DEVICE_B.gpsSource"])
    }

    @Test
    fun turningOnAutomaticLocationForOneRadioDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = DeviceLocationPreferences(store)

        prefs.setAutoUpdateLocationEnabled(true, DEVICE_A)

        assertTrue(prefs.isAutoUpdateLocationEnabled(DEVICE_A))
        assertEquals(GpsSource.PHONE, prefs.gpsSource(DEVICE_A))
        assertFalse(prefs.hasSetGpsSource(DEVICE_A))
        assertFalse(prefs.isAutoUpdateLocationEnabled(DEVICE_B))
        assertEquals(GpsSource.PHONE, prefs.gpsSource(DEVICE_B))
        assertFalse(prefs.hasSetGpsSource(DEVICE_B))
        val snap = store.snapshot()
        assertEquals(true, snap["device.$DEVICE_A.autoUpdateLocation"])
        assertEquals(null, snap["device.$DEVICE_A.gpsSource"])
        assertEquals(null, snap["device.$DEVICE_B.autoUpdateLocation"])
        assertEquals(null, snap["device.$DEVICE_B.gpsSource"])
    }

    @Test
    fun keepingThePhonePositionSourceDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = DeviceLocationPreferences(store)
        assertEquals(GpsSource.PHONE, prefs.gpsSource(DEVICE_A))
        assertFalse(prefs.hasSetGpsSource(DEVICE_A))
        assertFalse(prefs.isAutoUpdateLocationEnabled(DEVICE_A))
        assertEquals(GpsSource.PHONE, prefs.gpsSource(DEVICE_B))
        assertFalse(prefs.hasSetGpsSource(DEVICE_B))
        assertFalse(prefs.isAutoUpdateLocationEnabled(DEVICE_B))
        assertTrue(store.snapshot().isEmpty())

        prefs.setGpsSource(GpsSource.PHONE, DEVICE_A)

        assertEquals(GpsSource.PHONE, prefs.gpsSource(DEVICE_A))
        assertTrue(prefs.hasSetGpsSource(DEVICE_A))
        assertFalse(prefs.isAutoUpdateLocationEnabled(DEVICE_A))
        assertEquals(GpsSource.PHONE, prefs.gpsSource(DEVICE_B))
        assertFalse(prefs.hasSetGpsSource(DEVICE_B))
        assertFalse(prefs.isAutoUpdateLocationEnabled(DEVICE_B))
        val snap = store.snapshot()
        assertEquals("phone", snap["device.$DEVICE_A.gpsSource"])
        assertEquals(null, snap["device.$DEVICE_A.autoUpdateLocation"])
        assertEquals(null, snap["device.$DEVICE_B.gpsSource"])
        assertEquals(null, snap["device.$DEVICE_B.autoUpdateLocation"])
        assertEquals(
            mapOf("device.$DEVICE_A.gpsSource" to "phone"),
            snap,
        )
    }

    @Test
    fun choosingADeviceGpsSourceDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = DeviceLocationPreferences(store)

        prefs.setGpsSource(GpsSource.DEVICE, DEVICE_A)

        assertEquals(GpsSource.DEVICE, prefs.gpsSource(DEVICE_A))
        assertTrue(prefs.hasSetGpsSource(DEVICE_A))
        assertFalse(prefs.isAutoUpdateLocationEnabled(DEVICE_A))
        assertEquals(GpsSource.PHONE, prefs.gpsSource(DEVICE_B))
        assertFalse(prefs.hasSetGpsSource(DEVICE_B))
        assertFalse(prefs.isAutoUpdateLocationEnabled(DEVICE_B))
        val snap = store.snapshot()
        assertEquals("device", snap["device.$DEVICE_A.gpsSource"])
        assertEquals(null, snap["device.$DEVICE_A.autoUpdateLocation"])
        assertEquals(null, snap["device.$DEVICE_B.gpsSource"])
        assertEquals(null, snap["device.$DEVICE_B.autoUpdateLocation"])
    }
}
