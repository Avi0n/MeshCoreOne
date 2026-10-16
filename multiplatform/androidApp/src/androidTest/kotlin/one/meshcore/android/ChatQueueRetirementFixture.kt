package one.meshcore.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.app.AppStateViewModel
import one.meshcore.shared.chat.ui.ChatThreadEnvironment
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.ServiceContainer
import one.meshcore.shared.connection.ServiceContainerIdentity
import one.meshcore.shared.connection.ServiceContainerRuntime
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.persistence.setChannelFloodScope
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_input_send_message
import org.jetbrains.compose.resources.getString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import java.io.File

internal class ChatQueueRetirementFixture(val inheritsFloodScope: Boolean = false) {
    val base = ChatRadioNotificationFixture()
    val gatedSession = ChatQueueRetirementGatedSession(base.recording.session)
    var teardown: Deferred<Unit>? = null
    lateinit var graph: ServiceContainer
        private set
    lateinit var environment: ChatThreadEnvironment
        private set
    lateinit var sendLabel: String
        private set
    private var matchingSetupFloodCalls = 0
    private val fixtureId = System.nanoTime().toString()
    val expectedFreshFloodText: String
        get() = if (inheritsFloodScope) DATA.B_DEFAULT_SCOPE else checkNotNull(DATA.channel(DATA.RADIO_B).regionScope)

    suspend fun open() {
        base.open()
        if (inheritsFloodScope) {
            setChannelFloodScope(base.container.database, DATA.CHANNEL_B, ChannelFloodScope.Inherit)
            base.container.offlineData.refresh()
        }
        graph = base.graph
        environment = onMain {
            base.container.buildChatEnvironment(ChatRadioNotificationData.destination(DATA.RADIO_B))
        }
        sendLabel = getString(Res.string.chats_input_send_message)
        stage("isolated-b-ready")
    }

    suspend fun showThread() {
        base.postAndTap(DATA.RADIO_B)
        base.ui.assertThread(DATA.HISTORY_B, DATA.DRAFT_B, sendLabel, enabled = true)
        withTimeout(TIMEOUT_MS) {
            while (base.recording.floodCalls.get() < INITIAL_SETUP_FLOOD_CALLS) delay(POLL_MS)
        }
        assertEquals(INITIAL_SETUP_FLOOD_CALLS, base.recording.floodCalls.get())
        matchingSetupFloodCalls = INITIAL_SETUP_FLOOD_CALLS
        stage("same-owner-mounted-enabled")
    }

    suspend fun installFreshGraph() {
        val session = gatedSession
        val expectedBatteryReads = base.recording.batteryReads.get() + 1
        onMain {
            graph = ServiceContainer(
                session = session,
                database = base.container.database,
                identity = ServiceContainerIdentity(
                    DATA.RADIO_B,
                    DATA.device(DATA.RADIO_B).publicKey,
                    DATA.B_NODE_NAME,
                ),
                runtime = ServiceContainerRuntime(
                    scope = base.container.appScope,
                    nodePasswordStore = InMemoryNodePasswordStore(),
                    nowEpochSeconds = { System.currentTimeMillis().toDouble() / MILLIS_PER_SECOND },
                ),
            )
            val manager = base.container.connectionManager
            field("services").set(manager, graph)
            field("session").set(manager, session)
            state().value = ConnectionState.Connected(DATA.DEVICE_B.value)
        }
        withTimeout(TIMEOUT_MS) {
            base.container.appState.connectionState.first { it == DeviceConnectionState.CONNECTED }
            while (!hasCollectedFreshOwner()) delay(POLL_MS)
            while (base.recording.batteryReads.get() < expectedBatteryReads) delay(POLL_MS)
        }
        assertEquals(expectedBatteryReads, base.recording.batteryReads.get())
        stage("fresh-connected-owner-and-bootstrap-acknowledged")
        withTimeout(TIMEOUT_MS) {
            while (gatedSession.freshFloodCalls.isEmpty()) delay(POLL_MS)
        }
        assertFreshFloodCommands()
        assertEquals(FRESH_SETUP_FLOOD_CALLS, base.recording.floodCalls.get())
        matchingSetupFloodCalls = FRESH_SETUP_FLOOD_CALLS
        stage("fresh-connected-exact-region-push-before-ready")
        base.ui.assertThread(DATA.HISTORY_B, DATA.DRAFT_B, sendLabel, enabled = false)
        onMain { state().value = ConnectionState.Ready(DATA.DEVICE_B.value) }
        withTimeout(TIMEOUT_MS) {
            base.container.appState.connectionState.first { it == DeviceConnectionState.READY }
        }
        base.ui.assertThread(DATA.HISTORY_B, DATA.DRAFT_B, sendLabel, enabled = true)
        assertFreshFloodCommands()
        assertEquals(FRESH_SETUP_FLOOD_CALLS, base.recording.floodCalls.get())
        matchingSetupFloodCalls = FRESH_SETUP_FLOOD_CALLS
        stage("fresh-same-radio-owner-ready")
    }

