package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.transport.BluetoothUnauthorizedException
import one.meshcore.shared.transport.MeshLink
import java.io.File
import java.util.concurrent.Executors

internal class ConnectionPermissionBoundaryFixture {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var scope = newScope()
    private val owners = mutableListOf(scope)
    private val dbFile = File.createTempFile("mesh-permission-boundary", ".db").also { it.delete() }
    val database = meshDatabase(dbFile.absolutePath)
    private val preferences = InMemoryPreferencesStore()
    val lastConnection = LastConnectionStore(preferences)
    val intent = ConnectionIntentStore(preferences)
    var authorized = true
    var raceNextConnect = false
    var holdDeniedDisconnect = false
    var mintedRadioIds = 0
        private set
    val bleLinks = mutableListOf<FakeMeshLink>()
    val wifiLinks = mutableListOf<FakeWiFiLink>()
    val deniedCleanupEntered = CompletableDeferred<Unit>()
    val releaseDeniedCleanup = CompletableDeferred<Unit>()
    private val secondRacedEntry = CompletableDeferred<Unit>()
    private val secondRacedGate = CompletableDeferred<Unit>()
    private var racedLink: FakeMeshLink? = null
    var manager = newManager()
        private set

    suspend fun connectBleBaseline() = onManager {
        manager.connect(HANDLE)
        database.deviceDao().listAll().single()
    }

    suspend fun connectWifi() = onManager { manager.connectViaWiFi(WIFI_HOST, WIFI_PORT) }

    suspend fun replaceWithColdGraph() = onManager {
        manager.tearDownCurrent(stopSession = true)
        withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() }
        scope = newScope()
        owners += scope
        manager = newManager()
    }

    suspend fun startRacedConnect(): Deferred<BluetoothUnauthorizedException?> = onManager {
        raceNextConnect = true
        scope.async {
            try {
                manager.connect(HANDLE, forceReconnect = true)
                null
            } catch (denied: BluetoothUnauthorizedException) {
                denied
            }
        }
    }

    suspend fun awaitRacedBoundary(operation: Deferred<BluetoothUnauthorizedException?>) {
        withTimeout(TIMEOUT_MS) {
            select {
                operation.onAwait { Unit }
                secondRacedEntry.onAwait { Unit }
            }
        }
    }

    suspend fun <T> onManager(block: suspend () -> T): T = withContext(dispatcher) { block() }

    suspend fun close() {
        for (owner in owners) owner.coroutineContext.job.cancel()
        releaseDeniedCleanup.complete(Unit)
        secondRacedGate.cancel()
        try {
            for (owner in owners) {
                withTimeout(TIMEOUT_MS) { owner.coroutineContext.job.join() }
            }
        } finally {
            database.close()
            dbFile.delete()
            dispatcher.close()
        }
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + dispatcher)

    private fun newManager() = ConnectionManager(
        database = database,
        scope = scope,
        linkFactory = { newBleLink() },
        sessionFactory = { FakeMeshSession(defaultSelfInfo(PUBLIC_KEY)) },
        nowEpochSeconds = { NOW_SECONDS },
        ports = ConnectionManagerPorts(
            bluetoothConnectAuthorized = { authorized },
            wifiLinkFactory = { host, port, heartbeat ->
                FakeWiFiLink(host, port, heartbeat).also { wifiLinks += it }
            },
            lastConnectionStore = lastConnection,
            intentStore = intent,
            newRadioId = {
                mintedRadioIds++
                RADIO_ID
            },
        ),
    )

    private fun newBleLink(): MeshLink {
        val delegate = FakeMeshLink()
        bleLinks += delegate
        val races = raceNextConnect
        raceNextConnect = false
        if (races) {
            racedLink = delegate
            delegate.failConnectWith = BluetoothUnauthorizedException(SecurityException("connect grant revoked"))
        } else if (racedLink != null && !authorized) {
            delegate.connectGate = secondRacedGate
        }
        return object : MeshLink by delegate {
            override suspend fun connect() {
                if (races) authorized = false
                if (delegate.connectGate != null) secondRacedEntry.complete(Unit)
                delegate.connect()
            }

            override suspend fun disconnect() {
                delegate.disconnect()
                if (races) {
                    deniedCleanupEntered.complete(Unit)
                    if (holdDeniedDisconnect) releaseDeniedCleanup.await()
                }
            }
        }
    }

    companion object {
        const val HANDLE = "AA:BB:CC:DD:EE:92"
        const val WIFI_HOST = "192.0.2.92"
        const val WIFI_PORT = 4_092
        const val NOW_SECONDS = 1_700_000_000.0
        const val TIMEOUT_MS = 5_000L
        private const val PUBLIC_KEY_SIZE = 32
        private const val PUBLIC_KEY_BYTE = 0x29
        val RADIO_ID = Uuid.normalized("A0000000-0000-0000-0000-000000000092")
        val PUBLIC_KEY = ByteArray(PUBLIC_KEY_SIZE) { PUBLIC_KEY_BYTE.toByte() }
    }
}
