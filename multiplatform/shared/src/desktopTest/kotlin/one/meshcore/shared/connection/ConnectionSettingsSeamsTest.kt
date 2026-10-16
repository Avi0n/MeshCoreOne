package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.settings.AdvertLocationPolicy
import one.meshcore.shared.settings.ManualRadioFormTexts
import one.meshcore.shared.settings.ManualRadioParams
import one.meshcore.shared.settings.ManualRadioParseResult
import one.meshcore.shared.settings.parseManualRadioParams
import one.meshcore.shared.settings.preRepeatFrequencyMHz
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The Settings seams the host reads through: the radio's own TX-power ceiling and saved pre-repeat
 * frequency reaching the Advanced form, the advert policy reaching the wire unflattened, the repeat
 * transition saving and clearing pre-repeat parameters, and the verified node rename.
 */
class ConnectionSettingsSeamsTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var lastConnection: LastConnectionStore

    private val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000042")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-settings-seams", ".db").also { it.delete() }
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

    private fun manager(session: FakeMeshSession) = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { FakeMeshLink() },
        sessionFactory = { session },
        nowEpochSeconds = { 1_000.0 },
        ports = ConnectionManagerPorts(
            newRadioId = { radioId },
            lastConnectionStore = lastConnection,
        ),
    )

    private suspend fun connected(session: FakeMeshSession): ConnectionManager =
        manager(session).also { it.connect(DEVICE) }

    @Test
    fun advancedFormRejectsTxPowerTheConnectedRadioCannotProduce() {
        runBlocking {
            val lowCeiling = FakeMeshSession(selfInfo = defaultSelfInfo().copy(maxTxPower = LOW_CEILING_DBM))
            val manager = connected(lowCeiling)

            val snapshot = manager.radioSnapshot()!!
            assertEquals(LOW_CEILING_DBM, snapshot.maxTxPower)
            assertIs<ManualRadioParseResult.Invalid>(parseFormAtTxPower("20", snapshot.maxTxPower))
            assertIs<ManualRadioParseResult.Ok>(parseFormAtTxPower("14", snapshot.maxTxPower))
        }
    }

    @Test
    fun advancedFormAcceptsTxPowerUpToAHigherRatedRadiosCeiling() {
        runBlocking {
            val manager = connected(FakeMeshSession())

            val snapshot = manager.radioSnapshot()!!
            assertEquals(HIGH_CEILING_DBM, snapshot.maxTxPower)
            assertIs<ManualRadioParseResult.Ok>(parseFormAtTxPower("20", snapshot.maxTxPower))
        }
    }

    @Test
    fun advertPolicyReachesTheWireWithoutDegradingToStoredCoordinates() = runBlocking {
        val session = FakeMeshSession()
        val manager = connected(session)

        manager.setAdvertLocationPolicy(AdvertLocationPolicy.SHARE)

        assertEquals(AdvertLocationPolicy.SHARE.rawValue, session.lastOtherParams!!.advertisementLocationPolicy)
        assertEquals(AdvertLocationPolicy.SHARE.rawValue, manager.locationSettingsSnapshot()!!.advertLocationPolicyRaw)
    }

    @Test
    fun theBooleanSeamStillCannotExpressTheLiveFix() = runBlocking {
        val session = FakeMeshSession()
        val manager = connected(session)

        manager.setShareLocationPublicly(true)

        assertEquals(AdvertLocationPolicy.PREFS.rawValue, session.lastOtherParams!!.advertisementLocationPolicy)
    }

    @Test
    fun aRefusedRepeatModeFailsVerificationInsteadOfBeingPersisted() = runBlocking {
        val session = FakeMeshSession()
        // The radio keeps repeat off however it is asked, as firmware that refuses the mode does.
        session.reportedClientRepeat = false
        val manager = connected(session)

        assertFailsWith<RadioParamsVerificationFailed> {
            manager.applyManualRadioParams(
                ManualRadioParams(
                    frequencyMHz = REPEAT_FREQUENCY_MHZ,
                    bandwidthKHz = DEFAULT_BANDWIDTH_KHZ,
                    spreadingFactor = DEFAULT_SPREADING_FACTOR,
                    codingRate = DEFAULT_CODING_RATE,
                    txPower = null,
                    clientRepeat = true,
                ),
            )
        }
        // The Device row must not claim a mode the radio never entered.
        assertEquals(false, manager.radioSnapshot()?.clientRepeat)
    }

    @Test
    fun aRefusedTxPowerFailsVerificationInsteadOfBeingPersisted() = runBlocking {
        val session = FakeMeshSession()
        session.refuseTxPowerWrite = true
        val manager = connected(session)
        val before = checkNotNull(manager.radioSnapshot()?.txPower)

        assertFailsWith<RadioParamsVerificationFailed> {
            manager.applyManualRadioParams(
                ManualRadioParams(
                    frequencyMHz = DEFAULT_FREQUENCY_MHZ,
                    bandwidthKHz = DEFAULT_BANDWIDTH_KHZ,
                    spreadingFactor = DEFAULT_SPREADING_FACTOR,
                    codingRate = DEFAULT_CODING_RATE,
                    txPower = before + 1,
                ),
            )
        }
        assertEquals(before, manager.radioSnapshot()?.txPower)
    }

    @Test
    fun aRefusedTxPowerStillPersistsTheRadioParamsThatLanded() = runBlocking {
        val session = FakeMeshSession()
        val manager = connected(session)
        val before = checkNotNull(manager.radioSnapshot()?.txPower)
        session.refuseTxPowerWrite = true

        assertFailsWith<RadioParamsVerificationFailed> {
            manager.applyManualRadioParams(
                ManualRadioParams(
                    frequencyMHz = REPEAT_FREQUENCY_MHZ,
                    bandwidthKHz = DEFAULT_BANDWIDTH_KHZ,
                    spreadingFactor = DEFAULT_SPREADING_FACTOR,
                    codingRate = DEFAULT_CODING_RATE,
                    txPower = before + 1,
                ),
            )
        }
        val snapshot = manager.radioSnapshot()!!
        assertEquals(REPEAT_FREQUENCY_KHZ, snapshot.frequencyKHz)
        assertEquals(before, snapshot.txPower)
        assertNull(session.lastTxPower)
    }

    @Test
    fun setTxPowerVerifiedDoesNotRetuneLoRaParameters() = runBlocking {
        val session = FakeMeshSession()
        val manager = connected(session)
        val radioAfterConnect = session.lastRadio
        val nextPower = checkNotNull(manager.radioSnapshot()?.txPower) + 1

        manager.setTxPowerVerified(nextPower)

        assertEquals(radioAfterConnect, session.lastRadio)
        assertEquals(nextPower, session.lastTxPower)
        assertEquals(nextPower, manager.radioSnapshot()?.txPower)
    }

    @Test
    fun aRefusedTxPowerOnlyWriteFailsVerificationInsteadOfBeingPersisted() = runBlocking {
        val session = FakeMeshSession()
        session.refuseTxPowerWrite = true
        val manager = connected(session)
        val before = checkNotNull(manager.radioSnapshot()?.txPower)

        assertFailsWith<RadioParamsVerificationFailed> {
            manager.setTxPowerVerified(before + 1)
        }
        assertEquals(before, manager.radioSnapshot()?.txPower)
        assertNull(session.lastTxPower)
    }

    @Test
    fun enteringRepeatModeFromTheFormSavesTheParametersInUse() = runBlocking {
        val manager = connected(FakeMeshSession())

        manager.applyManualRadioParams(
            ManualRadioParams(
                frequencyMHz = REPEAT_FREQUENCY_MHZ,
                bandwidthKHz = DEFAULT_BANDWIDTH_KHZ,
                spreadingFactor = DEFAULT_SPREADING_FACTOR,
                codingRate = DEFAULT_CODING_RATE,
                txPower = null,
                clientRepeat = true,
            ),
        )

        val snapshot = manager.radioSnapshot()!!
        assertEquals(DEFAULT_FREQUENCY_KHZ, snapshot.preRepeatFrequencyKHz)
        assertEquals(DEFAULT_FREQUENCY_MHZ, snapshot.preRepeatFrequencyMHz())
        assertEquals(REPEAT_FREQUENCY_KHZ, snapshot.frequencyKHz)
    }

    @Test
    fun leavingRepeatModeFromTheFormClearsTheSavedParameters() = runBlocking {
        val manager = connected(FakeMeshSession())
        manager.applyManualRadioParams(
            ManualRadioParams(
                frequencyMHz = REPEAT_FREQUENCY_MHZ,
                bandwidthKHz = DEFAULT_BANDWIDTH_KHZ,
                spreadingFactor = DEFAULT_SPREADING_FACTOR,
                codingRate = DEFAULT_CODING_RATE,
                txPower = null,
                clientRepeat = true,
            ),
        )

        val restored = manager.radioSnapshot()!!.preRepeatFrequencyMHz()
        manager.applyManualRadioParams(
            ManualRadioParams(
                frequencyMHz = restored,
                bandwidthKHz = DEFAULT_BANDWIDTH_KHZ,
                spreadingFactor = DEFAULT_SPREADING_FACTOR,
                codingRate = DEFAULT_CODING_RATE,
                txPower = null,
                clientRepeat = false,
            ),
        )

        val snapshot = manager.radioSnapshot()!!
        assertNull(snapshot.preRepeatFrequencyKHz)
        assertEquals(DEFAULT_FREQUENCY_KHZ, snapshot.frequencyKHz)
        assertEquals(false, snapshot.clientRepeat)
    }

    @Test
    fun retuningWithinRepeatModeKeepsTheOriginalPreRepeatFrequency() = runBlocking {
        val manager = connected(FakeMeshSession())
        manager.enableClientRepeat(REPEAT_FREQUENCY_KHZ)

        manager.applyRepeatFrequency(OTHER_REPEAT_FREQUENCY_KHZ)

        assertEquals(DEFAULT_FREQUENCY_KHZ, manager.radioSnapshot()!!.preRepeatFrequencyKHz)
    }

    @Test
    fun leavingRepeatModeRestoresTheFrequencyFromBeforeRepeatWasEntered() = runBlocking {
        val session = FakeMeshSession()
        val manager = connected(session)
        manager.enableClientRepeat(REPEAT_FREQUENCY_KHZ)

        manager.disableClientRepeat()

        val radio = session.lastRadio!!
        assertEquals(DEFAULT_FREQUENCY_MHZ, radio.frequency)
        assertEquals(false, radio.clientRepeat)
        val snapshot = manager.radioSnapshot()!!
        assertEquals(DEFAULT_FREQUENCY_KHZ, snapshot.frequencyKHz)
        assertNull(snapshot.preRepeatFrequencyKHz)
    }

    @Test
    fun leavingRepeatModeWithNothingSavedFallsBackToCurrentParams() = runBlocking {
        // A radio put into repeat mode outside the app: it reports repeat mode at connect, but no
        // pre-repeat parameters were ever saved. iOS `disableRepeatMode` writes the current params
        // with clientRepeat off rather than refusing the toggle.
        val session = FakeMeshSession(capabilities = defaultCapabilities().copy(clientRepeat = true))
        val manager = connected(session)

        manager.disableClientRepeat()

        val radio = session.lastRadio!!
        assertEquals(DEFAULT_FREQUENCY_MHZ, radio.frequency)
        assertEquals(false, radio.clientRepeat)
        val snapshot = manager.radioSnapshot()!!
        assertEquals(DEFAULT_FREQUENCY_KHZ, snapshot.frequencyKHz)
        assertEquals(false, snapshot.clientRepeat)
        assertNull(snapshot.preRepeatFrequencyKHz)
    }

    @Test
    fun aPickedLocationPersistsOnTheDeviceRow() = runBlocking {
        val manager = connected(FakeMeshSession())
        assertEquals(0.0, manager.locationSettingsSnapshot()!!.latitude)
        assertEquals(0.0, manager.locationSettingsSnapshot()!!.longitude)

        manager.persistDeviceCoordinates(PICKED_LATITUDE, PICKED_LONGITUDE)

        val snap = manager.locationSettingsSnapshot()!!
        assertEquals(PICKED_LATITUDE, snap.latitude)
        assertEquals(PICKED_LONGITUDE, snap.longitude)
    }

    @Test
    fun renamingTheNodeWritesTheClampedNameAndPersistsIt() = runBlocking {
        val session = FakeMeshSession()
        val manager = connected(session)

        manager.setNodeName(OVERLONG_NAME)

        val clamped = OVERLONG_NAME.take(MAX_NAME_CHARS)
        assertEquals(clamped, session.lastSetName)
        assertEquals(clamped, db.deviceDao().getById(manager.connectedDeviceRowId()!!)!!.nodeName)
        assertEquals(clamped, lastConnection.deviceName())
    }

    @Test
    fun aRefusedRenameThrowsRatherThanClaimingTheNewName() = runBlocking {
        val session = FakeMeshSession()
        val manager = connected(session)
        session.refuseNameWrite = true

        assertFailsWith<NodeNameVerificationFailed> { manager.setNodeName("Ridge Relay") }
        assertEquals("Test", db.deviceDao().getById(manager.connectedDeviceRowId()!!)!!.nodeName)
    }

    private fun parseFormAtTxPower(txPower: String, maxTxPower: Int) = parseManualRadioParams(
        ManualRadioFormTexts(
            frequencyMHz = DEFAULT_FREQUENCY_MHZ.toString(),
            bandwidthKHz = DEFAULT_BANDWIDTH_KHZ.toString(),
            spreadingFactor = DEFAULT_SPREADING_FACTOR.toString(),
            codingRate = DEFAULT_CODING_RATE.toString(),
            txPower = txPower,
        ),
        maxTxPower = maxTxPower,
    )

    private companion object {
        const val DEVICE = "AA:BB:CC:DD:EE:FF"

        /** The canned handshake radio parameters of [defaultSelfInfo]. */
        const val DEFAULT_FREQUENCY_MHZ = 915.0
        const val DEFAULT_FREQUENCY_KHZ = 915_000L
        const val DEFAULT_BANDWIDTH_KHZ = 250.0
        const val DEFAULT_SPREADING_FACTOR = 7
        const val DEFAULT_CODING_RATE = 5

        const val REPEAT_FREQUENCY_MHZ = 869.525
        const val REPEAT_FREQUENCY_KHZ = 869_525L
        const val OTHER_REPEAT_FREQUENCY_KHZ = 867_500L

        /** A radio rated well below the old fixed 22 dBm ceiling, and one rated at it. */
        const val LOW_CEILING_DBM = 14
        const val HIGH_CEILING_DBM = 22

        const val MAX_NAME_CHARS = 31
        const val OVERLONG_NAME = "Ridge Relay North Station Alpha Bravo"

        const val PICKED_LATITUDE = 37.7749
        const val PICKED_LONGITUDE = -122.4194
    }
}
