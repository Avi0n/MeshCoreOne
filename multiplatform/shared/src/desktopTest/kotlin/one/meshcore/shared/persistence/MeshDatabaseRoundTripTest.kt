package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Proves the Room layer end to end on the desktop target: the shared UUID converter (uppercasing),
 * the JSON list converters (connection methods + known regions), `ByteArray` BLOB storage, and the
 * `RawValue`-column-plus-typed-accessor pattern (notification level, flood scope) all round-trip
 * through the bundled SQLite engine.
 */
class MeshDatabaseRoundTripTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase

    private val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-roundtrip", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun deviceRoundTripsConvertersAndBlob() = runBlocking {
        val publicKey = ByteArray(32) { 0x11 }
        val device =
            DeviceEntity(
                // Lower-case on the way in to prove the converter normalizes to canonical uppercase.
                id = Uuid("d0000000-0000-0000-0000-000000000001"),
                radioID = radioId,
                publicKey = publicKey,
                nodeName = "TestDevice",
                firmwareVersion = 9,
                firmwareVersionString = "v1.13.0",
                manufacturerName = "TestMfg",
                buildDate = "01 Jan 2025",
                maxContacts = 100,
                maxChannels = 8,
                frequency = 915_000,
                bandwidth = 250_000,
                spreadingFactor = 10,
                codingRate = 5,
                txPower = 20,
                maxTxPower = 20,
                latitude = 37.7749,
                longitude = -122.4194,
                blePin = 0,
                clientRepeat = false,
                pathHashMode = 0,
                manualAddContacts = false,
                autoAddConfig = 0,
                autoAddMaxHops = 0,
                multiAcks = 2,
                telemetryModeBase = 2,
                telemetryModeLoc = 0,
                telemetryModeEnv = 0,
                advertLocationPolicy = 0,
                lastConnected = 1_699_000_000.0,
                lastContactSync = 0,
                isActive = true,
                connectionMethods = listOf(ConnectionMethod.WiFi("192.168.1.50", 5000, "Base Camp")),
                knownRegions = listOf("US915", "EU868"),
                appliedRadioPresetID = "br",
            )
        db.deviceDao().upsert(device)

        val fetched = requireNotNull(db.deviceDao().getById(Uuid("D0000000-0000-0000-0000-000000000001")))
        assertEquals("D0000000-0000-0000-0000-000000000001", fetched.id.value, "converter must uppercase")
        assertEquals(radioId, fetched.radioID)
        assertContentEquals(publicKey, fetched.publicKey)
        assertEquals(
            listOf(ConnectionMethod.WiFi("192.168.1.50", 5000, "Base Camp")),
            fetched.connectionMethods,
        )
        assertEquals(listOf("US915", "EU868"), fetched.knownRegions)
        assertEquals("br", fetched.appliedRadioPresetID)
    }

    @Test
    fun channelRoundTripsEnumRawColumnAndFloodScope() = runBlocking {
        val channel =
            ChannelEntity(
                id = Uuid.normalized("C0000000-0000-0000-0000-0000000000AA"),
                radioID = radioId,
                index = 1,
                name = "#ops",
                secret = ByteArray(16) { 0x22 },
                isEnabled = true,
                unreadCount = 3,
                unreadMentionCount = 1,
                notificationLevelRawValue = NotificationLevel.MENTIONS_ONLY.raw,
                isPinned = true,
                floodScopeModeRawValue = ChannelFloodScopeStorage.MODE_SPECIFIC,
                regionScope = "US",
            )
        db.channelDao().upsert(channel)

        val fetched = db.channelDao().listByRadio(radioId).single()
        // Typed accessor reads the raw column back to the enum.
        assertEquals(NotificationLevel.MENTIONS_ONLY, fetched.notificationLevel)
        assertEquals(1, fetched.notificationLevelRawValue)
        // Two raw columns recompose to the typed flood scope.
        assertEquals(ChannelFloodScope.Region("US"), fetched.floodScope)
        assertContentEquals(ByteArray(16) { 0x22 }, fetched.secret)
    }
}
