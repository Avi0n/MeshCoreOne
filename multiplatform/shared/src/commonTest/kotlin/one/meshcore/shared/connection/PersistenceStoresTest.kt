package one.meshcore.shared.connection

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Round-trip and axis-separation for the two persistence stores. [LastConnectionStore] records "which
 * radio + how to reach it via BLE handle"; [ConnectionIntentStore] records the single user-disconnected
 * bit. Persisting a non-disconnected intent must clear the bit, exactly as Swift persists only the
 * `.userDisconnected` case.
 */
class PersistenceStoresTest {
    private val deviceId = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val radioId = Uuid.normalized("B0000000-0000-0000-0000-000000000002")

    @Test
    fun lastConnectionStoreRoundTripsAndClears() = runTest {
        val store = LastConnectionStore(InMemoryPreferencesStore())
        store.persist(deviceId, radioId, "Field Radio", handle = "AA:BB:CC:DD")

        assertEquals(deviceId, store.deviceId())
        assertEquals(radioId, store.radioId())
        assertEquals("Field Radio", store.deviceName())
        assertEquals("AA:BB:CC:DD", store.handle())

        store.clear()
        assertNull(store.deviceId())
        assertNull(store.radioId())
        assertNull(store.deviceName())
        assertNull(store.handle())
    }

    @Test
    fun bondVerificationRoundTripsAndReadsByHolder() = runTest {
        val store = LastConnectionStore(InMemoryPreferencesStore())
        store.persistBondVerification(handle = "AA:BB:CC:DD", epochSeconds = 1_000.0)

        assertEquals("AA:BB:CC:DD", store.bondVerifiedHandle())
        assertEquals(1_000.0, store.bondVerificationEpochSeconds("AA:BB:CC:DD"))
        // Single-slot and holder-keyed: a different handle sees no stamp even though one is stored.
        assertNull(store.bondVerificationEpochSeconds("EE:FF:00:11"))
    }

    @Test
    fun bondVerificationClearIsHolderMatched() = runTest {
        val store = LastConnectionStore(InMemoryPreferencesStore())
        store.persistBondVerification(handle = "AA:BB:CC:DD", epochSeconds = 1_000.0)

        // Clearing a non-holder must not destroy the holder's shield (a forgotten other radio).
        store.clearBondVerification("EE:FF:00:11")
        assertEquals(1_000.0, store.bondVerificationEpochSeconds("AA:BB:CC:DD"))

        // Clearing the holder wipes the slot.
        store.clearBondVerification("AA:BB:CC:DD")
        assertNull(store.bondVerifiedHandle())
        assertNull(store.bondVerificationEpochSeconds("AA:BB:CC:DD"))
    }

    @Test
    fun wifiConnectionDoesNotTouchTheBondSlot() = runTest {
        val store = LastConnectionStore(InMemoryPreferencesStore())
        store.persistBondVerification(handle = "AA:BB:CC:DD", epochSeconds = 1_000.0)

        // A WiFi connect overwrites the connection slot (handle = host:port) but carries no bond, so the
        // bond slot must survive it — the separate-slot invariant that shields BLE grace across a WiFi hop.
        store.persist(deviceId, radioId, "Field Radio", handle = "192.168.1.20:5000")

        assertEquals("192.168.1.20:5000", store.handle())
        assertEquals("AA:BB:CC:DD", store.bondVerifiedHandle())
        assertEquals(1_000.0, store.bondVerificationEpochSeconds("AA:BB:CC:DD"))
    }

    @Test
    fun persistOfflineScopeIfAbsentWritesRadioWithoutAReconnectHandle() = runTest {
        val store = LastConnectionStore(InMemoryPreferencesStore())
        store.persistOfflineScopeIfAbsent(radioId, "Imported Radio")

        assertEquals(radioId, store.radioId())
        assertEquals("Imported Radio", store.deviceName())
        assertNull(store.deviceId())
        assertNull(store.handle())
    }

    @Test
    fun persistOfflineScopeIfAbsentDoesNotOverwriteAnExistingRadio() = runTest {
        val store = LastConnectionStore(InMemoryPreferencesStore())
        store.persist(deviceId, radioId, "Field Radio", handle = "AA:BB:CC:DD")
        val other = Uuid.normalized("C0000000-0000-0000-0000-000000000003")

        store.persistOfflineScopeIfAbsent(other, "Imported Radio")

        assertEquals(radioId, store.radioId())
        assertEquals(deviceId, store.deviceId())
        assertEquals("Field Radio", store.deviceName())
        assertEquals("AA:BB:CC:DD", store.handle())
    }

    @Test
    fun intentStorePersistsOnlyTheUserDisconnectedBit() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ConnectionIntentStore(prefs)

        store.persist(ConnectionIntent.UserDisconnected)
        assertEquals(true, prefs.getBoolean(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED))
        assertEquals(ConnectionIntent.UserDisconnected, store.restore())

        store.persist(ConnectionIntent.WantsConnection())
        assertEquals(false, prefs.getBoolean(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED))
        assertEquals(ConnectionIntent.None, store.restore())
    }

    @Test
    fun intentRestoresNoneWhenNeverWritten() = runTest {
        assertEquals(ConnectionIntent.None, ConnectionIntentStore(InMemoryPreferencesStore()).restore())
    }
}
