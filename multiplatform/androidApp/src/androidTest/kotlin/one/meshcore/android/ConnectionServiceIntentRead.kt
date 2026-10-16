package one.meshcore.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

internal class ConnectionServiceIntentRead(private val delegate: PreferencesStore) : PreferencesStore by delegate {
    private val next = AtomicReference<Read?>()
    private val readers = CopyOnWriteArrayList<Job>()

    fun capture(held: Boolean = false): Read = Read(held).also {
        check(next.compareAndSet(null, it))
    }

    override suspend fun getBoolean(key: String): Boolean? {
        if (key != PersistenceKeys.USER_EXPLICITLY_DISCONNECTED) return delegate.getBoolean(key)
        val job = checkNotNull(currentCoroutineContext()[Job])
        readers += job
        val read = next.getAndSet(null)
        val value = delegate.getBoolean(key)
        if (read != null) {
            read.value = value
            read.entered.complete(job)
            read.release.await()
        }
        return value
    }

    suspend fun finishReaders() {
        val current = currentCoroutineContext()[Job]
        readers.toList().filter { it !== current }.forEach { job ->
            withTimeout(ConnectionServiceIntentFixture.TIMEOUT_MS) { job.join() }
        }
    }

    fun releasePending() {
        next.getAndSet(null)?.release?.complete(Unit)
    }

    class Read(held: Boolean) {
        val entered = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>().apply { if (!held) complete(Unit) }
        var value: Boolean? = null
            internal set

        suspend fun awaitEntered(): Job = withTimeout(ConnectionServiceIntentFixture.TIMEOUT_MS) { entered.await() }

        suspend fun awaitFinished() {
            val job = awaitEntered()
            withTimeout(ConnectionServiceIntentFixture.TIMEOUT_MS) { job.join() }
        }
    }
}
