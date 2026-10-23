// AndroidOnly: WP-501 Actual App/runtime/factory queue channel-query composition, not another frozen queue original.
// AndroidOnly: WP-207 Supplemental deferred-recovery ordering, not ungated production acceptance.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.session.SessionCorrelationException
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.runtime.RuntimeDiagnostic
import com.meshcoreone.android.core.services.contacts.ChannelServiceError
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueChannelConsumerIntegrationTest : RoomProcessTest() {
    @Test fun actualFactoryConfiguredChannelKeepsTheOriginalEnvelopeParked() = runTest {
        withFactoryQueue(QueryReply.CONFIGURED) { f ->
            f.awaitFailure("park")
            assertDeviceError2(f.failures("park").single())
            assertTrue(f.failures("channelQuery").isEmpty())
            assertTrue(f.failures("terminal").isEmpty())
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.assertWire()
            f.assertCurrentGraph()
            f.disconnect()
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.assertStatusEvents(emptyList())
        }
    }

    @Test fun actualFactoryUnconfiguredChannelTerminatesOnlyItsOriginalEnvelope() = runTest {
        withFactoryQueue(QueryReply.UNCONFIGURED) { f ->
            f.harness.eventually("actual factory queue commits terminal channel cleanup",
                virtualBudgetMillis = 10_000, virtualStepMillis = 25) {
                runBlocking { store.fetchPendingSends(f.radioId).isEmpty() } &&
                    f.live.chatSendQueueService.queuedEnvelopeCount == 0
            }
            f.live.chatSendQueueService.awaitDrainCompletion()
            assertDeviceError2(f.failures("terminal").single())
            assertTrue(f.failures("channelQuery").isEmpty())
            assertTrue(f.failures("park").isEmpty())
            f.assertStored(MessageStatus.FAILED, pending = false)
            f.assertWire()
            f.assertCurrentGraph()
            f.disconnect()
            f.assertStored(MessageStatus.FAILED, pending = false)
            f.assertStatusEvents(listOf(MessageStatusEvent.Failed(f.message.id)))
        }
    }

    @Test fun actualFactoryRawNotFoundQueryRetainsTypedCorrelationFailureInsteadOfInventingNull() = runTest {
        withFactoryQueue(QueryReply.RAW_NOT_FOUND) { f ->
            f.awaitFailure("park")
            val query = f.failures("channelQuery").single()
            val wrapped = assertIs<ChannelServiceError.SessionError>(query,
                "The App must use ChannelService, not expose the direct session Timeout: ${query.stackTraceToString()}")
            val chain = generateSequence<Throwable>(wrapped.error) { it.cause }.take(16).toList()
            val shape = chain.joinToString(" -> ") { "${it.javaClass.name}(${it.message})" }
            assertIs<MeshCoreException.ConnectionLost>(wrapped.error, shape)
            val unresolved = assertIs<SessionCorrelationException.UnresolvedReply>(
                chain.dropWhile { it is MeshCoreException.ConnectionLost }.firstOrNull(), shape)
            assertEquals(1L, unresolved.generation, shape)
            assertEquals("channel:0", unresolved.responseFamily, shape)
            assertSame(query, f.failures("park").single())
            assertTrue(f.failures("terminal").isEmpty())
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.assertWire()
            f.assertCurrentGraph()
            val report = f.live.chatSendQueueService.shutdown()
            assertEquals(listOf(LifecycleStage.STOP_SERVICES), report.issues.map { it.stage })
            assertSame(query, report.issues.single().cause)
            f.awaitPhysicalRecovery(query)
            f.assertWire()
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.disconnect(query)
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.assertStatusEvents(emptyList())
        }
    }

    @Test fun actualFactoryCancelsTheHeldQueueQueryWithoutTerminalFailureOrRowLoss() = runTest {
        withFactoryQueue(QueryReply.HELD) { f ->
            f.harness.eventually("actual factory queue waits inside its channel query",
                virtualBudgetMillis = 10_000, virtualStepMillis = 25) { f.queueWire().lastOrNull() == Bytes.of(31, 0) }
            f.assertStored(MessageStatus.FAILED, pending = true)
            f.assertWire()
            f.assertCurrentGraph()
            assertTrue(f.failures("channelQuery").isEmpty())
            assertTrue(f.failures("park").isEmpty())
            assertTrue(f.failures("terminal").isEmpty())

            val report = f.live.chatSendQueueService.shutdown()
            assertTrue(report.isComplete, "Query cancellation must not become a retained error: ${report.issues}")
            assertSame(report, f.live.chatSendQueueService.shutdown())
            assertFalse(f.live.chatSendQueueService.isObservingConnection)
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.assertWire()
            assertTrue(f.failures("channelQuery").isEmpty())
            assertTrue(f.failures("park").isEmpty())
            assertTrue(f.failures("terminal").isEmpty())
            f.disconnect()
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.assertStatusEvents(emptyList())
        }
    }

    private enum class QueryReply { CONFIGURED, UNCONFIGURED, RAW_NOT_FOUND, HELD }

    private suspend fun TestScope.withFactoryQueue(reply: QueryReply, body: suspend (Fixture) -> Unit) {
        val f = Fixture(this, reply)
        var primary: Throwable? = null
        try {
            f.start()
            body(f)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                f.releaseRecovery()
                withContext(NonCancellable) { f.harness.container.close() }
            }
            catch (cleanup: Throwable) {
                val failure = primary
                if (failure == null) throw cleanup
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
        }
    }

    private fun assertDeviceError2(cause: Throwable) {
        val service = assertIs<MessageServiceException>(cause)
        val session = assertIs<MessageServiceError.SessionError>(service.error)
        assertEquals(2.toUByte(), assertIs<MeshCoreException.DeviceError>(session.underlying).code)
    }

    private class RecoveryGate(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        private val lock = Any()
        private var held = true
        private val queued = mutableListOf<Pair<CoroutineContext, Runnable>>()
        val queuedCount: Int get() = synchronized(lock) { queued.size }
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val dispatchNow = synchronized(lock) {
                if (held) { queued += context to block; false } else true
            }
            if (dispatchNow) delegate.dispatch(context, block)
        }
        fun release() {
            val work = synchronized(lock) {
                held = false
                queued.toList().also { queued.clear() }
            }
            work.forEach { (context, block) -> delegate.dispatch(context, block) }
        }
    }

    private inner class Fixture(test: TestScope, reply: QueryReply) {
        private val recovery = RecoveryGate(StandardTestDispatcher(test.testScheduler))
        val harness = ContainerHarness(test, store,
            recoveryDispatcher = if (reply == QueryReply.RAW_NOT_FOUND) recovery else Dispatchers.Default)
        val radioId = RadioId(UUID.fromString("A0000000-0000-0000-0000-000000000001"))
        private val foreignRadioId = RadioId(UUID.fromString("B0000000-0000-0000-0000-000000000001"))
        private val deviceID = UUID.fromString("A0000000-0000-0000-0000-000000000002")
        private val channel = ChannelDTO(
            UUID.fromString("A0000000-0000-0000-0000-000000000003"), radioId, 0u, "Test",
            Bytes.fromHex("aa".repeat(16)),
        )
        val message = MessageDTO(
            id = UUID.fromString("A0000000-0000-0000-0000-000000000004"), radioId = radioId,
            channelIndex = 0u, text = "Hello channel", timestamp = 1_704_067_200u,
            createdAt = EPOCH, sortDate = EPOCH, direction = MessageDirection.OUTGOING, status = MessageStatus.PENDING,
        )
        private val pending = PendingSendDTO(
            UUID.fromString("A0000000-0000-0000-0000-000000000005"), radioId, message.id, PendingSendKind.CHANNEL,
            null, 0u, false, "Hello channel", 1_704_067_200u, "Stored Node", 1, EPOCH, 7,
        )
        private val foreignMessage = message.copy(
            id = UUID.fromString("B0000000-0000-0000-0000-000000000004"), radioId = foreignRadioId,
            text = "Foreign pending",
        )
        private val foreignPending = PendingSendDTO(
            UUID.fromString("B0000000-0000-0000-0000-000000000005"), foreignRadioId, foreignMessage.id,
            PendingSendKind.CHANNEL, null, 0u, false, "Foreign pending", 1_704_067_200u, "Foreign Node", 1, EPOCH, 2,
        )
        private val configuredReply = Bytes.fromHex("120054657374" + "00".repeat(28) + "aa".repeat(16))
        private val send = Bytes.fromHex("0300008000926548656c6c6f206368616e6e656c")
        private lateinit var firmware: FirmwareRadio
        lateinit var live: RadioSessionContainer
        private lateinit var statuses: SessionEventSubscription<MessageStatusEvent>
        private var holdContacts = true
        private var sending = false

        init {
            harness.links.configureRadio = { radio ->
                firmware = radio
                radio.respond = { command ->
                    when (command[0].toInt()) {
                        4 -> if (holdContacts) emptyList() else null
                        3 -> { sending = true; listOf(Bytes.of(1, 2)) }
                        31 -> when {
                            !sending -> listOf(if (command[1] == 0.toUByte()) configuredReply
                                else Bytes.of(0x12, command[1].toInt()) + Bytes(ByteArray(48)))
                            reply == QueryReply.CONFIGURED -> listOf(configuredReply)
                            reply == QueryReply.UNCONFIGURED -> listOf(Bytes.fromHex("1200" + "00".repeat(48)))
                            reply == QueryReply.RAW_NOT_FOUND -> listOf(Bytes.of(1, 2))
                            else -> emptyList()
                        }
                        else -> null
                    }
                }
            }
        }

        suspend fun start() {
            assertNotEquals(deviceID, radioId.value)
            store.saveDevice(DeviceDTO(id = deviceID, radioId = radioId, publicKey = key(7),
                nodeName = "Stored Node", isActive = false))
            store.saveDevice(DeviceDTO(radioId = foreignRadioId, publicKey = key(8),
                nodeName = "Foreign Node", isActive = false))
            store.saveChannel(channel)
            store.saveMessage(message)
            store.upsertPendingSend(pending)
            store.saveMessage(foreignMessage)
            store.upsertPendingSend(foreignPending)
            val connect = harness.container.uiScope.async {
                harness.manager.connect(harness.target(deviceID))
            }
            harness.eventually("actual factory hydrates the row while SYNCING") {
                harness.manager.connectionState == DeviceConnectionState.SYNCING &&
                    harness.sessions.current?.chatSendQueueService?.queuedEnvelopeCount == 1 &&
                    firmware.sent.any { it[0] == 4.toUByte() }
            }
            live = assertNotNull(harness.sessions.current)
            assertSame(live, harness.lifecycle.created.single())
            assertSame(store, live.dataStore)
            assertEquals(radioId, live.token.radioId)
            assertEquals(live.token, live.messageService.token)
            assertEquals(live.token, live.chatSendQueueService.token)
            statuses = live.messageService.statusEvents()
            assertEquals(listOf(pending), store.fetchPendingSends(radioId))
            assertEquals(message, store.fetchMessage(EntityKey(radioId, message.id)))
            assertTrue(firmware.sent.none { it[0] == 3.toUByte() }, "No channel write before actual runtime READY")
            holdContacts = false
            firmware.push(Bytes.of(2) + le32(0))
            firmware.push(Bytes.of(4) + le32(0))
            connect.await()
            harness.assertReady()
            assertCurrentGraph()
        }

        fun failures(phase: String): List<Throwable> = synchronized(harness.messagingDiagnostics) {
            harness.messagingDiagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
                .filter { it.operation == "$phase.${message.id}" }
                .onEach { assertEquals(live.token, it.token) }.map { it.cause }
        }

        fun awaitFailure(phase: String) {
            harness.eventually("actual factory $phase for ${message.id}",
                virtualBudgetMillis = 20_000, virtualStepMillis = 25) { failures(phase).size == 1 }
        }

        fun queueWire(): List<Bytes> = firmware.sent.dropWhile { it[0] != 3.toUByte() }
            .filter { it[0] == 3.toUByte() || it[0] == 31.toUByte() }

        fun assertWire() {
            assertEquals(List(4) { send } + listOf(Bytes.of(31, 0)), queueWire(),
                "Exactly four original channel writes then one real GET_CHANNEL; no replacement generation or injected query")
        }

        suspend fun assertStored(status: MessageStatus, pending: Boolean) {
            assertEquals(message.copy(status = status), store.fetchMessage(EntityKey(radioId, message.id)))
            assertEquals(if (pending) listOf(this.pending.copy(attemptCount = 8)) else emptyList(),
                store.fetchPendingSends(radioId))
            assertEquals(if (pending) 1 else 0, live.chatSendQueueService.queuedEnvelopeCount)
            assertEquals(listOf(channel), store.fetchChannels(radioId))
            assertEquals(foreignMessage, store.fetchMessage(EntityKey(foreignRadioId, foreignMessage.id)))
            assertEquals(listOf(foreignPending), store.fetchPendingSends(foreignRadioId))
        }

        fun assertCurrentGraph() {
            harness.assertReady()
            assertSame(live, harness.sessions.current)
            assertEquals(live.token, harness.manager.snapshot.value.token)
            assertEquals(live.token, harness.links.links.single().live)
            assertEquals(1, harness.sessions.created)
            assertEquals(1, firmware.connects)
            assertFalse(live.isTornDown)
        }

        fun releaseRecovery() = recovery.release()

        suspend fun awaitPhysicalRecovery(query: Throwable) {
            assertEquals(1, recovery.queuedCount, "The real unmatched query must schedule physical recovery")
            releaseRecovery()
            harness.eventually("physical retirement after the real unmatched channel query",
                virtualBudgetMillis = 0) {
                live.isTornDown && live.liveJobCount == 0 && firmware.closes == 1 &&
                    firmware.readers == 0 && harness.sessions.current == null &&
                    harness.manager.isReconnectionWatchdogRunning
            }
            assertEquals(DeviceConnectionState.DISCONNECTED, harness.manager.connectionState)
            assertNull(harness.manager.snapshot.value.token)
            assertNull(harness.manager.connectedDevice)
            assertNull(harness.links.links.single().live)
            assertEquals(1, harness.sessions.created)
            assertEquals(0, harness.sessions.outstanding)
            assertFalse(live.chatSendQueueService.isObservingConnection)
            assertEquals(1, firmware.connects)
            assertEquals(1, firmware.closes)
            assertEquals(listOf(live), harness.lifecycle.tornDown)
            val issue = assertIs<ConnectionIssue.Protocol>(harness.manager.snapshot.value.issue)
            val lost = assertIs<MeshCoreException.ConnectionLost>(issue.error)
            val chain = generateSequence<Throwable>(lost) { it.cause }.take(16).toList()
            val unresolved = assertIs<SessionCorrelationException.UnresolvedReply>(
                chain.dropWhile { it is MeshCoreException.ConnectionLost }.firstOrNull())
            assertEquals(live.token.generation.value, unresolved.generation)
            assertEquals("channel:0", unresolved.responseFamily)
            val report = harness.diagnostics.filterIsInstance<RuntimeDiagnostic.Teardown>().single().report
            assertEquals(listOf(LifecycleStage.STOP_SERVICES), report.issues.map { it.stage })
            assertSame(query, report.issues.single().cause)
            assertTrue(failures("terminal").isEmpty())
            assertTrue(harness.manager.connectionIntent.wantsConnection)
        }

        suspend fun disconnect(expectedIssue: Throwable? = null) {
            val report = harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            if (expectedIssue == null) assertTrue(report.isComplete, "Unexpected teardown issues: ${report.issues}")
            else {
                assertEquals(listOf(LifecycleStage.STOP_SERVICES), report.issues.map { it.stage })
                assertSame(expectedIssue, report.issues.single().cause)
            }
            assertTrue(live.isTornDown)
            assertFalse(live.chatSendQueueService.isObservingConnection)
            assertEquals(0, live.liveJobCount)
            assertNull(harness.sessions.current)
            assertEquals(0, harness.sessions.outstanding)
            assertEquals(0, firmware.readers)
            assertEquals(1, firmware.closes)
            assertEquals(foreignMessage, store.fetchMessage(EntityKey(foreignRadioId, foreignMessage.id)))
            assertEquals(listOf(foreignPending), store.fetchPendingSends(foreignRadioId))
        }

        suspend fun assertStatusEvents(expected: List<MessageStatusEvent>) {
            val events = statuses.events.toList()
            assertTrue(events.all { it.token == live.token })
            assertEquals(expected, events.map { it.event })
        }
    }
}
