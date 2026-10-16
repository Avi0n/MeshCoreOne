package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.transport.BluetoothUnauthorizedException
import one.meshcore.shared.transport.MeshLink
import java.io.File
import java.util.concurrent.Executors

internal class ConnectionPermissionFailureFixture {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val dbFile = File.createTempFile("mesh-permission-failure", ".db").also { it.delete() }
    val database = meshDatabase(dbFile.absolutePath)
    private val preferences = InMemoryPreferencesStore()
    val lastConnection = LastConnectionStore(preferences)
    val intent = ConnectionIntentStore(preferences)
    private val secondConnectGate = CompletableDeferred<Unit>()
    private val firstDeniedRetired = CompletableDeferred<Unit>()
    private val secondDeniedEntered = CompletableDeferred<Unit>()
    private var permissionGranted = true
    val deniedLinks = mutableListOf<FakeMeshLink>()
    var mintedRadioIds = 0
        private set
    var sessionAllocations = 0
        private set

    val manager = ConnectionManager(
        database = database,
        scope = scope,
        linkFactory = { newLink() },
        sessionFactory = {
            sessionAllocations++
            FakeMeshSession(defaultSelfInfo(PUBLIC_KEY))
        },
        nowEpochSeconds = { NOW_SECONDS },
        ports = ConnectionManagerPorts(
            lastConnectionStore = lastConnection,
            intentStore = intent,
            newRadioId = {
                mintedRadioIds++
                RADIO_ID
            },
        ),
    )

    suspend fun connectBaseline(): DeviceEntity = onManager {
        manager.connect(HANDLE)
        database.deviceDao().listAll().single()
    }

    suspend fun startDeniedReconnect() = onManager {
        permissionGranted = false
        manager.startBleReconnect(HANDLE)
        checkNotNull(manager.reconnectJob)
    }

    suspend fun awaitDeniedRetirement() {
        withTimeout(TIMEOUT_MS) { firstDeniedRetired.await() }
    }

    suspend fun awaitBoundedEpisodeBoundary(episode: Job) {
        withTimeout(TIMEOUT_MS) {
            select {
                episode.onJoin { Unit }
                secondDeniedEntered.onAwait { Unit }
            }
        }
    }

    suspend fun grantAndConnect() = onManager {
        permissionGranted = true
        manager.connect(HANDLE, forceReconnect = true)
    }

    suspend fun <T> onManager(block: suspend () -> T): T = withContext(dispatcher) { block() }

    suspend fun close() {
        try {
            withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() }
        } finally {
            secondConnectGate.cancel()
            database.close()
            dbFile.delete()
            dispatcher.close()
        }
    }

    private fun newLink(): MeshLink {
        if (permissionGranted) return FakeMeshLink()
        val delegate = FakeMeshLink()
        deniedLinks += delegate
        val first = deniedLinks.size == 1
        if (first) {
            delegate.failConnectWith = BluetoothUnauthorizedException(
                SecurityException("BLUETOOTH_CONNECT permission denied"),
            )
        } else {
            delegate.connectGate = secondConnectGate
        }
        return object : MeshLink by delegate {
            override suspend fun connect() {
                if (!first) secondDeniedEntered.complete(Unit)
                delegate.connect()
            }

            override suspend fun disconnect() {
                delegate.disconnect()
                if (first) firstDeniedRetired.complete(Unit)
            }
        }
    }

    companion object {
        const val HANDLE = "AA:BB:CC:DD:EE:91"
        const val NOW_SECONDS = 1_700_000_000.0
        const val TIMEOUT_MS = 5_000L
        private const val PUBLIC_KEY_SIZE = 32
        private const val PUBLIC_KEY_BYTE = 0x19
        val RADIO_ID = Uuid.normalized("A0000000-0000-0000-0000-000000000091")
        val PUBLIC_KEY = ByteArray(PUBLIC_KEY_SIZE) { PUBLIC_KEY_BYTE.toByte() }
    }
}
