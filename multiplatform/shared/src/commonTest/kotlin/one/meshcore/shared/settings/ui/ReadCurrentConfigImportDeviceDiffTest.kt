package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.NodeConfigService
import one.meshcore.shared.settings.NodeConfigSession
import one.meshcore.shared.settings.TelemetryModes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReadCurrentConfigImportDeviceDiffTest {
    @Test
    fun currentProviderPublishesItsActualSelfInfo() = runTest {
        val session = HeldSelfInfo()
        session.reply.complete(selfInfo())
        val service = NodeConfigService(session)
        val diff = readCurrentConfigImportDeviceDiff({ service }, { RADIO })
        assertEquals("Current", assertNotNull(diff).name)
        assertEquals(1, session.reads)
    }

    @Test
    fun aRetainedComparisonIsHiddenWhenItsLiveOwnerChanges() = runTest {
        for (change in Change.entries) {
            val session = HeldSelfInfo()
            session.reply.complete(selfInfo())
            val original = NodeConfigService(session)
            var service: NodeConfigService? = original
            var radio: Uuid? = RADIO
            val state = NodeConfigImportState()
            state.importedConfig = one.meshcore.shared.settings.MeshCoreNodeConfig(name = "Selected")
            state.deviceDiff = readCurrentConfigImportDeviceDiff({ service }, { radio })
            val diff = assertNotNull(state.deviceDiff)
            assertTrue(diff.isCurrent(), change.name)
            assertEquals("Current", diff.name)
            when (change) {
                Change.DISCONNECT -> service = null
                Change.SERVICE -> service = NodeConfigService(HeldSelfInfo())
                Change.RADIO -> radio = OTHER_RADIO
            }
            assertFalse(diff.isCurrent(), change.name)
            assertNull(state.currentDeviceDiff, change.name)
            assertEquals("Selected", state.importedConfig?.name)
            service = NodeConfigService(HeldSelfInfo())
            radio = RADIO
            assertFalse(diff.isCurrent(), "Returning to the same partition with a new service")
            service = original
            assertTrue(diff.isCurrent(), "Exact owner remains valid")
            assertEquals(1, session.reads)
        }
    }

    @Test
    fun missingServiceOrRadioDoesNotReadTheSession() = runTest {
        val session = HeldSelfInfo()
        val service = NodeConfigService(session)
        assertNull(readCurrentConfigImportDeviceDiff({ null }, { RADIO }))
        assertNull(readCurrentConfigImportDeviceDiff({ service }, { null }))
        assertEquals(0, session.reads)
    }

    @Test
    fun changedProviderOrRadioRejectsTheCompletedOldRead() = runTest {
        for (change in Change.entries) {
            val session = HeldSelfInfo()
            val replacement = HeldSelfInfo()
            var service: NodeConfigService? = NodeConfigService(session)
            var radio: Uuid? = RADIO
            val read = async { readCurrentConfigImportDeviceDiff({ service }, { radio }) }
            try {
                runCurrent()
                assertEquals(1, session.reads, change.name)
                assertFalse(read.isCompleted, change.name)
                when (change) {
                    Change.DISCONNECT -> service = null
                    Change.SERVICE -> service = NodeConfigService(replacement)
                    Change.RADIO -> radio = OTHER_RADIO
                }
                session.reply.complete(selfInfo())
                assertNull(read.await(), change.name)
                assertEquals(0, replacement.reads, change.name)
            } finally {
                read.cancelAndJoin()
                session.reply.cancel()
                replacement.reply.cancel()
            }
        }
    }

    @Test
    fun genuineCancellationOfTheReadPropagates() = runTest {
        val session = HeldSelfInfo()
        val service = NodeConfigService(session)
        var returnedNormally = false
        val read = async {
            readConfigImportDeviceDiff { readCurrentConfigImportDeviceDiff({ service }, { RADIO }) }
            returnedNormally = true
        }
        runCurrent()
        assertEquals(1, session.reads)
        read.cancelAndJoin()
        assertTrue(read.isCancelled)
        assertFalse(returnedNormally)
        session.reply.cancel()
    }

    private enum class Change { DISCONNECT, SERVICE, RADIO }

    private class HeldSelfInfo : NodeConfigSession {
        val reply = CompletableDeferred<SelfInfo>()
        var reads = 0

        override suspend fun getSelfInfo(): SelfInfo {
            reads += 1
            return reply.await()
        }

        override suspend fun exportPrivateKey(): ByteArray = error("Unexpected key read")
        override suspend fun queryDevice(): DeviceCapabilities = error("Unexpected capability read")
        override suspend fun getContacts(since: Long?): List<MeshContact> = error("Unexpected contacts read")
        override suspend fun getChannel(index: Int): ChannelInfo = error("Unexpected channel read")
        override suspend fun importPrivateKey(privateKey: ByteArray): Unit = error("Unexpected write")
        override suspend fun setNodeName(name: String): Unit = error("Unexpected write")
        override suspend fun setLocation(latitude: Double, longitude: Double): Unit = error("Unexpected write")
        override suspend fun setOtherParams(
            autoAddContacts: Boolean,
            telemetryModes: TelemetryModes,
            advertLocationPolicyRaw: Int,
            multiAcks: Int,
        ): Unit = error("Unexpected write")
        override suspend fun setRadioParams(
            frequencyKHz: UInt,
            bandwidthKHz: UInt,
            spreadingFactor: UByte,
            codingRate: UByte,
        ): Unit = error("Unexpected write")
        override suspend fun setTxPower(txPower: Byte): Unit = error("Unexpected write")
        override suspend fun setChannel(radioID: Uuid, index: Int, name: String, secret: ByteArray): Unit =
            error("Unexpected write")
        override suspend fun setContact(radioID: Uuid, contact: MeshContact): Unit = error("Unexpected write")
    }

    private fun selfInfo() = SelfInfo(
        advertisementType = 1,
        txPower = 22,
        maxTxPower = 30,
        publicKey = ByteArray(32),
        latitude = 0.0,
        longitude = 0.0,
        multiAcks = 0,
        advertisementLocationPolicy = 0,
        telemetryModeEnvironment = 0,
        telemetryModeLocation = 0,
        telemetryModeBase = 0,
        manualAddContacts = false,
        radioFrequency = 910.525,
        radioBandwidth = 62.5,
        radioSpreadingFactor = 7,
        radioCodingRate = 5,
        name = "Current",
    )

    private companion object {
        val RADIO = Uuid.normalized("C01DF100-0000-4000-8000-000000000001")
        val OTHER_RADIO = Uuid.normalized("C01DF100-0000-4000-8000-000000000002")
    }
}
