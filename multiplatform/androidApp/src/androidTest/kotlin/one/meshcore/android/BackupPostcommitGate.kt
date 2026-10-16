package one.meshcore.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import one.meshcore.shared.chat.DraftStore
import one.meshcore.shared.persistence.DiscoveredNodeDao
import one.meshcore.shared.persistence.DiscoveredNodeEntity
import one.meshcore.shared.prefs.PreferencesStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

internal class BackupPostcommitGate(val stage: Stage) {
    val origin = CompletableDeferred<Job>()
    val entered = CompletableDeferred<CoroutineContext>()
    val returned = CompletableDeferred<Unit>()
    val callbackReturned = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private val armed = AtomicBoolean(true)
    var calls = 0
        private set

    suspend fun hold(at: Stage) {
        val context = currentCoroutineContext()
        if (at != stage || context[CoroutineName]?.name != MARKER || !armed.compareAndSet(true, false)) return
        check(origin.isCompleted && origin.await().isActive)
        calls += 1
        entered.complete(context)
        try {
            release.await()
        } finally {
            returned.complete(Unit)
        }
    }

    class Drafts(private val delegate: PreferencesStore, private val gate: BackupPostcommitGate) :
        PreferencesStore by delegate {
        override suspend fun putString(key: String, value: String) {
            delegate.putString(key, value)
            if (key == DraftStore.STORAGE_KEY) gate.hold(Stage.DRAFT)
        }
    }

    class Discover(private val delegate: DiscoveredNodeDao, private val gate: BackupPostcommitGate) :
        DiscoveredNodeDao by delegate {
        override suspend fun listAll(): List<DiscoveredNodeEntity> {
            val actual = delegate.listAll()
            gate.hold(Stage.DISCOVER)
            return actual
        }
    }

    enum class Stage { DRAFT, DISCOVER, BEFORE }

    companion object {
        const val MARKER = "backup-postcommit-ui-origin"
    }
}
