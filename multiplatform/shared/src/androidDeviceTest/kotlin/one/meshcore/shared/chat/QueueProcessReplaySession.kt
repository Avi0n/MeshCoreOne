package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import one.meshcore.shared.channel.FloodScope
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import java.util.Collections
import kotlin.time.Duration

@Suppress("MagicNumber")
internal class QueueProcessReplaySession(private val hold: Boolean, private val pendingAttempt: suspend (Int) -> Int) :
    ChatSession {
    override val events: SharedFlow<MeshEvent> = MutableSharedFlow()
    private val calls = Collections.synchronizedList(mutableListOf<Call>())
    private val waits = Collections.synchronizedList(mutableListOf<Int>())
    private val directArrived = CompletableDeferred<Unit>()
    private val channelArrived = CompletableDeferred<Unit>()
    private var lastDirect = -1

    private data class Call(
        val index: Int,
        val destination: List<Byte>,
        val timestamp: Long,
        val wireAttempt: Int?,
        val persistedAttempts: Int,
        val scope: ResolvedFloodScope?,
    )

    override suspend fun currentSelfPublicKey(): ByteArray = QueueProcessReplayRows.selfKey

    override suspend fun sendDirectMessage(
        destination: ByteArray,
        text: String,
        timestamp: Long,
        attempt: Int,
    ): MessageSentInfo {
        val index = QueueProcessReplayRows.texts.indexOf(text)
        require(index in 0..1)
        calls.add(Call(index, destination.toList(), timestamp, attempt, pendingAttempt(index), null))
        lastDirect = index
        directArrived.complete(Unit)
        if (hold) awaitCancellation()
        return MessageSentInfo(route = 0, expectedAck = QueueProcessReplayRows.ack(index), suggestedTimeoutMs = 5000)
    }

    override suspend fun sendChannelMessage(
        channelIndex: Int,
        text: String,
        timestamp: Long,
        floodScope: ResolvedFloodScope,
    ) {
        val index = QueueProcessReplayRows.texts.indexOf(text)
        require(index in 2..3 && channelIndex == QueueProcessReplayRows.CHANNEL)
        calls.add(Call(index, emptyList(), timestamp, null, pendingAttempt(index), floodScope))
        channelArrived.complete(Unit)
        if (hold) awaitCancellation()
    }

    override suspend fun sendChannelMessage(channelIndex: Int, text: String, timestamp: Long): Unit =
        error("Expected the scoped channel overload")

    override suspend fun waitForAcknowledgement(code: ByteArray, timeout: Duration): Boolean {
        check(!hold)
        assertArrayEquals(QueueProcessReplayRows.ack(lastDirect), code)
        waits.add(lastDirect)
        return true
    }

    override suspend fun getContact(publicKey: ByteArray): MeshContact? {
        assertArrayEquals(QueueProcessReplayRows.contact().publicKey, publicKey)
        return null
    }

    override suspend fun resetPath(publicKey: ByteArray): Unit = error("Unexpected routing retry")

    suspend fun awaitSeedCalls() {
        directArrived.await()
        channelArrived.await()
        verify(seed = true)
    }

    fun verify(seed: Boolean = false, cold: Boolean = false) {
        val actual = synchronized(calls) { calls.toList() }
        if (cold) {
            assertEquals(emptyList<Call>(), actual)
            assertEquals(emptyList<Int>(), waits.toList())
            return
        }
        val indexes = if (seed) listOf(0, 2) else listOf(0, 1, 2, 3)
        val expected = indexes.map { index ->
            Call(
                index,
                if (index < 2) List(6) { 7.toByte() } else emptyList(),
                if (!seed &&
                    index == 1
                ) {
                    QueueProcessReplayRows.REPLAY_TIME.toLong()
                } else {
                    QueueProcessReplayRows.SEED_TIME.toLong()
                },
                if (index < 2) 0 else null,
                if (!seed && (index == 0 || index == 2)) 2 else 1,
                if (index < 2) null else ResolvedFloodScope.Scope(FloodScope.Disabled),
            )
        }
        assertEquals(expected.filter { it.index < 2 }, actual.filter { it.index < 2 })
        assertEquals(expected.filter { it.index >= 2 }, actual.filter { it.index >= 2 })
        assertEquals(if (seed) emptyList() else listOf(0, 1), waits.toList())
    }
}
