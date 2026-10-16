package one.meshcore.android

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionIntent
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.ServiceContainer
import one.meshcore.shared.connection.ServiceContainerIdentity
import one.meshcore.shared.connection.ServiceContainerRuntime
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.notification.ChannelMessageNotification
import one.meshcore.shared.notification.NotificationChannelKey
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.transport.MeshLink
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

internal class ChatRadioNotificationFixture {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as MeshCoreApplication
    private val original = application.container
    private val fixtureId = System.nanoTime().toString()
    private val root = File(application.cacheDir, "chat-radio-$fixtureId")
    private val allocations = AtomicInteger()
    private var installed = false
    private var actionBatteryBaseline: Int? = null
    lateinit var container: AppContainer
        private set
    val recording = ChatRadioNotificationSession()
    val ui = ChatRadioNotificationUi(::stage)
    lateinit var graph: ServiceContainer
        private set

    suspend fun open() {
        stage("open-entered")
        check(application.packageName == DEBUG_PACKAGE)
        check(original.connectionManager.services == null)
        check(field(ConnectionManager::class.java, "link").get(original.connectionManager) == null)
        check(field(ConnectionManager::class.java, "session").get(original.connectionManager) == null)
        check(original.connectionManager.state.value == ConnectionState.Disconnected)
        check(original.lastConnectionStore.deviceId() == null)
        root.mkdirs()
        withContext(Dispatchers.Main.immediate) { container = AppContainer(IsolatedContext(application, root)) }
        container.awaitInitialOfflineData()
        container.onboarding.complete()
        container.intentStore.persist(ConnectionIntent.UserDisconnected)
        installTransportGuards()
        seed()
        stage("isolated-store-seeded")
        instrumentation.runOnMainSync { applicationField.set(application, container) }
        installed = true
        launchActivity()
        stage("activity-foreground-arrived")
        withContext(Dispatchers.Main.immediate) { installReadyGraph() }
        stage("scripted-b-ready")
        withTimeout(TIMEOUT_MS) {
            container.appState.connectionState.first { it == DeviceConnectionState.READY }
            while (recording.batteryReads.get() == 0) delay(POLL_MS)
        }
        withContext(Dispatchers.Main.immediate) { container.refreshConnectedFloodScopeInputs() }
        withTimeout(TIMEOUT_MS) {
            container.localNodeNameFlow.first { it == ChatRadioNotificationData.B_NODE_NAME }
            container.connectedDefaultFloodScopeName.first { it == ChatRadioNotificationData.B_DEFAULT_SCOPE }
        }
        assertEquals(1, recording.batteryReads.get())
        stage("ready-local-inputs-loaded-ambient-battery-one")
    }

    suspend fun postAndTap(radio: Uuid) {
        val data = ChatRadioNotificationData
        actionBatteryBaseline = recording.batteryReads.get()
        AndroidNotificationPoster(application).postChannel(
            ChannelMessageNotification(
                radioID = radio,
                channelIndex = data.INDEX,
                messageID = if (radio == data.RADIO_A) data.MESSAGE_A else data.MESSAGE_B,
                title = if (radio == data.RADIO_A) data.TITLE_A else data.TITLE_B,
                body = data.NOTIFICATION_BODY,
                playSound = false,
                channelKey = NotificationChannelKey.MESSAGES,
            ),
        )
        stage("synthetic-notification-posted")
        ui.tapPostedBody()
        stage("systemui-body-clicked")
    }

    suspend fun assertNoOutbound() {
        stage("outbound-boundary-checked")
        assertEquals(0, allocations.get())
        assertEquals(0, recording.otherCalls.get())
        assertEquals(1, recording.batteryReads.get())
        assertEquals(actionBatteryBaseline, recording.batteryReads.get())
        val data = ChatRadioNotificationData
        assertEquals(
            setOf(data.MESSAGE_A, data.MESSAGE_B),
            container.database.messageDao().listAll().map { it.id }.toSet(),
        )
        assertTrue(container.database.pendingSendDao().listByRadio(data.RADIO_A).isEmpty())
        assertTrue(container.database.pendingSendDao().listByRadio(data.RADIO_B).isEmpty())
        assertTrue(container.database.pendingSendDao().listOrphans().isEmpty())
    }

