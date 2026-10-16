package one.meshcore.shared.chat

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.RoomChatStore
import one.meshcore.shared.persistence.RoomContactStore
import one.meshcore.shared.persistence.RoomPendingSendStore
import one.meshcore.shared.persistence.RxLogCorrelator
import one.meshcore.shared.persistence.chatDaos
import one.meshcore.shared.persistence.meshDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class QueueProcessReplayInstrumentedTest {
    @Test
    fun enqueueAndHoldRealDrains() = phase(SEED, held = true) { db, scope ->
        QueueProcessReplayRows.seedControls(db)
        val graph = graph(db, scope, seed = true)
        val peer = QueueProcessReplayRows.contact()
        for (index in QueueProcessReplayRows.texts.indices) {
            val text = QueueProcessReplayRows.texts[index]
            if (index < DIRECT_COUNT) {
                val row = graph.messages.createPendingMessage(text, peer)
                assertEquals(QueueProcessReplayRows.messages[index].id, row.id)
                graph.queue.enqueueDM(DirectMessageEnvelope(row.id, peer.id))
            } else {
                val row = graph.messages.createPendingChannelMessage(
                    text,
                    QueueProcessReplayRows.CHANNEL,
                    QueueProcessReplayRows.radioA,
                )
                assertEquals(QueueProcessReplayRows.messages[index].id, row.id)
                graph.queue.enqueueChannel(
                    ChannelMessageEnvelope(
                        row.id,
                        QueueProcessReplayRows.CHANNEL,
                        messageText = text,
                        messageTimestamp = QueueProcessReplayRows.SEED_TIME.toLong(),
                        localNodeName = "Queue A",
                    ),
                )
            }
        }
        graph.session.awaitSeedCalls()
        QueueProcessReplayRows.verify(db, drained = false)
    }

    @Test
    fun restoreHydrateAndDrain() = phase(REPLAY, held = false) { db, scope ->
        QueueProcessReplayRows.verify(db, drained = false)
        val graph = graph(db, scope, seed = false)
        graph.queue.warmUp()
        graph.queue.observeConnectionState(DeviceConnectionState.READY, emptyFlow())
        graph.queue.hydrate()
        graph.queue.awaitDrainCompletion()
        graph.session.verify()
        QueueProcessReplayRows.verify(db, drained = true)
        assertEquals(0, graph.messages.pendingAckCount())
        graph.queue.hydrate()
        graph.queue.awaitDrainCompletion()
        graph.session.verify()
        QueueProcessReplayRows.verify(db, drained = true)
        graph.queue.shutdown()
    }

    @Test
    fun coldVerifyDrainedState() = phase(VERIFY, held = false) { db, scope ->
        QueueProcessReplayRows.verify(db, drained = true)
        val graph = graph(db, scope, seed = false)
        graph.queue.warmUp()
        graph.queue.observeConnectionState(DeviceConnectionState.READY, emptyFlow())
        graph.queue.hydrate()
        graph.queue.awaitDrainCompletion()
        graph.session.verify(cold = true)
        QueueProcessReplayRows.verify(db, drained = true)
        graph.queue.shutdown()
    }

    private data class Graph(
        val queue: ChatSendQueueService,
        val messages: MessageService,
        val session: QueueProcessReplaySession,
    )

    private fun graph(db: MeshDatabase, scope: CoroutineScope, seed: Boolean): Graph {
        val pending = RoomPendingSendStore(db.pendingSendDao())
        val chat = RoomChatStore(QueueProcessReplayRows.radioA, db.chatDaos(), RxLogCorrelator(db.rxLogEntryDao()))
        val contacts = RoomContactStore(db)
        val session = QueueProcessReplaySession(hold = seed) { index ->
            requireNotNull(
                pending.fetchPendingSendsForMessage(QueueProcessReplayRows.messages[index].id).single().attemptCount,
            )
        }
        var messageIndex = 0
        val messages = MessageService(
            session,
            chat,
            scope,
            MessageServiceIdentity(
                now = { if (seed) QueueProcessReplayRows.SEED_TIME else QueueProcessReplayRows.REPLAY_TIME },
                newMessageId = {
                    check(seed)
                    QueueProcessReplayRows.messages[messageIndex++].id
                },
            ),
        )
        var pendingIndex = 0
        val queue = ChatSendQueueService(
            QueueProcessReplayRows.radioA,
            ChatSendQueueCollaborators(
                store = DefaultSendQueueStore(pending, chat, contacts::fetchContact),
                messageService = messages,
                channelResolver = { error("Success-only fixture must not query the radio") },
                scope = scope,
                now = { QueueProcessReplayRows.SEED_TIME },
                newPendingSendId = {
                    check(seed)
                    QueueProcessReplayRows.id(PENDING_ID_BASE + pendingIndex++)
                },
            ),
        )
        return Graph(queue, messages, session)
    }

    private fun phase(stage: String, held: Boolean, assertions: suspend (MeshDatabase, CoroutineScope) -> Unit) {
        val arguments = InstrumentationRegistry.getArguments()
        val selected = arguments.getString("queueStage")
        assumeTrue("Requires isolated three-process queue host protocol", selected != null)
        require(selected == stage)
        val nonce = requireNotNull(arguments.getString("queueNonce"))
        require(nonce.matches(Regex("[a-f0-9]{12}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "queue-replay-$nonce.db"
        assertEquals(stage != SEED, context.getDatabasePath(name).exists())
        val receipt = File(context.filesDir, "queue-$nonce-$stage.json")
        assertFalse(receipt.exists())
        val previousStages = when (stage) {
            SEED -> emptyList()
            REPLAY -> listOf(SEED)
            else -> listOf(SEED, REPLAY)
        }
        for (priorStage in previousStages) {
            val prior = JSONObject(File(context.filesDir, "queue-$nonce-$priorStage.json").readText())
            assertEquals(priorStage, prior.getString("phase"))
            assertEquals(nonce, prior.getString("nonce"))
            assertEquals(name, prior.getString("database"))
            assertEquals(Process.myUid(), prior.getInt("uid"))
            assertTrue(Process.myPid() != prior.getInt("pid"))
            assertTrue(prior.getBoolean("assertionsComplete") && prior.getBoolean("databaseOpen"))
        }
        val db = meshDatabase(context, Dispatchers.IO, name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking { withTimeout(ASSERT_TIMEOUT_MS) { assertions(db, scope) } }
            val record = JSONObject().put("phase", stage).put("nonce", nonce).put("database", name)
                .put("pid", Process.myPid()).put("uid", Process.myUid())
                .put("assertionsComplete", true).put("databaseOpen", true)
                .put(
                    "holdDeadlineElapsedMs",
                    if (held) SystemClock.elapsedRealtime() + HOLD_SECONDS * MILLIS_PER_SECOND else 0,
                )
            val temporary = File(context.filesDir, receipt.name + ".tmp")
            assertFalse(temporary.exists())
            FileOutputStream(temporary).use { output ->
                output.write(record.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            assertTrue(temporary.renameTo(receipt))
            if (held) {
                CountDownLatch(1).await(HOLD_SECONDS, TimeUnit.SECONDS)
                fail("Host did not stop the process while its real drains and database remained open")
            }
        } finally {
            try {
                runBlocking { withTimeout(TEARDOWN_TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() } }
            } finally {
                db.close()
            }
        }
    }

    private companion object {
        const val SEED = "seed"
        const val REPLAY = "replay"
        const val VERIFY = "verify"
        const val DIRECT_COUNT = 2
        const val PENDING_ID_BASE = 100
        const val ASSERT_TIMEOUT_MS = 45_000L
        const val TEARDOWN_TIMEOUT_MS = 10_000L
        const val HOLD_SECONDS = 180L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
