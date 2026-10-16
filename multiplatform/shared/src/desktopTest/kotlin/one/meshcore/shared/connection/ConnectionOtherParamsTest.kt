package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.settings.AdvertLocationPolicy
import one.meshcore.shared.settings.AppliedRadioPresetStore
import one.meshcore.shared.settings.DeviceOtherParamsSnapshot
import one.meshcore.shared.settings.ManualRadioParams
import one.meshcore.shared.settings.RadioPresets
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Settings → Connection PIN commit/revert (reboot to apply) and Settings → Advanced telemetry /
 * multi-ack / path-hash writes over the live session.
 */
class ConnectionOtherParamsTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var lastConnection: LastConnectionStore
    private lateinit var appliedPresets: AppliedRadioPresetStore
    private lateinit var prefs: InMemoryPreferencesStore

    private val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-other-params", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        prefs = InMemoryPreferencesStore()
        lastConnection = LastConnectionStore(prefs)
        appliedPresets = AppliedRadioPresetStore(prefs)
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    private fun manager(session: FakeMeshSession) = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { FakeMeshLink() },
        sessionFactory = { session },
        nowEpochSeconds = { 1_000.0 },
        ports = ConnectionManagerPorts(
            newRadioId = { radioId },
            lastConnectionStore = lastConnection,
            appliedRadioPresetStore = appliedPresets,
        ),
    )

    @Test
    fun setBlePinWritesRebootsAndPersists() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)

        manager.setBlePin(246_802)

        assertEquals(246_802L, session.lastDevicePin)
        assertEquals(1, session.rebootCount)
        assertTrue(manager.connectionSettingsSnapshot()!!.isCustomBlePin)
    }

    @Test
    fun setBlePinSwallowsExpectedRebootDisconnect() = runBlocking {
        val session = FakeMeshSession()
        session.failRebootWith = MeshBridgeException(BridgeErrorCode.CONNECTION_LOST, "link dropped")
        val manager = manager(session)
        manager.connect(DEVICE)

        manager.setBlePin(555_555)

        assertEquals(555_555L, session.lastDevicePin)
        assertEquals(555_555L, manager.connectionSettingsSnapshot()!!.blePin)
    }

    @Test
    fun setBlePinRethrowsUnexpectedRebootFailure() = runBlocking {
        val session = FakeMeshSession()
        session.failRebootWith = MeshBridgeException(BridgeErrorCode.DEVICE_ERROR, "boom")
        val manager = manager(session)
        manager.connect(DEVICE)

        assertFailsWith<MeshBridgeException> { manager.setBlePin(555_555) }
        Unit
    }

    @Test
    fun rebootDeviceSwallowsExpectedTimeout() = runBlocking {
        val session = FakeMeshSession()
        session.failRebootWith = MeshBridgeException(BridgeErrorCode.TIMEOUT, "no ack")
        val manager = manager(session)
        manager.connect(DEVICE)

        manager.rebootDevice()

        assertEquals(1, session.rebootCount)
    }

    @Test
    fun rebootDeviceSwallowsExpectedConnectionLost() = runBlocking {
        val session = FakeMeshSession()
        session.failRebootWith = MeshBridgeException(BridgeErrorCode.CONNECTION_LOST, "link dropped")
        val manager = manager(session)
        manager.connect(DEVICE)

        manager.rebootDevice()

        assertEquals(1, session.rebootCount)
    }

    @Test
    fun rebootDeviceRethrowsUnexpectedFailure() = runBlocking {
        val session = FakeMeshSession()
        session.failRebootWith = MeshBridgeException(BridgeErrorCode.DEVICE_ERROR, "boom")
        val manager = manager(session)
        manager.connect(DEVICE)

        assertFailsWith<MeshBridgeException> { manager.rebootDevice() }
        Unit
    }

    @Test
    fun revertToDefaultPinRebootsAndClearsCustomFlag() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)
        manager.setBlePin(246_802)
        assertTrue(manager.connectionSettingsSnapshot()!!.isCustomBlePin)

        manager.setBlePin(ConnectionSettingsSnapshot.BLE_PIN_FIRMWARE_DEFAULT)

        assertEquals(ConnectionSettingsSnapshot.BLE_PIN_FIRMWARE_DEFAULT, session.lastDevicePin)
        assertEquals(2, session.rebootCount)
        assertEquals(false, manager.connectionSettingsSnapshot()!!.isCustomBlePin)
    }

    @Test
    fun setTelemetryModesWritesAllThreeAndPersists() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)

        manager.setTelemetryModes(
            base = DeviceOtherParamsSnapshot.TELEMETRY_MODE_TRUSTED_ONLY,
            location = DeviceOtherParamsSnapshot.TELEMETRY_MODE_TRUSTED_ONLY,
            environment = DeviceOtherParamsSnapshot.TELEMETRY_MODE_OFF,
        )

        val recorded = session.lastOtherParams!!
        assertEquals(DeviceOtherParamsSnapshot.TELEMETRY_MODE_TRUSTED_ONLY, recorded.telemetryModeBase)
        assertEquals(DeviceOtherParamsSnapshot.TELEMETRY_MODE_TRUSTED_ONLY, recorded.telemetryModeLocation)
        assertEquals(DeviceOtherParamsSnapshot.TELEMETRY_MODE_OFF, recorded.telemetryModeEnvironment)
        val snap = manager.deviceOtherParamsSnapshot()!!
        assertEquals(DeviceOtherParamsSnapshot.TELEMETRY_MODE_TRUSTED_ONLY, snap.telemetryModeBase)
        assertEquals(DeviceOtherParamsSnapshot.TELEMETRY_MODE_OFF, snap.telemetryModeEnvironment)
    }

    @Test
    fun aRefusedSelfInfoReadStillWritesFromTheDeviceRow() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)
        session.selfInfoFailure = IllegalStateException("radio down")
        session.selfInfoFailuresRemaining = 1
        val readsBefore = session.selfInfoReadCount

        manager.setTelemetryModes(
            base = DeviceOtherParamsSnapshot.TELEMETRY_MODE_TRUSTED_ONLY,
            location = DeviceOtherParamsSnapshot.TELEMETRY_MODE_TRUSTED_ONLY,
            environment = DeviceOtherParamsSnapshot.TELEMETRY_MODE_OFF,
        )

        assertEquals(readsBefore + 2, session.selfInfoReadCount)
        assertEquals(
            DeviceOtherParamsSnapshot.TELEMETRY_MODE_TRUSTED_ONLY,
            session.lastOtherParams!!.telemetryModeBase,
        )
        assertEquals(
            DeviceOtherParamsSnapshot.TELEMETRY_MODE_TRUSTED_ONLY,
            manager.deviceOtherParamsSnapshot()!!.telemetryModeBase,
        )
    }

    @Test
    fun aCancelledSelfInfoReadStopsTheOtherParamsWrite() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)
        val cancelled = CancellationException("stopped")
        session.selfInfoFailure = cancelled
        session.selfInfoFailuresRemaining = 1

        val thrown = assertFailsWith<CancellationException> {
            manager.setTelemetryModes(
                base = DeviceOtherParamsSnapshot.TELEMETRY_MODE_TRUSTED_ONLY,
                location = DeviceOtherParamsSnapshot.TELEMETRY_MODE_TRUSTED_ONLY,
                environment = DeviceOtherParamsSnapshot.TELEMETRY_MODE_OFF,
            )
        }

        assertSame(cancelled, thrown)
        assertNull(session.lastOtherParams)
    }

    @Test
    fun aSelfInfoReadErrorStopsTheOtherParamsWrite() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)
        val probe = OtherParamsSelfInfoProbeError()
        session.selfInfoFailure = probe
        session.selfInfoFailuresRemaining = 1

        val thrown = assertFailsWith<OtherParamsSelfInfoProbeError> {
            manager.setTelemetryModes(
                base = DeviceOtherParamsSnapshot.TELEMETRY_MODE_TRUSTED_ONLY,
                location = DeviceOtherParamsSnapshot.TELEMETRY_MODE_TRUSTED_ONLY,
                environment = DeviceOtherParamsSnapshot.TELEMETRY_MODE_OFF,
            )
        }

        assertSame(probe, thrown)
        assertNull(session.lastOtherParams)
    }

    @Test
    fun setMultiAcksWritesAndPersists() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)

        manager.setMultiAcks(1)

        assertEquals(1, session.lastOtherParams!!.multiAcks)
        assertEquals(1, manager.deviceOtherParamsSnapshot()!!.multiAcks)
    }

    @Test
    fun setPathHashModeWritesAndPersists() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)

        manager.setPathHashMode(2)

        assertEquals(2, session.lastPathHashMode)
        val snap = manager.deviceOtherParamsSnapshot()!!
        assertEquals(2, snap.pathHashMode)
        assertTrue(snap.supportsPathHashMode)
    }

    @Test
    fun applyRadioPresetWritesPathHashWhenCatalogCarriesOne() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)

        manager.applyRadioPreset(RadioPresets.all.first { it.id == "hu" })

        assertEquals(1, session.lastPathHashMode)
        assertEquals(1, manager.deviceOtherParamsSnapshot()!!.pathHashMode)
    }

    @Test
    fun applyRadioPresetSkipsPathHashWhenCatalogHasNone() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)

        manager.applyRadioPreset(RadioPresets.all.first { it.id == "us-ca" })

        assertNull(session.lastPathHashMode)
    }

    @Test
    fun applyRadioPresetSkipsPathHashOnFirmwareBelowTen() = runBlocking {
        val session = FakeMeshSession(capabilities = defaultCapabilities().copy(firmwareVersion = 9))
        val manager = manager(session)
        manager.connect(DEVICE)

        manager.applyRadioPreset(RadioPresets.all.first { it.id == "hu" })

        assertNull(session.lastPathHashMode)
    }

    @Test
    fun applyRadioPresetStampsTheCatalogIdOnTheSnapshot() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)

        manager.applyRadioPreset(RadioPresets.all.first { it.id == "br" })

        assertEquals("br", manager.radioSnapshot()!!.appliedRadioPresetID)
        assertEquals("br", appliedPresets.get(radioId))
        val rowId = requireNotNull(lastConnection.deviceId())
        assertEquals("br", db.deviceDao().getById(rowId)!!.appliedRadioPresetID)
    }

    @Test
    fun aFailedPresetPathHashDoesNotPersistRfOrTheCatalogId() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)
        val br = RadioPresets.all.first { it.id == "br" }
        manager.applyRadioPreset(br)
        session.refusePathHashModeWrite = true

        assertFailsWith<PathHashModeVerificationFailed> {
            manager.applyRadioPreset(RadioPresets.all.first { it.id == "hu" })
        }

        val snap = manager.radioSnapshot()!!
        assertEquals("br", snap.appliedRadioPresetID)
        assertEquals(br.frequencyKHz, snap.frequencyKHz)
        assertEquals("br", appliedPresets.get(radioId))
    }

    @Test
    fun aManualLoRaWriteDropsAStaleCatalogId() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)
        manager.applyRadioPreset(RadioPresets.all.first { it.id == "br" })

        val us = RadioPresets.all.first { it.id == "us-ca" }
        manager.applyManualRadioParams(
            ManualRadioParams(
                frequencyMHz = us.frequencyMHz,
                bandwidthKHz = us.bandwidthKHz,
                spreadingFactor = us.spreadingFactor,
                codingRate = us.codingRate,
                txPower = null,
            ),
        )

        assertNull(manager.radioSnapshot()!!.appliedRadioPresetID)
        assertNull(appliedPresets.get(radioId))
    }

    @Test
    fun aRefusedTelemetryWriteThrowsRatherThanClaimingTheNewModes() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)
        session.refuseOtherParamsWrite = true

        assertFailsWith<OtherParamsVerificationFailed> {
            manager.setTelemetryModes(
                base = DeviceOtherParamsSnapshot.TELEMETRY_MODE_EVERYONE,
                location = DeviceOtherParamsSnapshot.TELEMETRY_MODE_EVERYONE,
                environment = DeviceOtherParamsSnapshot.TELEMETRY_MODE_EVERYONE,
            )
        }
        val snap = manager.deviceOtherParamsSnapshot()!!
        assertEquals(DeviceOtherParamsSnapshot.TELEMETRY_MODE_OFF, snap.telemetryModeBase)
    }

    @Test
    fun aRefusedMultiAckWriteThrowsRatherThanClaimingTheNewCount() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)
        session.refuseOtherParamsWrite = true

        assertFailsWith<OtherParamsVerificationFailed> { manager.setMultiAcks(1) }
        assertEquals(0, manager.deviceOtherParamsSnapshot()!!.multiAcks)
    }

    @Test
    fun aRefusedAdvertPolicyWriteThrowsRatherThanClaimingTheNodeStoppedSharing() = runBlocking {
        val session = FakeMeshSession(selfInfo = defaultSelfInfo().copy(advertisementLocationPolicy = SHARING_POLICY))
        val manager = manager(session)
        manager.connect(DEVICE)
        session.refuseOtherParamsWrite = true

        assertFailsWith<OtherParamsVerificationFailed> { manager.setAdvertLocationPolicy(AdvertLocationPolicy.NONE) }
        // The node is still advertising its position; the row must not claim otherwise.
        assertEquals(SHARING_POLICY, manager.locationSettingsSnapshot()!!.advertLocationPolicyRaw)
    }

    @Test
    fun aRefusedPathHashModeWriteThrowsRatherThanWideningTheCachedHopWidth() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)
        session.refusePathHashModeWrite = true

        assertFailsWith<PathHashModeVerificationFailed> { manager.setPathHashMode(2) }
        assertEquals(0, manager.deviceOtherParamsSnapshot()!!.pathHashMode)
    }

    @Test
    fun applyRepeatFrequencyRetunesFrequencyOnlyWithRepeatOn() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session)
        manager.connect(DEVICE)
        val before = manager.radioSnapshot()!!

        manager.applyRepeatFrequency(repeatPresetFrequencyKHz = 867_500)

        val radio = session.lastRadio!!
        assertEquals(867.5, radio.frequency)
        // Bandwidth / SF / CR carry over from the device row; only the frequency retunes.
        assertEquals(before.bandwidthHz / 1000.0, radio.bandwidth)
        assertEquals(before.spreadingFactor, radio.spreadingFactor)
        assertEquals(before.codingRate, radio.codingRate)
        assertEquals(true, radio.clientRepeat)
    }

    @Test
    fun otherParamsSnapshotNullWhenDisconnected() = runBlocking {
        val manager = manager(FakeMeshSession())
        assertNull(manager.deviceOtherParamsSnapshot())
    }

    private companion object {
        const val DEVICE = "device-1"

        /** A radio that is advertising its position, so a refused "stop sharing" write is visible. */
        const val SHARING_POLICY = 2
    }
}

private class OtherParamsSelfInfoProbeError : Error("probe")
