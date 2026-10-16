package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore
import java.io.File
import java.util.concurrent.Executors

internal class ExplicitDisconnectIntentFixture {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val dbFile = File.createTempFile("mesh-disconnect-intent", ".db").also { it.delete() }
    val database = meshDatabase(dbFile.absolutePath)
    private val storedPreferences = InMemoryPreferencesStore()
    private val readEntered = CompletableDeferred<Unit>()
    private val releaseRead = CompletableDeferred<Unit>()
    private var holdNextRead = false
    private val preferences = object : PreferencesStore by storedPreferences {
        override suspend fun getBoolean(key: String): Boolean? {
            val captured = storedPreferences.getBoolean(key)
            if (key == PersistenceKeys.USER_EXPLICITLY_DISCONNECTED && holdNextRead) {
                holdNextRead = false
                readEntered.complete(Unit)
                releaseRead.await()
            }
            return captured
        }
    }
    val intent = ConnectionIntentStore(preferences)
    var linkAllocations = 0
        private set
    var sessionAllocations = 0
        private set

    val manager = ConnectionManager(
        database = database,
        scope = scope,
        linkFactory = {
            linkAllocations++
            FakeMeshLink()
        },
        sessionFactory = {
            sessionAllocations++
            FakeMeshSession()
        },
        nowEpochSeconds = { NOW_SECONDS },
        ports = ConnectionManagerPorts(
            intentStore = intent,
            bluetoothConnectAuthorized = { false },
        ),
    )

    suspend fun suspendNextIntentRead(): Deferred<Boolean> = onManager {
        holdNextRead = true
        scope.async(start = CoroutineStart.UNDISPATCHED) { manager.isExplicitlyDisconnected() }
    }

    suspend fun awaitCapturedRead() = withTimeout(TIMEOUT_MS) { readEntered.await() }

    fun resumeIntentRead() {
        releaseRead.complete(Unit)
    }

    fun cancelIntentRead() {
        releaseRead.cancel()
    }

    suspend fun <T> onManager(block: suspend () -> T): T = withContext(dispatcher) { block() }

    suspend fun close() {
        releaseRead.cancel()
        try {
            withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() }
        } finally {
            database.close()
            dbFile.delete()
            dispatcher.close()
        }
    }

    companion object {
        const val HANDLE = "AA:BB:CC:DD:EE:D1"
        const val NOW_SECONDS = 1_700_000_000.0
        const val TIMEOUT_MS = 5_000L
    }
}
