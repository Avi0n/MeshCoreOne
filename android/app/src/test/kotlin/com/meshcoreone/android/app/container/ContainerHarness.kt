// AndroidOnly: WP-303 Test harness: the production AppContainer over a Room store, a fake radio and fake platform roles.
// AndroidOnly: WP-501 Fixed, class-scoped Room lifecycle diagnostic markers.
// AndroidOnly: WP-207 Forward recovery scheduling through the real App graph.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.database.MeshCoreDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import com.meshcoreone.android.core.contracts.domain.BluetoothAddress
import com.meshcoreone.android.core.contracts.domain.BluetoothPairingHandle
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.runtime.ProcessConnectionPreferences
import com.meshcoreone.android.core.runtime.RuntimeClock
import com.meshcoreone.android.core.runtime.RuntimeDiagnostic
import com.meshcoreone.android.core.runtime.RuntimeIssueReporter
import com.meshcoreone.android.core.runtime.RuntimePreferenceSnapshot
import com.meshcoreone.android.core.runtime.RuntimePreferenceValue
import com.meshcoreone.android.core.services.contacts.ContactPreferenceFlags
import com.meshcoreone.android.core.services.diagnostics.DebugLogBuffer
import com.meshcoreone.android.core.services.messaging.ChatSendQueueConfig
import com.meshcoreone.android.core.services.messaging.ChatSendQueueProgress
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import com.meshcoreone.android.core.services.rendering.DraftDefaults
import com.meshcoreone.android.core.services.rendering.DraftStore
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.ExperimentalCoroutinesApi

internal class MemoryConnectionPreferences : ProcessConnectionPreferences {
    val values = linkedMapOf<String, RuntimePreferenceValue>()
    override suspend fun read() = RuntimePreferenceSnapshot(values)
    override suspend fun update(transform: (MutableMap<String, RuntimePreferenceValue>) -> Unit): RuntimePreferenceSnapshot {
        val next = values.toMutableMap()
        transform(next)
        values.clear(); values.putAll(next)
        return RuntimePreferenceSnapshot(values)
    }
}

internal class MemoryVault : NodePasswordVault {
    private val values = mutableMapOf<Pair<RadioId, Bytes>, String>()
    override suspend fun store(password: String, radioId: RadioId, nodePublicKey: Bytes) { values[radioId to nodePublicKey] = password }
    override suspend fun retrieve(radioId: RadioId, nodePublicKey: Bytes): String? = values[radioId to nodePublicKey]
    override suspend fun has(radioId: RadioId, nodePublicKey: Bytes): Boolean = (radioId to nodePublicKey) in values
    override suspend fun delete(radioId: RadioId, nodePublicKey: Bytes) { values.remove(radioId to nodePublicKey) }
}

internal class MemoryFlags : ContactPreferenceFlags {
    private val values = mutableMapOf<String, Boolean>()
    override fun bool(key: String): Boolean = values[key] ?: false
    override fun set(key: String, value: Boolean) { values[key] = value }
}

internal class MemoryDraftDefaults : DraftDefaults {
    private val values = mutableMapOf<String, Map<String, String>>()
    override fun stringDictionary(key: String): Map<String, String>? = values[key]
    override fun setStringDictionary(value: Map<String, String>, key: String) { values[key] = value }
}

internal class RecordingLifecycle : SessionLifecycleListener {
    val created = mutableListOf<RadioSessionContainer>()
    val tornDown = mutableListOf<RadioSessionContainer>()
    override fun created(container: RadioSessionContainer) { created += container }
    override fun tornDown(container: RadioSessionContainer) { tornDown += container }
}

