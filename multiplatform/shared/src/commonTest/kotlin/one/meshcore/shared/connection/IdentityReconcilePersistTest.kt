package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityReconcilePersistTest {
    @Test
    fun selfInfoFaultRecordsIosDumpAndReturnsNull() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val info = readSelfInfoForReconcile(
            getSelfInfo = { error("no self info") },
            logger = connectionManagerLogger(this, recorded),
        )
        assertNull(info)
        val line = recorded.single { it.message.contains(RECONCILE_SELF_INFO_NIL) }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("ConnectionManager", line.category)
    }

    @Test
    fun aCancelledSelfInfoReadStopsReconcile() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            readSelfInfoForReconcile(
                getSelfInfo = { throw cancelled },
                logger = connectionManagerLogger(this, recorded),
            )
        }
        assertSame(cancelled, thrown)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun aSelfInfoReadErrorStillKeepsTheLivePartition() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val info = readSelfInfoForReconcile(
            getSelfInfo = { throw ReconcileSelfInfoProbeError() },
            logger = connectionManagerLogger(this, recorded),
        )
        assertNull(info)
        assertEquals(1, recorded.count { it.message.contains(RECONCILE_SELF_INFO_NIL) })
    }

    @Test
    fun aSelfInfoReadReturnsTheRadioSnapshot() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val sample = reconcileSelfInfo()
        val info = readSelfInfoForReconcile(getSelfInfo = { sample }, logger = connectionManagerLogger(this, recorded))
        assertSame(sample, info)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun persistFaultRecordsIosDumpAndReturnsFalse() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val ok = applyGhostReconcilePersist(
            apply = { error("db locked") },
            logger = connectionManagerLogger(this, recorded),
        )
        assertFalse(ok)
        val line = recorded.single { it.message.contains(RECONCILE_GHOST_FAILED_PREFIX) }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun persistSuccessReturnsTrueWithoutADump() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val ok = applyGhostReconcilePersist(
            apply = { },
            logger = connectionManagerLogger(this, recorded),
        )
        assertTrue(ok)
        assertTrue(recorded.none { it.message.contains(RECONCILE_GHOST_FAILED_PREFIX) })
    }

    @Test
    fun aCancelledGhostPersistStopsReconcile() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            applyGhostReconcilePersist(apply = { throw cancelled }, logger = connectionManagerLogger(this, recorded))
        }
        assertSame(cancelled, thrown)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun aGhostPersistErrorStillLetsImportContinue() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val ok = applyGhostReconcilePersist(
            apply = { throw ReconcilePersistProbeError() },
            logger = connectionManagerLogger(this, recorded),
        )
        assertFalse(ok)
        val line = recorded.single { it.message.contains(RECONCILE_GHOST_FAILED_PREFIX) }
        assertTrue(line.message.contains("probe"))
    }
}

private fun reconcileSelfInfo(): SelfInfo = SelfInfo(
    advertisementType = 1,
    txPower = 22,
    maxTxPower = 22,
    publicKey = byteArrayOf(1),
    latitude = 0.0,
    longitude = 0.0,
    multiAcks = 0,
    advertisementLocationPolicy = 0,
    telemetryModeEnvironment = 0,
    telemetryModeLocation = 0,
    telemetryModeBase = 0,
    manualAddContacts = false,
    radioFrequency = 915.0,
    radioBandwidth = 250.0,
    radioSpreadingFactor = 7,
    radioCodingRate = 5,
    name = "Ridge",
)

private class ReconcileSelfInfoProbeError : Error("probe")

private class ReconcilePersistProbeError : Error("probe")
