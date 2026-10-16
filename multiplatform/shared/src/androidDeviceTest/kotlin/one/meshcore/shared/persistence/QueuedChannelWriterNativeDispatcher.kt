package one.meshcore.shared.persistence

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

internal class QueuedChannelWriterNativeDispatcher(private val method: String, private val hold: Boolean) :
    CoroutineDispatcher() {
    val entered = CompletableDeferred<Work>()
    private val lock = Any()
    private val observed = mutableListOf<Work>()
    private var armed = false
    private var released = false
    private var held: Work? = null

    fun arm() = synchronized(lock) {
        check(!armed && !released)
        armed = true
    }
    fun works(): List<Work> = synchronized(lock) { observed.toList() }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val frames = Thread.currentThread().stackTrace.map { it.className + "." + it.methodName }
        val outerWriter = context[CoroutineName]?.name == ORIGIN &&
            frames.any { it.startsWith("one.meshcore.shared.persistence.RoomChannelStore.") } &&
            "androidx.room.RoomDatabaseKt.useWriterConnection" in frames
        if (!outerWriter) {
            Dispatchers.IO.dispatch(context, block)
            return
        }
        val work = Work(context, block, frames)
        val pause = synchronized(lock) {
            observed += work
            if (armed && !released && frames.any { it.substringBefore('-').endsWith(".$method") }) {
                armed = false
                entered.complete(work)
                if (hold) held = work
                hold
            } else {
                false
            }
        }
        if (!pause) forward(work)
    }

    fun release() {
        val work = synchronized(lock) {
            released = true
            armed = false
            held.also { held = null }
        }
        work?.let(::forward)
    }

    private fun forward(work: Work) {
        check(work.forwards.incrementAndGet() == 1)
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
        val forwards: AtomicInteger = AtomicInteger(0),
        val returned: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    companion object {
        const val ORIGIN = "channel-writer-origin"
    }
}
