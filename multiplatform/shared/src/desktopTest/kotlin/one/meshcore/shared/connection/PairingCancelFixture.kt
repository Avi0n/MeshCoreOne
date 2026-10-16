package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.transport.MeshLink
import java.io.File
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

internal class PairingCancelFixture {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val dbFile = File.createTempFile("mesh-pairing-cancel", ".db").also { it.delete() }
    val database = meshDatabase(dbFile.absolutePath)
    private val preferences = InMemoryPreferencesStore()
    val lastConnection = LastConnectionStore(preferences)
    val intent = ConnectionIntentStore(preferences)
    val bleTargets = mutableListOf<String>()
    val wifiTargets = mutableListOf<Pair<String, Int>>()
    val bleLinks = mutableListOf<FakeMeshLink>()
    private val recoveryEntered = CompletableDeferred<Unit>()
    private val recoveryGate = CompletableDeferred<Unit>()
    private var gateNextLink = false
    private var latestPublicKey = PUBLIC_KEY
    var mintedRadioIds = 0
        private set
    var noteWantedCalls = 0
        private set

    val manager = ConnectionManager(
        database = database,
        scope = scope,
        linkFactory = { handle ->
            bleTargets += handle
            latestPublicKey = if (handle == OTHER_HANDLE) OTHER_PUBLIC_KEY else PUBLIC_KEY
            val link = FakeMeshLink().also(bleLinks::add)
            boundedRecoveryLink(link)
        },
        sessionFactory = { FakeMeshSession(defaultSelfInfo(latestPublicKey)) },
        nowEpochSeconds = { NOW_SECONDS },
        ports = ConnectionManagerPorts(
            policy = ReconnectPolicy(
                ReconnectPolicy.Config(
                    baseBackoff = 1.milliseconds,
                    maxBackoff = 1.milliseconds,
                    sessionRebuildRetryDelay = 1.milliseconds,
                ),
            ),
            wifiPolicy = WiFiReconnectPolicy(baseBackoff = 1.milliseconds, maxBackoff = 1.milliseconds),
            wifiLinkFactory = { host, port, heartbeat ->
                wifiTargets += host to port
                latestPublicKey = PUBLIC_KEY
                boundedRecoveryLink(FakeWiFiLink(host, port, heartbeat))
            },
            lastConnectionStore = lastConnection,
            intentStore = intent,
            newRadioId = {
                mintedRadioIds++
                if (mintedRadioIds == 1) RADIO_ID else OTHER_RADIO_ID
            },
        ),
    )

    suspend fun connectBle(): DeviceEntity = onManager {
        manager.connect(HANDLE)
        database.deviceDao().listAll().single()
    }

    suspend fun connectWifi(): DeviceEntity = onManager {
        manager.connectViaWiFi(HOST, PORT)
        database.deviceDao().listAll().single()
    }

    suspend fun prepareChooser(): Boolean = onManager {
        prepareCompanionChooser(
            isRadioLinked = manager.state.value.isLinkUp,
            hasLeftovers = false,
            disconnect = { manager.disconnectForPairing() },
            noteWantsConnection = {
                noteWantedCalls++
                manager.noteWantsConnection()
            },
        )
    }

    suspend fun gateRecovery() = onManager { gateNextLink = true }

    suspend fun endPairing(): Job? = onManager {
        manager.setPairingInProgress(false)
        manager.pairingTransitionJob
    }

    suspend fun awaitRecoveryEntry() = withTimeout(TIMEOUT_MS) { recoveryEntered.await() }

    fun releaseRecovery() {
        recoveryGate.complete(Unit)
    }

    suspend fun awaitReady(handle: String) = withTimeout(TIMEOUT_MS) {
        manager.state.first { it == ConnectionState.Ready(handle) }
    }

    suspend fun join(job: Job?) = withTimeout(TIMEOUT_MS) {
        if (job is Deferred<*>) job.await() else job?.join()
    }

    suspend fun launchOnManager(block: suspend () -> Unit): Deferred<Unit> = onManager {
        scope.async(start = CoroutineStart.UNDISPATCHED) { block() }
    }

    suspend fun holdOldTeardown(): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> = onManager {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        checkNotNull(manager.services).rxLogCatchUpJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        entered to release
    }

    suspend fun <T> onManager(block: suspend () -> T): T = withContext(dispatcher) { block() }

    suspend fun close() {
        recoveryGate.cancel()
        try {
            withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() }
        } finally {
            database.close()
            dbFile.delete()
            dispatcher.close()
        }
    }

    private fun boundedRecoveryLink(delegate: MeshLink): MeshLink {
        if (!gateNextLink) return delegate
        gateNextLink = false
        return object : MeshLink by delegate {
            override suspend fun connect() {
                recoveryEntered.complete(Unit)
                recoveryGate.await()
                delegate.connect()
            }
        }
    }

    companion object {
        const val HANDLE = "AA:BB:CC:DD:EE:A1"
        const val OTHER_HANDLE = "AA:BB:CC:DD:EE:B2"
        const val HOST = "10.0.0.41"
        const val PORT = 4403
        const val WIFI_HANDLE = "$HOST:$PORT"
        const val NOW_SECONDS = 1_700_000_000.0
        const val TIMEOUT_MS = 5_000L
        private const val PUBLIC_KEY_SIZE = 32
        private const val PUBLIC_KEY_BYTE = 0x41
        private const val OTHER_PUBLIC_KEY_BYTE = 0x42
        val RADIO_ID = Uuid.normalized("A0000000-0000-0000-0000-000000000041")
        val OTHER_RADIO_ID = Uuid.normalized("A0000000-0000-0000-0000-000000000042")
        val PUBLIC_KEY = ByteArray(PUBLIC_KEY_SIZE) { PUBLIC_KEY_BYTE.toByte() }
        val OTHER_PUBLIC_KEY = ByteArray(PUBLIC_KEY_SIZE) { OTHER_PUBLIC_KEY_BYTE.toByte() }
    }
}
