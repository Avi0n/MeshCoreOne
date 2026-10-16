package one.meshcore.shared.chat.pathdetail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A duplicate-check fault still counts the echo as already recorded. Cancellation and an error leave
 * the check.
 */
class HeardRepeatsDuplicateTest {
    private class DuplicateProbeError : Error("probe")

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCancelledDuplicateCheckPropagates() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val stopped = CancellationException("stopped")
        val service = duplicateService(recorded) { throw stopped }
        service.configure(radioID = ChatFixtures.RADIO)

        val cancelled = assertFailsWith<CancellationException> {
            service.processForRepeats(echo())
        }

        assertSame(stopped, cancelled)
        assertTrue(recorded.none { it.message.contains("Failed to check for existing repeat") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aDuplicateCheckErrorPropagates() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val probe = DuplicateProbeError()
        val service = duplicateService(recorded) { throw probe }
        service.configure(radioID = ChatFixtures.RADIO)

        val thrown = assertFailsWith<DuplicateProbeError> { service.processForRepeats(echo()) }
        assertSame(probe, thrown)
        assertTrue(recorded.none { it.message.contains("Failed to check for existing repeat") })
    }

    private fun echo(): HeardRepeatEcho = HeardRepeatEcho(
        id = ChatFixtures.freshUuid(),
        isGroupText = true,
        isDecryptSuccess = true,
        decodedText = "TestNode: hello",
        channelIndex = 0,
        senderTimestamp = 1_700_000_000L,
        receivedAt = 1_700_000_100.0,
        pathNodes = byteArrayOf(0x42),
        pathLength = 0x01,
        snr = 8.0,
        rssi = -70,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.duplicateService(
        recorded: MutableList<DebugLogEntryDto>,
        exists: suspend (Uuid) -> Boolean,
    ): HeardRepeatsService {
        val backing = InMemoryHeardRepeatStore()
        val throwing = object : HeardRepeatStore by backing {
            override suspend fun messageRepeatExists(rxLogEntryID: Uuid): Boolean = exists(rxLogEntryID)
        }
        return HeardRepeatsService(
            store = throwing,
            newRepeatId = { ChatFixtures.freshUuid() },
            logger = PersistentLogger(
                scope = this,
                category = "HeardRepeatsService",
                record = { recorded += it },
            ),
        )
    }
}
