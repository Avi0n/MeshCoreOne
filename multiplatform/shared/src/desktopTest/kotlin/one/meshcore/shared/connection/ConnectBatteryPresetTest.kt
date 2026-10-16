package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.battery.BatteryOcv
import one.meshcore.shared.battery.BatteryThresholdMonitor
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The connect path must seed the device-specific battery curve from the model string the radio reports,
 * mirroring the Swift `ocvPreset: existingDevice?.ocvPreset ?? OCVPreset.preset(forManufacturer:)` write.
 * Without it a two-cell radio reads full forever and the low-battery notification can never fire.
 */
class ConnectBatteryPresetTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    private val fixedRadioId = Uuid.normalized("A0000000-0000-0000-0000-000000000031")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-battery-preset", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    @Test
    fun connectSeedsTheModelCurveSoALowTwoCellPackRaisesTheAlert() = runBlocking {
        val manager = manager(model = TWO_CELL_MODEL)

        manager.connect(DEVICE)

        val row = db.deviceDao().listAll().single()
        assertEquals(OCVPreset.LILY_GO_T_BEAM_1W.rawValue, row.ocvPreset)

        // The curve `AppStateViewModel.connectedDeviceOcvArray` resolves from that same row.
        val curve = BatteryOcv.activeArray(row.ocvPreset, row.customOCVArrayString)
        assertEquals(OCVPreset.LILY_GO_T_BEAM_1W.ocvArray, curve)

        // End of the path: the percentage the battery poller publishes, and the threshold monitor behind
        // the low-battery notification.
        val percentage = BatteryOcv.percentage(LOW_TWO_CELL_MILLIVOLTS, curve)
        assertTrue(
            percentage < BatteryThresholdMonitor.WARNING_THRESHOLDS.first(),
            "expected a low reading, got $percentage%",
        )
        assertTrue(
            BatteryThresholdMonitor().check(percentage, isBatteryPresent = true),
            "the low-battery alert must fire",
        )

        // The contrast: on the unseeded Li-Ion default the same pack reads full and nothing ever fires.
        val defaultPercentage = BatteryOcv.percentage(LOW_TWO_CELL_MILLIVOLTS, OCVPreset.LI_ION.ocvArray)
        assertEquals(FULL_PERCENT, defaultPercentage)
        assertFalse(BatteryThresholdMonitor().check(defaultPercentage, isBatteryPresent = true))
    }

    @Test
    fun connectKeepsTheCurveTheUserAlreadyChose() = runBlocking {
        val manager = manager(model = TWO_CELL_MODEL)
        manager.connect(DEVICE)

        // The user picks a different chemistry in Settings → Advanced.
        val seeded = db.deviceDao().listAll().single()
        db.deviceDao().upsert(seeded.copy(ocvPreset = OCVPreset.NI_MH.rawValue))
        manager.disconnect()

        manager.connect(DEVICE)

        assertEquals(OCVPreset.NI_MH.rawValue, db.deviceDao().listAll().single().ocvPreset)
    }

    @Test
    fun anUnrecognisedModelLeavesTheCurveUnset() = runBlocking {
        val manager = manager(model = NRF52_MODEL)

        manager.connect(DEVICE)

        assertNull(db.deviceDao().listAll().single().ocvPreset)
    }

    private fun manager(model: String): ConnectionManager = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { FakeMeshLink() },
        sessionFactory = { FakeMeshSession(capabilities = defaultCapabilities().copy(model = model)) },
        nowEpochSeconds = { FIXED_CLOCK_SECONDS },
        ports = ConnectionManagerPorts(
            newRadioId = { fixedRadioId },
            nodePasswordStore = InMemoryNodePasswordStore(),
        ),
    )

    private companion object {
        const val DEVICE = "device-battery"
        const val FIXED_CLOCK_SECONDS = 1_000.0
        const val FULL_PERCENT = 100

        /** A `getManufacturerName()` string the firmware reports for a 2S pack (7950→6000 mV curve). */
        const val TWO_CELL_MODEL = "LilyGo T-Beam 1W"

        /** Deep into the 2S curve's low end, and far above every single-cell curve's full mark. */
        const val LOW_TWO_CELL_MILLIVOLTS = 6800
    }
}