    suspend fun record(label: String, sendEnabled: Boolean) {
        val data = ChatRadioNotificationData
        val receipt = JSONObject()
            .put("label", label)
            .put("fixtureId", fixtureId)
            .put("notificationTap", "SystemUI synthetic body click")
            .put("controlledLifecycle", "saved disconnect intent skips scripted graph health reconciliation")
            .put("actualAppRoot", true)
            .put("sendEnabled", sendEnabled)
            .put("floodCalls", recording.floodCalls.get())
            .put("otherSessionCalls", recording.otherCalls.get())
            .put("otherSessionMethods", recording.otherMethods.toList())
            .put("ambientBatteryReads", recording.batteryReads.get())
            .put("actionBatteryReads", recording.batteryReads.get() - checkNotNull(actionBatteryBaseline))
            .put("transportAllocations", allocations.get())
            .put("messageCount", container.database.messageDao().listAll().size)
            .put("pendingA", container.database.pendingSendDao().listByRadio(data.RADIO_A).size)
            .put("pendingB", container.database.pendingSendDao().listByRadio(data.RADIO_B).size)
            .put(
                "draftARetained",
                container.draftStore.draft(data.destination(data.RADIO_A).conversationID) == data.DRAFT_A,
            )
        val evidence = File(application.getExternalFilesDir(null), "chat-radio-notification").apply { mkdirs() }
        File(evidence, "$fixtureId-$label.json").writeText(receipt.toString(2))
        println(receipt)
    }

    suspend fun close() {
        try {
            stage("teardown-entered")
            if (installed) closeActivities()
        } finally {
            application.getSystemService(NotificationManager::class.java).cancelAll()
            application.stopService(Intent(application, ConnectionService::class.java))
            if (::container.isInitialized) {
                withContext(Dispatchers.Main.immediate) {
                    field(ConnectionManager::class.java, "services").set(container.connectionManager, null)
                    field(ConnectionManager::class.java, "session").set(container.connectionManager, null)
                    withTimeout(TIMEOUT_MS) { container.appScope.coroutineContext[Job]?.cancelAndJoin() }
                }
            }
            if (installed) {
                instrumentation.runOnMainSync {
                    check(application.container === container)
                    applicationField.set(application, original)
                }
            }
            if (::container.isInitialized) container.database.close()
            root.deleteRecursively()
        }
        assertTrue(application.container === original)
    }

    private suspend fun closeActivities() {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        withContext(Dispatchers.Main.immediate) {
            Stage.values().flatMap { monitor.getActivitiesInStage(it) }
                .filterIsInstance<MainActivity>().distinct().forEach { it.finish() }
        }
        withTimeout(TIMEOUT_MS) {
            while (
                withContext(Dispatchers.Main.immediate) {
                    Stage.values().filter { it != Stage.DESTROYED }
                        .any { monitor.getActivitiesInStage(it).any { activity -> activity is MainActivity } }
                }
            ) {
                delay(POLL_MS)
            }
        }
        stage("own-activities-destroyed")
    }

    private fun stage(label: String) {
        val receipt = JSONObject()
            .put("fixtureId", fixtureId)
            .put("stage", label)
            .put("elapsedRealtimeMs", android.os.SystemClock.elapsedRealtime())
            .put("floodCalls", recording.floodCalls.get())
            .put("otherMethods", recording.otherMethods.toList())
            .put("ambientBatteryReads", recording.batteryReads.get())
            .put("actionBatteryReads", actionBatteryBaseline?.let { recording.batteryReads.get() - it })
        val evidence = File(application.getExternalFilesDir(null), "chat-radio-notification").apply { mkdirs() }
        File(evidence, "$fixtureId-stage-$label.json").writeText(receipt.toString(2))
        println(receipt)
    }

