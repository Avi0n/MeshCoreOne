package one.meshcore.shared.persistence

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

internal class QueuedContactPreferenceWriterDispatcher : CoroutineDispatcher() {
    val entered = CompletableDeferred<Work>()
    private val lock = Any()
    private var armed = false
    private var released = false
    private var held: Work? = null

    fun arm() = synchronized(lock) {
        check(!armed && !released && held == null)
        armed = true
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val frames = Thread.currentThread().stackTrace.map { it.className + "." + it.methodName }
        if (
            context[CoroutineName]?.name == ORIGIN &&
            frames.any(::isStoreWriter) &&
            frames.any(::isOuterWriter)
        ) {
            val work = synchronized(lock) {
                if (armed && !released) {
                    armed = false
                    Work(context, block, frames).also {
                        held = it
                        entered.complete(it)
                    }
                } else {
                    null
                }
            }
            if (work != null) return
        }
        Dispatchers.IO.dispatch(context, block)
    }

    fun release() {
        val work = synchronized(lock) {
            released = true
            armed = false
            held.also { held = null }
        }
        work?.let {
            Dispatchers.IO.dispatch(it.context) {
                try {
                    it.ran.set(true)
                    it.block.run()
                } finally {
                    it.returned.complete(Unit)
                }
            }
        }
    }

    data class Work(
        val context: CoroutineContext,
        val block: Runnable,
        val frames: List<String>,
        val ran: AtomicBoolean = AtomicBoolean(false),
        val returned: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    companion object {
        const val ORIGIN = "nickname-preference-writer-origin"
        fun isStoreWriter(frame: String): Boolean =
            frame.startsWith("one.meshcore.shared.persistence.RoomContactStore.") ||
                frame.startsWith("one.meshcore.shared.persistence.RoomContactStore$")

        fun isOuterWriter(frame: String): Boolean = frame == "androidx.room.RoomDatabaseKt.useWriterConnection" ||
            (frame.startsWith("androidx.room.util.DBUtil") && frame.endsWith(".performInTransactionSuspending"))
    }
}
