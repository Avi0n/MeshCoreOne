package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.transport.TcpTransportException
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Device-row WiFi endpoint edits for Settings → Connection. */
class ConnectionWifiEditTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var lastConnection: LastConnectionStore

    private val deviceId = Uuid.normalized("D0000000-0000-0000-0000-000000000001")
    private val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val publicKey = ByteArray(32) { 0x01 }

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-wifi-edit", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lastConnection = LastConnectionStore(InMemoryPreferencesStore())
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    @Test
    fun updateWifiEndpointTrimsTheHostAndRejectsABlankOne() = runBlocking {
        db.deviceDao().upsert(deviceEntity(methods = listOf(ConnectionMethod.WiFi("10.0.0.1", 4403, "Camp"))))
        lastConnection.persist(deviceId, radioId, "Node", deviceId.value)
        val manager = ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { FakeMeshLink() },
            sessionFactory = { FakeMeshSession() },
            nowEpochSeconds = { 1_000.0 },
            ports = ConnectionManagerPorts(
                lastConnectionStore = lastConnection,
                newRadioId = { radioId },
            ),
        )

        assertIs<TcpTransportException.InvalidHost>(
            assertFailsWith<TcpTransportException> { manager.updateWifiEndpoint("   ", 5000) },
        )

        manager.updateWifiEndpoint(host = "  192.168.1.50  ", port = 5000)
        val wifi = db.deviceDao().getById(deviceId)!!.connectionMethods
            .filterIsInstance<ConnectionMethod.WiFi>().single()
        assertEquals("192.168.1.50", wifi.host)
        assertEquals(5000, wifi.port)
        assertEquals("Camp", wifi.displayName)
    }

    @Test
    fun updateWifiEndpointMergesHostPortAndPreservesBluetooth() = runBlocking {
        db.deviceDao().upsert(
            deviceEntity(
                methods = listOf(
                    ConnectionMethod.Bluetooth(deviceId, "ble-handle"),
                    ConnectionMethod.WiFi("10.0.0.1", 4403, "Camp"),
                ),
            ),
        )
        lastConnection.persist(deviceId, radioId, "Node", deviceId.value)
        val manager = ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { FakeMeshLink() },
            sessionFactory = { FakeMeshSession() },
            nowEpochSeconds = { 1_000.0 },
            ports = ConnectionManagerPorts(
                lastConnectionStore = lastConnection,
                newRadioId = { radioId },
            ),
        )

        manager.updateWifiEndpoint(host = "192.168.1.50", port = 5000)

        val methods = db.deviceDao().getById(deviceId)!!.connectionMethods
        assertEquals(2, methods.size)
        assertIs<ConnectionMethod.Bluetooth>(methods.first { it is ConnectionMethod.Bluetooth })
        val wifi = methods.first { it is ConnectionMethod.WiFi } as ConnectionMethod.WiFi
        assertEquals("192.168.1.50", wifi.host)
        assertEquals(5000, wifi.port)
        assertEquals("Camp", wifi.displayName)

        val snapshot = manager.connectionSettingsSnapshot()!!
        assertEquals("192.168.1.50", snapshot.wifiHost)
        assertEquals(5000, snapshot.wifiPort)
        assertEquals(true, snapshot.hasWifiMethod)
        assertEquals(true, snapshot.hasBluetoothMethod)
    }

    @Test
    fun updateWifiEndpointReconnectsALiveWifiSessionToTheNewAddress() = runBlocking {
        lastConnection.persist(deviceId, radioId, "Node", deviceId.value)
        val links = mutableListOf<FakeWiFiLink>()
        val manager = ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { FakeMeshLink() },
            sessionFactory = { FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) },
            nowEpochSeconds = { 1_000.0 },
            ports = ConnectionManagerPorts(
                wifiLinkFactory = { h, p, hb -> FakeWiFiLink(h, p, hb).also(links::add) },
                lastConnectionStore = lastConnection,
                newRadioId = { radioId },
            ),
        )

        manager.connectViaWiFi("10.0.0.1", 4403)
        assertEquals(1, links.size)
        assertEquals("10.0.0.1", links.first().host)
        assertEquals(4403, links.first().port)

        manager.updateWifiEndpoint(host = "192.168.1.50", port = 5000)

        assertEquals(2, links.size)
        assertEquals("192.168.1.50", links.last().host)
        assertEquals(5000, links.last().port)
        assertEquals("192.168.1.50:5000", (manager.state.value as ConnectionState.Ready).deviceId)
    }

    @Test
    fun updateOCVSettingsPersistsPresetAndCustomArray() = runBlocking {
        db.deviceDao().upsert(deviceEntity(methods = emptyList()))
        lastConnection.persist(deviceId, radioId, "Node", deviceId.value)
        val manager = ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { FakeMeshLink() },
            sessionFactory = { FakeMeshSession() },
            nowEpochSeconds = { 1_000.0 },
            ports = ConnectionManagerPorts(
                lastConnectionStore = lastConnection,
                newRadioId = { radioId },
            ),
        )

        manager.updateOCVSettings(preset = "liFePO4", customArray = null)
        val afterPreset = db.deviceDao().getById(deviceId)!!
        assertEquals("liFePO4", afterPreset.ocvPreset)
        assertEquals(null, afterPreset.customOCVArrayString)

        val custom = "4000,3900,3800,3700,3600,3500,3400,3300,3200,3100,3000"
        manager.updateOCVSettings(preset = "custom", customArray = custom)
        val afterCustom = db.deviceDao().getById(deviceId)!!
        assertEquals("custom", afterCustom.ocvPreset)
        assertEquals(custom, afterCustom.customOCVArrayString)

        val editor = manager.deviceOcvEditorState()!!
        assertEquals(OCVPreset.CUSTOM, editor.preset)
        assertEquals(11, editor.values.size)
        assertEquals(4000, editor.values.first())
    }

    private fun deviceEntity(methods: List<ConnectionMethod>) = DeviceEntity(
        id = deviceId,
        radioID = radioId,
        publicKey = ByteArray(32) { 0x01 },
        nodeName = "Node",
        firmwareVersion = 0,
        firmwareVersionString = "test",
        manufacturerName = "test",
        buildDate = "",
        maxContacts = 0,
        maxChannels = 0,
        frequency = 0,
        bandwidth = 0,
        spreadingFactor = 0,
        codingRate = 0,
        txPower = 0,
        maxTxPower = 0,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0,
        clientRepeat = false,
        pathHashMode = 0,
        manualAddContacts = false,
        autoAddConfig = 0,
        autoAddMaxHops = 0,
        multiAcks = 0,
        telemetryModeBase = 0,
        telemetryModeLoc = 0,
        telemetryModeEnv = 0,
        advertLocationPolicy = 0,
        lastConnected = 0.0,
        lastContactSync = 0,
        isActive = true,
        connectionMethods = methods,
    )
}