    private suspend fun seed() {
        val data = ChatRadioNotificationData
        for (radio in listOf(data.RADIO_A, data.RADIO_B)) {
            container.database.deviceDao().upsert(data.device(radio))
            container.database.channelDao().upsert(data.channel(radio))
            container.database.messageDao().upsert(data.message(radio))
            container.draftStore.setDraft(
                if (radio == data.RADIO_A) data.DRAFT_A else data.DRAFT_B,
                data.destination(radio).conversationID,
            )
        }
        container.lastConnectionStore.persist(
            data.DEVICE_B,
            data.RADIO_B,
            data.device(data.RADIO_B).nodeName,
            data.DEVICE_B.value,
        )
        container.offlineData.refresh()
    }

    private suspend fun launchActivity() = coroutineScope {
        val foreground = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(TIMEOUT_MS) { container.appForeground.first() }
        }
        instrumentation.targetContext.startActivity(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        foreground.await()
    }

    private fun installReadyGraph() {
        val data = ChatRadioNotificationData
        graph = ServiceContainer(
            session = recording.session,
            database = container.database,
            identity = ServiceContainerIdentity(data.RADIO_B, data.device(data.RADIO_B).publicKey, "Synthetic B radio"),
            runtime = ServiceContainerRuntime(
                scope = container.appScope,
                nodePasswordStore = InMemoryNodePasswordStore(),
                nowEpochSeconds = { System.currentTimeMillis().toDouble() / MILLIS_PER_SECOND },
            ),
        )
        val manager = container.connectionManager
        field(ConnectionManager::class.java, "services").set(manager, graph)
        field(ConnectionManager::class.java, "session").set(manager, recording.session)
        container.localNodeNameFlow.value = data.B_NODE_NAME
        container.connectedDefaultFloodScopeName.value = data.B_DEFAULT_SCOPE
        container.connectedKnownRegions.set(listOf(data.B_KNOWN_REGION))
        container.connectedSupportsUnscopedFloodSend.set(true)
        @Suppress("UNCHECKED_CAST")
        val state = field(ConnectionManager::class.java, "mutableState").get(manager)
            as MutableStateFlow<ConnectionState>
        state.value = ConnectionState.Ready(data.DEVICE_B.value)
    }

    private fun installTransportGuards() {
        val refuseBle: (String) -> MeshLink = { refuseTransport() }
        val refuseWifi: (String, Int, suspend () -> Unit) -> MeshLink = { _, _, _ -> refuseTransport() }
        val manager = container.connectionManager
        field(ConnectionManager::class.java, "linkFactory").set(manager, refuseBle)
        field(ConnectionManager::class.java, "reconnectLinkFactory").set(manager, refuseBle)
        field(ConnectionManager::class.java, "wifiLinkFactory").set(manager, refuseWifi)
    }

    private fun refuseTransport(): Nothing {
        allocations.incrementAndGet()
        error("Physical transport allocation is forbidden in the synthetic chat fixture")
    }

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = directory("files")

        override fun getCacheDir(): File = directory("cache")

        override fun getNoBackupFilesDir(): File = directory("nobackup")

        override fun getDataDir(): File = root

        override fun getDatabasePath(name: String): File = File(directory("databases"), name)

        private fun directory(name: String): File = File(root, name).apply { mkdirs() }
    }

    private companion object {
        const val DEBUG_PACKAGE = "io.pocketmesh.app.debug"
        const val TIMEOUT_MS = 8_000L
        const val POLL_MS = 25L
        const val MILLIS_PER_SECOND = 1_000L
        val applicationField = field(MeshCoreApplication::class.java, "container")

        fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    }
}
