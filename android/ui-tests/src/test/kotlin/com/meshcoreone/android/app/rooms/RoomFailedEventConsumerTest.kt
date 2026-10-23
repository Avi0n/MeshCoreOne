// AndroidOnly: WP-502 Real App event adapter/Room/holder regressions for immediate in-thread failed-send acknowledgement.
// ReferenceInput: MC1/Views/RemoteNodes/Rooms/RoomConversationViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.rooms

import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.rooms.*
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomFailedEventConsumerTest : RoomProcessTest() {
    @Test fun knownFailureMarksTheRealStoreBefore50msEvenWhileTheLaterReadIsHeld() = runTest {
        withRig(this) {
            val release = CompletableDeferred<Unit>()
            beforeFetchReturn = { if (it == 2) release.await() }
            failOriginal()
            fire(MessageEvent.RoomMessageFailed(original.id))
            awaitChanges(1)

            assertEquals(0L, elapsed)
            assertTrue(persisted(original).failureSeen)
            assertEquals(listOf(0L), markTimes)
            assertEquals(listOf("failed-start", "failed-done", "changed"), trace)
            assertEquals(listOf(RoomConversationEvent.MessageFailed(room.id, original.id)), observed)
            advance(49)
            assertEquals(1, fetches)
            advance(1)
            awaitSnapshot(2)
            assertEquals(1, changes)
            assertEquals(MessageStatus.PENDING, holder.state.value.messages.single().status)
            assertEquals(listOf("failed-start", "failed-done", "changed", "fetch"), trace)

            release.complete(Unit)
            awaitChanges(2)
            assertTrue(holder.state.value.messages.single().failureSeen)
            assertEquals(MessageStatus.FAILED, holder.state.value.messages.single().status)
            assertEquals(
                listOf("failed-start", "failed-done", "changed", "fetch", "read",
                    "failed-start", "failed-done", "notifications", "badge", "changed"),
                trace,
            )
            assertTrue(reported.isEmpty())
        }
    }

    @Test fun failureDuringAnOlderHeldReadInvalidatesItsRowsAndKeepsTheSuccessorSlot() = runTest {
        withRig(this) {
            val release = CompletableDeferred<Unit>()
            beforeFetchReturn = { if (it == 2) release.await() }
            fire(MessageEvent.RoomMessageStatusUpdated(original.id))
            awaitEvents(1)
            advance(50)
            awaitSnapshot(2)
            assertEquals(MessageStatus.PENDING, snapshots[1].single().status)

            failOriginal()
            fire(MessageEvent.RoomMessageFailed(original.id))
            awaitChanges(1)
            assertEquals(listOf(50L), markTimes)
            assertTrue(persisted(original).failureSeen)
            release.complete(Unit)
            pump("invalidated read returns") { completedFetches.contains(2) }
            assertEquals(MessageStatus.PENDING, holder.state.value.messages.single().status)
            assertFalse(trace.contains("read"))
            assertEquals(1, changes)

            fire(MessageEvent.RoomMessageStatusUpdated(original.id))
            awaitEvents(3)
            advance(49)
            assertEquals(2, fetches)
            advance(1)
            awaitChanges(2)
            assertEquals(3, fetches)
            assertEquals(MessageStatus.FAILED, holder.state.value.messages.single().status)
            assertTrue(holder.state.value.messages.single().failureSeen)
            assertEquals(1, trace.count { it == "read" })
            assertFalse(holder.state.value.isLoading)
            assertTrue(reported.isEmpty())
        }
    }

    @Test fun failedReloadCannotUndoTheImmediateWriteAndTheNextStatusCanRecover() = runTest {
        withRig(this) {
            val failure = IllegalStateException("held-port read failed")
            beforeFetchReturn = { if (it == 2) throw failure }
            failOriginal()
            fire(MessageEvent.RoomMessageFailed(original.id))
            awaitChanges(1)
            advance(49)
            assertTrue(persisted(original).failureSeen)
            assertEquals(1, fetches)
            assertTrue(reported.isEmpty())
            advance(1)
            pump("later read failure") { holder.state.value.failure === failure }
            assertEquals(1, changes)
            assertEquals(listOf("load room"), reported.map { it.message })
            assertSame(failure, reported.single().thrown)
            assertTrue(persisted(original).failureSeen)
            assertFalse(trace.contains("read"))

            beforeFetchReturn = {}
            fire(MessageEvent.RoomMessageStatusUpdated(original.id))
            awaitEvents(2)
            advance(50)
            awaitChanges(2)
            assertEquals(3, fetches)
            assertNull(holder.state.value.failure)
            assertEquals(MessageStatus.FAILED, holder.state.value.messages.single().status)
        }
    }

    @Test fun ordinaryStatusUnknownUnloadedAndForeignFailuresHaveNoImmediateFailedEffects() = runTest {
        withRig(this) {
            val unloaded = message(2, 250u).copy(statusRawValue = MessageStatus.FAILED.rawValue)
            val otherRoom = room.copy(id = UUID(502, 99), publicKey = Bytes(ByteArray(32) { 9 }))
            val foreignSession = message(3, 300u).copy(
                sessionID = otherRoom.id, statusRawValue = MessageStatus.FAILED.rawValue,
            )
            val otherRadio = RadioId(UUID(502, 100))
            val foreignRadio = message(4, 400u).copy(statusRawValue = MessageStatus.FAILED.rawValue)
            stored {
                store.saveRoomMessage(room.radioId, unloaded)
                store.saveRemoteNodeSessionDTO(otherRoom)
                store.saveRoomMessage(room.radioId, foreignSession)
                store.saveRemoteNodeSessionDTO(room.copy(radioId = otherRadio))
                store.saveRoomMessage(otherRadio, foreignRadio)
            }
            failOriginal()
            fire(MessageEvent.RoomMessageFailed(UUID(502, 101)))
            fire(MessageEvent.RoomMessageFailed(foreignSession.id))
            fire(MessageEvent.RoomMessageFailed(foreignRadio.id))
            fire(MessageEvent.RoomMessageFailed(unloaded.id))
            fire(MessageEvent.RoomMessageStatusUpdated(original.id))
            awaitEvents(2)
            assertEquals(
                listOf(RoomConversationEvent.MessageFailed(room.id, unloaded.id),
                    RoomConversationEvent.MessageChanged(room.id, original.id)),
                observed,
            )
            advance(49)
            assertEquals(1, fetches)
            assertEquals(0, changes)
            assertTrue(trace.isEmpty())
            assertFalse(persisted(original).failureSeen)
            assertFalse(persisted(unloaded).failureSeen)
            advance(1)
            awaitChanges(1)
            assertEquals(2, fetches)
            assertTrue(persisted(original).failureSeen)
            assertTrue(persisted(unloaded).failureSeen)
            assertFalse(persisted(foreignSession).failureSeen)
            assertFalse(stored {
                requireNotNull(store.fetchRoomMessage(EntityKey(otherRadio, foreignRadio.id))).failureSeen
            })
            assertEquals(listOf(50L), markTimes)
            assertTrue(reported.isEmpty())
        }
    }

    @Test fun mixedBurstKeepsTheFirst50msBoundaryAndStoreOrderingWithoutDuplicateRows() = runTest {
        withRig(this) {
            failOriginal()
            fire(MessageEvent.RoomMessageFailed(original.id))
            awaitChanges(1)
            advance(20)
            val earlier = message(3, 100u).copy(isFromSelf = false)
            val later = message(4, 100u).copy(isFromSelf = false)
            stored {
                store.saveRoomMessage(room.radioId, later)
                store.saveRoomMessage(room.radioId, earlier)
            }
            fire(MessageEvent.RoomMessageReceived(later, room.id))
            fire(MessageEvent.RoomMessageReceived(earlier, room.id))
            awaitEvents(3)
            assertEquals(listOf(later.id, earlier.id, original.id), holder.state.value.messages.map { it.id })
            advance(20)
            fire(MessageEvent.RoomMessageReceived(later, room.id))
            fire(MessageEvent.RoomMessageStatusUpdated(original.id))
            fire(MessageEvent.RoomMessageFailed(original.id))
            awaitChanges(2)
            assertEquals(listOf(0L, 40L), markTimes)
            advance(9)
            assertEquals(1, fetches)
            advance(1)
            awaitChanges(3)
            assertEquals(50L, elapsed)
            assertEquals(2, fetches)
            assertEquals(listOf(earlier.id, later.id, original.id), holder.state.value.messages.map { it.id })
            assertEquals(holder.state.value.messages.map { it.id }, holder.state.value.rows.map { it.message.id })
            assertEquals(listOf(0L, 40L, 50L), markTimes)
            assertTrue(reported.isEmpty())
        }
    }

    @Test fun realFailedSeenWriteFailureIsLoggedWithoutCancellingReloadOrTheCollector() = runTest {
        withRig(this) {
            failOriginal()
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_room_failed_seen BEFORE UPDATE OF failureSeen ON room_messages " +
                    "WHEN NEW.failureSeen = 1 AND OLD.failureSeen = 0 " +
                    "BEGIN SELECT RAISE(ABORT, 'injected room failed-seen write failure'); END",
            )
            try {
                fire(MessageEvent.RoomMessageFailed(original.id))
                pump("real immediate write diagnostic") { reported.any { it.message == "mark room failed sends seen" } }
                assertTrue(reported.single().thrown is PersistenceStoreException)
                assertEquals(0, changes)
                assertFalse(persisted(original).failureSeen)
                assertEquals(listOf("failed-start"), trace)
                assertNull(holder.state.value.failure)
                advance(49)
                assertEquals(1, fetches)
                advance(1)
                pump("scheduled reload survives failed write") { holder.state.value.failure != null }
                assertEquals(2, fetches)
                assertEquals(listOf("mark room failed sends seen", "load room"), reported.map { it.message })
                assertEquals(0, changes)
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_room_failed_seen")
            }
            fire(MessageEvent.RoomMessageFailed(original.id))
            awaitChanges(1)
            assertTrue(persisted(original).failureSeen)
            assertEquals(2, observed.filterIsInstance<RoomConversationEvent.MessageFailed>().size)
            assertEquals(2, reported.size)
            advance(50)
            awaitChanges(2)
            assertEquals(3, fetches)
            assertNull(holder.state.value.failure)
            assertTrue(holder.state.value.messages.single().failureSeen)
        }
    }

    @Test fun cancellingTheImmediateWritePropagatesButDoesNotOwnTheScheduledReload() = runTest {
        withRig(this) {
            markGate = CompletableDeferred()
            failOriginal()
            fire(MessageEvent.RoomMessageFailed(original.id))
            pump("immediate write suspended") { markJob != null }
            val collector = requireNotNull(markJob)
            collector.cancel(CancellationException("cancel only the event consumer"))
            pump("write cancellation propagates") { markCancellation != null }
            assertTrue(collector.isCancelled)
            assertTrue(screenScope.isActive)
            assertEquals(0, changes)
            assertFalse(persisted(original).failureSeen)
            assertTrue(reported.isEmpty())
            markGate = null
            advance(49)
            assertEquals(1, fetches)
            advance(1)
            awaitChanges(1)
            assertEquals(2, fetches)
            assertTrue(persisted(original).failureSeen)
            assertEquals(listOf(0L, 50L), markTimes)
            assertTrue(reported.isEmpty())
        }
    }

    @Test fun cancellingTheScreenBefore50msStopsReloadWithoutRevertingTheCommittedEffect() = runTest {
        withRig(this) {
            failOriginal()
            fire(MessageEvent.RoomMessageFailed(original.id))
            awaitChanges(1)
            screenScope.cancel()
            advance(50)
            assertEquals(1, fetches)
            assertEquals(1, changes)
            assertTrue(persisted(original).failureSeen)
            assertFalse(trace.contains("read"))
            assertTrue(reported.isEmpty())
        }
    }

    private suspend fun withRig(test: TestScope, block: suspend Rig.() -> Unit) {
        val rig = Rig(test)
        try {
            rig.initialize()
            rig.block()
        } finally {
            rig.screenScope.cancel()
            test.testScheduler.runCurrent()
            rig.logger.removeHandler(rig.logHandler)
            rig.h.container.close()
        }
    }

    private inner class Rig(val test: TestScope) {
        val h = ContainerHarness(test, store)
        val screenScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val room = RemoteNodeSessionDTO(
            id = UUID(502, 310), radioId = RadioId(UUID(502, 1)),
            publicKey = Bytes(ByteArray(32) { 0x42 }), name = "Synthetic failed-event room",
            role = RemoteNodeRole.ROOM_SERVER, isConnected = true,
            permissionLevel = RoomPermissionLevel.READ_WRITE,
        )
        val original = message(1, 200u)
        val trace = mutableListOf<String>()
        val markTimes = mutableListOf<Long>()
        val observed = mutableListOf<RoomConversationEvent>()
        val snapshots = mutableListOf<List<RoomMessageDTO>>()
        val completedFetches = mutableListOf<Int>()
        val reported = Collections.synchronizedList(mutableListOf<LogRecord>())
        val logger = Logger.getLogger("com.mc1.AppChatsFeature")
        val logHandler = object : Handler() {
            override fun publish(record: LogRecord) { reported += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        var beforeFetchReturn: suspend (Int) -> Unit = {}
        var markGate: CompletableDeferred<Unit>? = null
        var markJob: Job? = null
        var markCancellation: CancellationException? = null
        var fetches = 0
        var changes = 0
        private var subscribed = false
        private var origin = 0L
        private var initialVersion = 0
        val elapsed get() = test.testScheduler.currentTime - origin
        private val actual = h.container.chatsFeature.room(room)
        private val dependencies = object : RoomConversationDependencies by actual {
            override val events = actual.events.onStart { subscribed = true }.onEach {
                if (it !is RoomConversationEvent.ConnectionRecovered) observed += it
            }
            override val data = object : RoomConversationDataSource by actual.data {
                override suspend fun fetchMessages(session: RemoteNodeSessionDTO): List<RoomMessageDTO> {
                    trace += "fetch"
                    val call = ++fetches
                    val messages = actual.data.fetchMessages(session)
                    snapshots += messages.toList()
                    beforeFetchReturn(call)
                    completedFetches += call
                    return messages
                }
                override suspend fun markAsRead(session: RemoteNodeSessionDTO) {
                    trace += "read"
                    actual.data.markAsRead(session)
                }
                override suspend fun markFailedSendsSeen(session: RemoteNodeSessionDTO) {
                    trace += "failed-start"
                    markTimes += elapsed
                    markJob = currentCoroutineContext()[Job]
                    try {
                        markGate?.await()
                        actual.data.markFailedSendsSeen(session)
                        trace += "failed-done"
                    } catch (cancelled: CancellationException) {
                        markCancellation = cancelled
                        throw cancelled
                    }
                }
            }
            override suspend fun removeDeliveredNotifications(session: RemoteNodeSessionDTO) {
                trace += "notifications"
                actual.removeDeliveredNotifications(session)
            }
            override suspend fun updateBadgeCount() {
                trace += "badge"
                actual.updateBadgeCount()
            }
            override fun conversationsChanged() {
                trace += "changed"
                actual.conversationsChanged()
                changes++
            }
        }
        val holder = RoomConversationStateHolder(room, dependencies, screenScope)

        suspend fun initialize() {
            logger.addHandler(logHandler)
            store.saveRemoteNodeSessionDTO(room)
            store.saveRoomMessage(room.radioId, original)
            holder.start()
            holder.start()
            holder.load()
            pump("actual App room subscription") { subscribed }
            initialVersion = h.appState.conversationsVersion.value
            origin = test.testScheduler.currentTime
            trace.clear()
            markTimes.clear()
            changes = 0
            markJob = null
            assertTrue(reported.isEmpty())
        }

        fun message(index: Long, timestamp: UInt) = RoomMessageDTO(
            id = UUID(502, index), sessionID = room.id, authorKeyPrefix = Bytes.of(0x42),
            authorName = "Synthetic author", text = "Synthetic room message $index",
            timestamp = timestamp, createdAt = Instant.ofEpochSecond(timestamp.toLong(), index),
            isFromSelf = true, statusRawValue = MessageStatus.PENDING.rawValue,
        )

        fun persisted(message: RoomMessageDTO): RoomMessageDTO = stored {
            requireNotNull(store.fetchRoomMessage(EntityKey(room.radioId, message.id)))
        }

        fun failOriginal() = stored {
            store.updateRoomMessageStatus(EntityKey(room.radioId, original.id), MessageStatus.FAILED, null, null)
        }

        // Room continuations must run without either blocking their scheduler or advancing the 50ms timer.
        fun <T> stored(block: suspend () -> T): T {
            val result = test.backgroundScope.async { block() }
            pump("real Room operation") { result.isCompleted }
            return result.getCompleted()
        }

        fun fire(event: MessageEvent) = h.appState.messageEventStream.send(event)

        fun pump(label: String, condition: () -> Boolean) =
            h.eventually(label, virtualBudgetMillis = 0, condition = condition)

        fun awaitChanges(count: Int) {
            pump("real successful failed-seen/conversation effects $count") { changes == count }
            assertEquals(initialVersion + count, h.appState.conversationsVersion.value)
            assertTrue(h.links.radios.isEmpty())
        }

        fun awaitEvents(count: Int) = pump("actual App room events $count") { observed.size == count }
        fun awaitSnapshot(count: Int) = pump("real Room read $count") { snapshots.size == count }
        fun advance(milliseconds: Long) {
            test.testScheduler.advanceTimeBy(milliseconds)
            test.testScheduler.runCurrent()
        }
    }
}