/** The production [AppContainer] composed over test roles; every connection runs the real service graph. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ContainerHarness(
    test: TestScope,
    val store: RoomPersistenceStore,
    identity: (ConnectionTarget) -> Bytes = { key(7) },
    deliveryOverride: RecordingDelivery? = null,
    primerFactory: com.meshcoreone.android.app.state.ChatPrimerFactory = com.meshcoreone.android.app.state.ChatPrimerFactory { null },
    chatContent: AppChatContent? = null,
    syncClock: com.meshcoreone.android.core.services.sync.SyncClock = com.meshcoreone.android.core.services.sync.SyncClock.SYSTEM,
    recoveryDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    runtimeClock: RuntimeClock = SchedulerClock { test.testScheduler.currentTime },
    runtimeContext: CoroutineContext = test.backgroundScope.coroutineContext,
    sessionConfiguration: SessionConfiguration = SessionConfiguration.DEFAULT,
    messageServiceConfig: MessageServiceConfig = MessageServiceConfig(),
    chatSendQueueConfig: ChatSendQueueConfig = ChatSendQueueConfig(),
    mainScope: CoroutineScope = test.backgroundScope,
) {
    val scheduler: TestCoroutineScheduler = test.testScheduler
    val links = HarnessLinks(identity)
    val delivery = deliveryOverride ?: RecordingDelivery()
    val pairing = HarnessPairingService()
    val foregroundCalls = mutableListOf<Boolean>()
    val preferences = MemoryConnectionPreferences()
    val lifecycle = RecordingLifecycle()
    val chatSendQueueProgress = ChatSendQueueProgress()
    val diagnostics = mutableListOf<RuntimeDiagnostic>()
    val messagingDiagnostics = java.util.Collections.synchronizedList(mutableListOf<com.meshcoreone.android.core.contracts.domain.MessagingDiagnostic>())
    val draftStore = DraftStore(MemoryDraftDefaults())
    val container = AppContainer(
        AppContainerDependencies(
            store = store,
            passwords = MemoryVault(),
            connectionPreferences = preferences,
            connectivity = newConnectivity(pairing) { foregroundCalls += it },
            linkProbe = NoSystemLinks,
            scans = newScans(),
            linkFactory = links,
            notificationDelivery = delivery,
            notificationPreferences = FixedNotificationPreferences(),
            contactPreferences = MemoryFlags(),
            draftStore = draftStore,
            chatSendQueueProgress = chatSendQueueProgress,
            mainScope = mainScope,
            runtimeClock = runtimeClock,
            syncClock = syncClock,
            runtimeContext = runtimeContext,
            sessionConfiguration = sessionConfiguration,
            messageServiceConfig = messageServiceConfig,
            chatSendQueueConfig = chatSendQueueConfig,
            runtimeRecoveryDispatcher = recoveryDispatcher,
            accessibility = SilentAnnouncements,
            sessionLifecycle = lifecycle,
            runtimeReporter = RuntimeIssueReporter { diagnostics += it },
            messagingReporter = com.meshcoreone.android.core.contracts.domain.MessagingIssueReporter { messagingDiagnostics += it },
            newBootstrapDebugLog = { DebugLogBuffer(store, it) },
            primerFactory = primerFactory,
            chatContent = chatContent,
        ),
    )
    val manager get() = container.connectionManager
    val appState get() = container.appState
    val sessions get() = container.sessions

    fun target(id: UUID = UUID.randomUUID()): ConnectionTarget.Bluetooth =
        ConnectionTarget.Bluetooth(BluetoothPairingHandle(BluetoothAddress(syntheticAddress(id)), null), id)

    /** Runs scheduled work, then yields real time so Default-dispatcher services can finish. */
    fun settle(rounds: Int = 5) {
        repeat(rounds) { scheduler.runCurrent(); scheduler.advanceUntilIdle(); Thread.sleep(2) }
    }

    /**
     * Polls until [condition] holds or five real seconds pass. Each poll runs scheduled work and advances virtual
     * time by [virtualStepMillis], up to [virtualBudgetMillis] in total, so short timers fire but a held radio reply
     * can never be turned into a protocol timeout.
     */
    fun eventually(
        message: String = "condition", virtualBudgetMillis: Long = 1_000, virtualStepMillis: Long = 1, condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 5_000_000_000L
        var advanced = 0L
        while (System.nanoTime() < deadline) {
            scheduler.runCurrent()
            if (condition()) return
            if (advanced < virtualBudgetMillis) { scheduler.advanceTimeBy(virtualStepMillis); advanced += virtualStepMillis }
            scheduler.runCurrent()
            if (condition()) return
            Thread.sleep(2)
        }
        throw AssertionError("Timed out waiting for $message (state=${manager.connectionState})")
    }

    fun assertReady() {
        check(manager.connectionState == DeviceConnectionState.READY) { "state=${manager.connectionState}" }
    }
}

/** Robolectric base: an in-memory Room database and the production [RoomPersistenceStore] over it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
abstract class RoomProcessTest {
    internal lateinit var db: MeshCoreDatabase
    internal lateinit var store: RoomPersistenceStore
    private lateinit var owner: CoroutineScope

    @Before fun openStore() {
        reportPathRecencyPhase("room-open|START")
        db = openMemoryDatabase()
        owner = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        store = RoomPersistenceStore(db, owner)
        reportPathRecencyPhase("room-open|END")
    }

    @After fun closeStore() {
        reportPathRecencyPhase("room-close|START")
        try { kotlinx.coroutines.runBlocking { store.close() } } finally {
            DebugLogBuffer.shared = null
            owner.cancel()
            db.close()
        }
        reportPathRecencyPhase("room-close|END")
    }

    protected fun reportPathRecencyPhase(phase: String) {
        if (javaClass.name == "com.meshcoreone.android.parity.AdvertisementPathRecencyOriginalPredicateTest") {
            println("WP501_PATH_RECENCY_PHASE|$phase")
        }
    }
}
