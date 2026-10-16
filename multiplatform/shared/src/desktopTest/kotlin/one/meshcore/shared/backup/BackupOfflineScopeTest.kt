package one.meshcore.shared.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.connection.LastConnectionStore
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PreferencesStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BackupOfflineScopeTest {
    private lateinit var dbFile: File

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("backup-offline-scope-", ".db")
        dbFile.deleteOnExit()
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun adoptPicksTheMostRecentlyConnectedImportedRadio() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val older = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val newer = Uuid.normalized("B0000000-0000-0000-0000-000000000002")
        db.deviceDao().upsert(device(older, "Old Radio", lastConnected = 1.0))
        db.deviceDao().upsert(device(newer, "New Radio", lastConnected = 2.0))
        val store = LastConnectionStore(InMemoryPreferencesStore())
        val warnings = mutableListOf<String>()

        adoptImportedRadioForOfflineScope(db, store, warnings::add)

        assertTrue(warnings.isEmpty())

        assertEquals(newer, store.radioId())
        assertEquals("New Radio", store.deviceName())
        assertNull(store.deviceId())
        assertNull(store.handle())
        db.close()
    }

    @Test
    fun persistFaultDoesNotThrowAfterImportedRowsLand() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        db.deviceDao().upsert(device(radio, "Imported Radio", lastConnected = 1.0))
        val prefs = object : PreferencesStore by InMemoryPreferencesStore() {
            override suspend fun putString(key: String, value: String) {
                error("datastore down")
            }
        }
        val store = LastConnectionStore(prefs)
        val warnings = mutableListOf<String>()

        adoptImportedRadioForOfflineScope(db, store, warnings::add)

        assertNull(store.radioId())
        assertEquals(listOf("Imported radio adopt failed: datastore down"), warnings)
        db.close()
    }

    @Test
    fun aCancelledAdoptPropagates() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        db.deviceDao().upsert(device(radio, "Imported Radio", lastConnected = 1.0))
        val cancelled = CancellationException("stopped")
        val prefs = object : PreferencesStore by InMemoryPreferencesStore() {
            override suspend fun putString(key: String, value: String): Unit = throw cancelled
        }
        val warnings = mutableListOf<String>()

        val thrown = assertFailsWith<CancellationException> {
            adoptImportedRadioForOfflineScope(db, LastConnectionStore(prefs), warnings::add)
        }

        assertSame(cancelled, thrown)
        assertTrue(warnings.isEmpty())
        db.close()
    }

    @Test
    fun anAdoptErrorPropagates() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        db.deviceDao().upsert(device(radio, "Imported Radio", lastConnected = 1.0))
        val probe = AdoptProbeError()
        val prefs = object : PreferencesStore by InMemoryPreferencesStore() {
            override suspend fun putString(key: String, value: String): Unit = throw probe
        }
        val warnings = mutableListOf<String>()

        val thrown = assertFailsWith<AdoptProbeError> {
            adoptImportedRadioForOfflineScope(db, LastConnectionStore(prefs), warnings::add)
        }

        assertSame(probe, thrown)
        assertTrue(warnings.isEmpty())
        db.close()
    }

    private fun device(radio: Uuid, name: String, lastConnected: Double): DeviceEntity = DeviceEntity(
        id = Uuid.normalized("D0000000-0000-0000-0000-${radio.value.takeLast(12)}"),
        radioID = radio,
        publicKey = ByteArray(PUBLIC_KEY_BYTES) { 0x11 },
        nodeName = name,
        firmwareVersion = 1,
        firmwareVersionString = "v1",
        manufacturerName = "m",
        buildDate = "d",
        maxContacts = 10,
        maxChannels = 8,
        frequency = 915_000,
        bandwidth = 250_000,
        spreadingFactor = 10,
        codingRate = 5,
        txPower = 20,
        maxTxPower = 20,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0,
        clientRepeat = false,
        pathHashMode = 0,
        manualAddContacts = true,
        autoAddConfig = 0,
        autoAddMaxHops = 0,
        multiAcks = 0,
        telemetryModeBase = 0,
        telemetryModeLoc = 0,
        telemetryModeEnv = 0,
        advertLocationPolicy = 0,
        lastConnected = lastConnected,
        lastContactSync = 0,
        isActive = true,
    )

    private class AdoptProbeError : Error("probe")

    private companion object {
        const val PUBLIC_KEY_BYTES = 32
    }
}
