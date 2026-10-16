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
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

internal class ConnectionWantedIntentFixture {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val root = Files.createTempDirectory("mesh-wanted-intent").toFile()
    private val dbFile = File(root, "mesh.db")
    val database = meshDatabase(dbFile.absolutePath)
    private val storedPreferences = InMemoryPreferencesStore()
    private val entered = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    private var heldKey: String? = null
    var preferenceReads = 0
        private set
    private val preferences = object : PreferencesStore by storedPreferences {
        override suspend fun getBoolean(key: String): Boolean? {
            val captured = storedPreferences.getBoolean(key)
            afterRead(key)
            return captured
        }

        override suspend fun getString(key: String): String? {
            val captured = storedPreferences.getString(key)
            afterRead(key)
            return captured
        }
    }
    val intent = ConnectionIntentStore(preferences)
    val last = LastConnectionStore(preferences)
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
            lastConnectionStore = last,
            bluetoothConnectAuthorized = { false },
        ),
    )

    suspend fun startHeldQuery(key: String): Deferred<Boolean> = onManager {
        heldKey = key
        scope.async(start = CoroutineStart.UNDISPATCHED) { manager.isConnectionWanted() }
    }

    suspend fun startHeldTargetRead(): Deferred<Uuid?> = onManager {
        heldKey = PersistenceKeys.LAST_CONNECTED_DEVICE_ID
        scope.async(start = CoroutineStart.UNDISPATCHED) { last.deviceId() }
    }

    suspend fun awaitRead() = withTimeout(TIMEOUT_MS) { entered.await() }

    fun releaseRead() {
        released.complete(Unit)
    }
    fun failRead(error: Throwable) {
        released.completeExceptionally(error)
    }

    suspend fun seedSavedTarget() = last.persist(DEVICE, RADIO, "Synthetic wanted radio", HANDLE)

    suspend fun <T> onManager(block: suspend () -> T): T = withContext(dispatcher) { block() }

    private suspend fun afterRead(key: String) {
        preferenceReads++
        if (heldKey != key) return
        heldKey = null
        entered.complete(Unit)
        released.await()
    }

    suspend fun close() {
        released.cancel()
        try {
            withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() }
        } finally {
            try {
                database.close()
            } finally {
                try {
                    check(root.deleteRecursively() && !root.exists())
                } finally {
                    dispatcher.close()
                }
            }
        }
    }

    companion object {
        val DEVICE = Uuid.normalized("D1000000-0000-0000-0000-000000000001")
        val RADIO = Uuid.normalized("D2000000-0000-0000-0000-000000000002")
        const val HANDLE = "AA:BB:CC:DD:EE:D2"
        const val NOW_SECONDS = 1_700_000_000.0
        const val TIMEOUT_MS = 5_000L
    }
}
