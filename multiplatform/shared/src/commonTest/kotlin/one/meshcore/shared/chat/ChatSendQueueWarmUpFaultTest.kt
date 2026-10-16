package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Connect-time queue purge. A store fault, including a thrown error, leaves the rows for the next
 * connect. Cancellation stops the rest of warm-up and keeps the thrown instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendQueueWarmUpFaultTest {
    @Test
    fun anOrphanPurgeExceptionStillPurgesLegacyRows() = runTest(UnconfinedTestDispatcher()) {
        val store = PurgeFaultStore(onOrphan = { error("db locked") })

        queue(this, store).warmUp()

        assertEquals(1, store.orphanCalls)
        assertEquals(1, store.legacyCalls)
    }

    @Test
    fun anOrphanPurgeErrorStillPurgesLegacyRows() = runTest(UnconfinedTestDispatcher()) {
        val store = PurgeFaultStore(onOrphan = { throw WarmUpProbeError() })

        queue(this, store).warmUp()

        assertEquals(1, store.orphanCalls)
        assertEquals(1, store.legacyCalls)
    }

    @Test
    fun aCancelledOrphanPurgeSkipsLegacyRows() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val store = PurgeFaultStore(onOrphan = { throw stopped })

        val cancelled = assertFailsWith<CancellationException> {
            queue(this, store).warmUp()
        }

        assertSame(stopped, cancelled)
        assertEquals(1, store.orphanCalls)
        assertEquals(0, store.legacyCalls)
    }

    @Test
    fun aLegacyPurgeExceptionIsSwallowedAfterOrphans() = runTest(UnconfinedTestDispatcher()) {
        val store = PurgeFaultStore(onLegacy = { error("db locked") })

        queue(this, store).warmUp()

        assertEquals(1, store.orphanCalls)
        assertEquals(1, store.legacyCalls)
    }

    @Test
    fun aLegacyPurgeErrorIsSwallowedAfterOrphans() = runTest(UnconfinedTestDispatcher()) {
        val store = PurgeFaultStore(onLegacy = { throw WarmUpProbeError() })

        queue(this, store).warmUp()

        assertEquals(1, store.orphanCalls)
        assertEquals(1, store.legacyCalls)
    }

    @Test
    fun aCancelledLegacyPurgeKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val store = PurgeFaultStore(onLegacy = { throw stopped })

        val cancelled = assertFailsWith<CancellationException> {
            queue(this, store).warmUp()
        }

        assertSame(stopped, cancelled)
        assertEquals(1, store.orphanCalls)
        assertEquals(1, store.legacyCalls)
    }

    private fun queue(scope: CoroutineScope, store: SendQueueStore): ChatSendQueueService = ChatSendQueueService(
        radioID = RADIO,
        collaborators = ChatSendQueueCollaborators(
            store = store,
            messageService = MessageService(
                FakeChatSession(),
                InMemoryMessageStore(),
                scope,
                MessageServiceIdentity({ NOW }, SequentialIds()::next),
            ),
            channelResolver = FakeChannelResolver(),
            scope = scope,
            now = { NOW },
            newPendingSendId = PendingSendIds()::next,
        ),
    )

    private companion object {
        const val NOW: Double = 1_700_000_000.0
        val RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
    }
}

private class WarmUpProbeError : Error("probe")

private class PurgeFaultStore(private val onOrphan: () -> Unit = {}, private val onLegacy: () -> Unit = {}) :
    SendQueueStore by FakeSendQueueStore() {
    var orphanCalls: Int = 0
    var legacyCalls: Int = 0

    override suspend fun purgeOrphanPendingSends(): Int {
        orphanCalls += 1
        onOrphan()
        return 0
    }

    override suspend fun purgeLegacyAttemptCountRows(): Int {
        legacyCalls += 1
        onLegacy()
        return 0
    }
}
