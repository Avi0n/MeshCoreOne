package one.meshcore.shared.connection

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.persistence.MeshDatabase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A cancelled local contact-count read is rethrown. Any other failure, including a thrown error,
 * is logged and degrades to an empty count at the radio's slot cap.
 */
class ConnectionContactCapacityFaultTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-contact-capacity-fault", ".db").also { it.delete() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        if (::db.isInitialized) db.close()
        dbFile.delete()
    }

    @Test
    fun aCancelledContactCountIsRethrown() = runBlocking {
        val stopped = CancellationException("stopped")
        val manager = manager { throw stopped }
        manager.insertRadio()

        val thrown = assertFailsWith<CancellationException> {
            manager.localContactTableCapacity(RADIO_ID)
        }

        assertTrue(thrown === stopped || thrown.cause === stopped)
    }

    @Test
    fun aContactCountErrorDegradesToAnEmptyTable() = runBlocking {
        val manager = manager { throw ContactCapacityProbeError() }
        manager.insertRadio()

        val capacity = manager.localContactTableCapacity(RADIO_ID)

        assertEquals(0, capacity.realContactCount)
        assertEquals(MAX_CONTACTS, capacity.maxContacts)
    }

    private fun manager(failContactRead: () -> Nothing): ConnectionManager {
        db = Room.databaseBuilder<MeshDatabase>(name = dbFile.absolutePath)
            .setDriver(ContactQueryFaultDriver(failContactRead))
            .setQueryCoroutineContext(Dispatchers.IO)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { FakeMeshLink() },
            sessionFactory = { FakeMeshSession() },
            nowEpochSeconds = { NOW_SECONDS },
        )
    }

    private suspend fun ConnectionManager.insertRadio() {
        database.deviceDao().upsert(radio())
    }

    private fun radio() = DeviceEntity(
        id = DEVICE_ID,
        radioID = RADIO_ID,
        publicKey = ByteArray(PUBLIC_KEY_BYTES),
        nodeName = "radio",
        firmwareVersion = 1,
        firmwareVersionString = "v1",
        manufacturerName = "m",
        buildDate = "d",
        maxContacts = MAX_CONTACTS,
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
        lastConnected = NOW_SECONDS,
        lastContactSync = 0,
        isActive = true,
    )

    private companion object {
        val RADIO_ID: Uuid = Uuid.normalized("00000000-0000-0000-0000-000000000071")
        val DEVICE_ID: Uuid = Uuid.normalized("00000000-0000-0000-0000-000000000072")
        const val MAX_CONTACTS = 40
        const val PUBLIC_KEY_BYTES = 32
        const val NOW_SECONDS = 1_700_000_000.0
    }
}

private class ContactCapacityProbeError : Error("probe")

/**
 * Room's contact query is the count read. Failing that statement leaves device writes alone so the
 * capacity path still has a radio row to degrade against.
 */
private class ContactQueryFaultDriver(private val failContactRead: () -> Nothing) : SQLiteDriver {
    private val delegate = BundledSQLiteDriver()

    override fun open(fileName: String): SQLiteConnection =
        FaultingContactConnection(delegate.open(fileName), failContactRead)
}

private class FaultingContactConnection(
    private val delegate: SQLiteConnection,
    private val failContactRead: () -> Nothing,
) : SQLiteConnection by delegate {
    override fun prepare(sql: String) = if (sql.contains("FROM contact", ignoreCase = true)) {
        failContactRead()
    } else {
        delegate.prepare(sql)
    }
}
