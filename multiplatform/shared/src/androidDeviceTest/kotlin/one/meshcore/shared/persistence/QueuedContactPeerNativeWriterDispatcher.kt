package one.meshcore.shared.persistence

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

internal class QueuedContactPeerNativeWriterDispatcher : CoroutineDispatcher() {
    val entered = CompletableDeferred<Work>()
    val works = CopyOnWriteArrayList<Work>()
    private val lock = Any()
    private var targetOrdinal = 0
    private var released = false
    private var held: Work? = null

    fun arm(ordinal: Int) = synchronized(lock) {
        check(targetOrdinal == 0 && !released && held == null && works.isEmpty())
        targetOrdinal = ordinal
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val frames = Thread.currentThread().stackTrace.map { it.className + "." + it.methodName }
        if (
            context[CoroutineName]?.name == ORIGIN &&
            frames.any(::isStoreWriter) &&
            frames.any(::isOuterWriter)
        ) {
            val work = Work(context, block, frames)
            val keep = synchronized(lock) {
                works.add(work)
                if (!released && works.size == targetOrdinal) {
                    held = work
                    entered.complete(work)
                    true
                } else {
                    false
                }
            }
            if (!keep) forward(work)
        } else {
            Dispatchers.IO.dispatch(context, block)
        }
    }

    fun release() {
        val work = synchronized(lock) {
            released = true
            held.also { held = null }
        }
        work?.let(::forward)
    }

    private fun forward(work: Work) {
        check(work.forwardCount.incrementAndGet() == 1)
        Dispatchers.IO.dispatch(work.context) {
            try {
                work.ran.set(true)
                work.block.run()
            } finally {
                work.returned.complete(Unit)
            }
        }
    }

    data class Work(
        val context: CoroutineContext,
        val block: Runnable,
        val frames: List<String>,
        val ran: AtomicBoolean = AtomicBoolean(false),
        val returned: CompletableDeferred<Unit> = CompletableDeferred(),
        val forwardCount: AtomicInteger = AtomicInteger(),
    )

    companion object {
        const val ORIGIN = "contact-peer-native-origin"
        fun isStoreWriter(frame: String): Boolean =
            listOf("RoomContactStore", "RoomNodeContacts", "RoomChatMessages").any { family ->
                frame.startsWith("one.meshcore.shared.persistence.$family.saveContact") ||
                    frame.startsWith("one.meshcore.shared.persistence.$family$" + "saveContact")
            }

        fun isOuterWriter(frame: String): Boolean = frame == "androidx.room.RoomDatabaseKt.useWriterConnection" ||
            (frame.startsWith("androidx.room.util.DBUtil") && frame.endsWith(".performInTransactionSuspending"))
    }
}