    fun stage(label: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val receipt = JSONObject()
            .put("fixtureId", fixtureId)
            .put("stage", label)
            .put("syntheticOnly", true)
            .put("actualAppRoot", true)
            .put("actualRoom", true)
            .put("inheritsFloodScope", inheritsFloodScope)
            .put("elapsedRealtimeMs", android.os.SystemClock.elapsedRealtime())
            .put("ambientBatteryReads", base.recording.batteryReads.get())
            .put("intentionalStopFetchCalls", gatedSession.stopCalls.get())
            .put("otherSessionMethods", base.recording.otherMethods.toList())
            .put("floodCalls", base.recording.floodCalls.get())
            .put("matchingSetupFloodCalls", matchingSetupFloodCalls)
            .put("retirementFloodDelta", base.recording.floodCalls.get() - matchingSetupFloodCalls)
            .put(
                "freshFloodCommands",
                JSONArray(
                    gatedSession.freshFloodCalls.map { command ->
                        JSONObject()
                            .put("type", command.type)
                            .put("text", command.text)
                            .put("keyLength", command.keyLength)
                    },
                ),
            )
        val directory = File(context.getExternalFilesDir(null), "chat-queue-retirement").apply { mkdirs() }
        File(directory, "$fixtureId-$label.json").writeText(receipt.toString(2))
        println(receipt)
    }

    suspend fun close() {
        try {
            gatedSession.releaseStop.complete(Unit)
            teardown?.let { owned ->
                owned.cancel()
                withTimeout(TIMEOUT_MS) { owned.join() }
            }
            stage("cleanup-entered")
        } finally {
            base.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun state(): MutableStateFlow<ConnectionState> = field("mutableState")
        .get(base.container.connectionManager) as MutableStateFlow<ConnectionState>

    private fun field(name: String) = ConnectionManager::class.java.getDeclaredField(name).apply { isAccessible = true }

    private suspend fun hasCollectedFreshOwner(): Boolean = onMain {
        val appState = base.container.appState
        fun observed(name: String) = AppStateViewModel::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(appState)
        state().value == ConnectionState.Connected(DATA.DEVICE_B.value) &&
            observed("collectedServices") === graph &&
            observed("collectedSession") === gatedSession
    }

    companion object {
        const val TIMEOUT_MS = 8_000L
        private const val POLL_MS = 25L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val INITIAL_SETUP_FLOOD_CALLS = 1
        private const val FRESH_SETUP_FLOOD_CALLS = 2
        private val DATA = ChatRadioNotificationData
    }
}

internal suspend fun <T> ChatQueueRetirementFixture.onMain(action: suspend () -> T): T =
    withContext(Dispatchers.Main.immediate) { action() }

internal suspend fun ChatQueueRetirementFixture.awaitCancellation(expected: CancellationException) {
    val failure = try {
        withTimeout(ChatQueueRetirementFixture.TIMEOUT_MS) { checkNotNull(teardown).await() }
        null
    } catch (cancelled: CancellationException) {
        cancelled
    }
    org.junit.Assert.assertSame(expected, failure)
}
