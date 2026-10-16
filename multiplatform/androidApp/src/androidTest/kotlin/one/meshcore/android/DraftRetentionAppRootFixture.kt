package one.meshcore.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.os.Process
import android.view.Window
import androidx.lifecycle.Lifecycle
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.BackupEnvelopeBuilder
import one.meshcore.shared.backup.BackupExportInput
import one.meshcore.shared.backup.decodeBackupEnvelope
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatDraftEditor
import one.meshcore.shared.chat.DraftStore
import one.meshcore.shared.chat.draftStorageKey
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.ChatRoute
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.io.File

internal class DraftRetentionAppRootFixture(private val nonce: String) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as MeshCoreApplication
    val container = application.container
    lateinit var channelRoute: ChatRoute.Channel
        private set
    private val rows = BackupPostcommitRows(container.database)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var backgroundObservation: DraftBackgroundObservation? = null
    private var backgroundActivity: MainActivity? = null
    private var backgroundWindow: Window? = null
    private val launcherIntent = checkNotNull(application.packageManager.getLaunchIntentForPackage(TARGET))
    private val editors = mutableMapOf<ChatConversationID, ChatDraftEditor>()
    private val phases = mutableListOf<String>()
    private var seededRows: Map<String, List<Map<String, Any?>>>? = null
    val directory = File(application.getExternalFilesDir(null), "draft-retention-$nonce")

    init {
        require(Regex("[0-9a-f]{32}").matches(nonce))
        identity()
    }

    fun open() = runBlocking {
        identity()
        assertFalse(directory.exists())
        assertTrue(directory.mkdirs())
        withTimeout(TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                container.awaitInitialOfflineData()
                noConnection()
                assertNull(container.currentRadioId())
                assertNull(container.prefs.getString(DraftStore.STORAGE_KEY))
            }
            rows.assertCounts(rows.snapshot(), copies = 0)
            val base = decodeBackupEnvelope(
                instrumentation.context.assets.open("backup-postcommit.json").bufferedReader().use { it.readText() },
            )
            assertEquals(RADIO, base.devices.single().radioID)
            assertEquals(CONTACT, base.contacts.single().id)
            assertEquals(CHANNEL_ID, base.channels.single().id)
            val json = BackupEnvelopeBuilder.encode(
                BackupExportInput(
                    appVersion = "fixture",
                    appBuild = "draft-retention",
                    exportDate = 0.0,
                    devices = base.devices.map { it.copy(isActive = false, connectionMethods = emptyList()) },
                    contacts = base.contacts.map {
                        it.copy(
                            name = DM_NAME,
                            nickname = null,
                            typeRawValue = ContactType.CHAT.raw,
                            isBlocked = false,
                            unreadCount = 0,
                            unreadMentionCount = 0,
                        )
                    },
                    channels = base.channels.map {
                        it.copy(name = CHANNEL_NAME, isEnabled = true, unreadCount = 0, unreadMentionCount = 0)
                    },
                    messages = base.messages.map { it.copy(isRead = true, direction = MessageDirection.INCOMING) },
                ),
            )
            withContext(Dispatchers.Main.immediate) {
                assertEquals(INSERTED, container.importBackupJson(json).totalInserted)
                container.onboarding.complete()
                noConnection()
                assertEquals(RADIO, container.currentRadioId())
            }
        }
        val imported = container.database.channelDao().listByRadio(RADIO).single()
        assertEquals(CHANNEL_SLOT, imported.index)
        assertEquals(CHANNEL_NAME, imported.name)
        channelRoute = ChatRoute.Channel(imported.id.value)
        seededRows = rows.snapshot()
        assertEquals(Intent.ACTION_MAIN, launcherIntent.action)
        assertTrue(launcherIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(launcherIntent.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals(TARGET, launcherIntent.component?.packageName)
        assertEquals(MainActivity::class.java.name, launcherIntent.component?.className)
        scenario = ActivityScenario.launch<MainActivity>(Intent(launcherIntent))
        checkNotNull(scenario).onActivity(::assertOwnedWindow)
    }

    suspend fun noConnection() {
        assertFalse(container.connectionManager.state.value.isConnected)
        assertNull(container.connectionManager.services)
        assertNull(container.connectionManager.radioId)
        assertNull(container.lastConnectionStore.deviceId())
    }

    fun editor(id: ChatConversationID): ChatDraftEditor {
        val editor = container.draftEditors.editor(id)
        editors[id]?.let { assertSame(it, editor) }
        editors[id] = editor
        return editor
    }

    fun assertActualGraph(route: ChatRoute, expected: ChatConversationID) = runBlocking {
        withTimeout(TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                noConnection()
                assertEquals(route.conversationId, container.coordinator.chatsSelectedRoute.value?.conversationId)
                val destination = checkNotNull(container.chatThreadBinder.resolveDestination(route))
                assertEquals(expected, destination.conversationID)
                val environment = container.chatThreadBinder.environment(destination)
                assertSame(container.draftEditors, environment.draftEditors())
                assertSame(editor(expected), checkNotNull(environment.draftEditors()).editor(expected))
            }
        }
    }

    suspend fun saved(id: ChatConversationID): String? {
        val raw = container.prefs.getString(DraftStore.STORAGE_KEY) ?: return null
        val values = JSONObject(raw)
        return if (values.has(id.draftStorageKey)) values.getString(id.draftStorageKey) else null
    }

    fun awaitSaved(id: ChatConversationID, expected: String) = runBlocking {
        withTimeout(TIMEOUT_MS) {
            while (saved(id) != expected) delay(POLL_MS)
        }
    }

    fun recreate() {
        val owner = checkNotNull(scenario)
        var old: MainActivity? = null
        var oldWindow: Window? = null
        owner.onActivity {
            assertOwnedWindow(it)
            old = it
            oldWindow = it.window
        }
        owner.recreate()
        owner.onActivity {
            assertOwnedWindow(it)
            assertTrue(it !== old && it.window !== oldWindow)
            for ((id, retained) in editors) assertSame(retained, container.draftEditors.editor(id))
        }
        phase("recreated", JSONObject().put("distinctActivityAndWindow", true).put("sameEditors", true))
    }

    fun armBackground(): () -> Boolean {
        assertNull(backgroundObservation)
        var request: (() -> Boolean)? = null
        checkNotNull(scenario).onActivity {
            assertOwnedWindow(it)
            backgroundActivity = it
            backgroundWindow = it.window
            backgroundObservation = DraftBackgroundObservation(it) { container.notificationCoordinator.isForeground }
            request = {
                check(Looper.myLooper() == Looper.getMainLooper())
                it.moveTaskToBack(true)
            }
        }
        return checkNotNull(request)
    }

    fun background(editNanos: Long, requestReturnedNanos: Long, requestAccepted: Boolean) {
        val result = checkNotNull(backgroundObservation).result(editNanos, requestReturnedNanos)
        phase(
            "background",
            result.put("systemRequestAccepted", requestAccepted).put("exclusiveHostFlushAttribution", false),
        )
        assertTrue(requestAccepted)
        assertTrue(result.getBoolean("snapshotReadOnMain") && result.getBoolean("afterDispatchAwaitCompleted"))
        val event = result.getLong("eventElapsedNanos")
        val admission = result.getLong("afterDispatchElapsedNanos")
        assertEquals(1, result.getInt("eventCount"))
        assertTrue(result.getBoolean("sameOwner") && result.getBoolean("postAccepted"))
        assertTrue(result.getBoolean("afterDispatchObserved") && result.getBoolean("stoppedAfterDispatch"))
        assertFalse(result.getBoolean("foregroundAfterDispatch"))
        assertTrue("Observed ON_STOP admission must precede debounce", event in 1..admission && admission < DEBOUNCE_NS)
        checkNotNull(scenario).onActivity {
            identity()
            assertSame(backgroundActivity, it)
            assertSame(backgroundWindow, it.window)
            assertEquals(Lifecycle.State.CREATED, it.lifecycle.currentState)
            assertFalse(container.notificationCoordinator.isForeground)
        }
    }

    fun resume() {
        application.startActivity(Intent(launcherIntent))
        assertTrue(checkNotNull(backgroundObservation).awaitResumed())
        val observation = checkNotNull(backgroundObservation).awaitWindowReady(::resumeSnapshot)
        phase("resumed", observation)
        assertTrue(observation.getBoolean("snapshotObserved") && observation.getBoolean("probeRequestAccepted"))
        assertTrue(observation.getBoolean("waitCompleted"))
        assertTrue(observation.getJSONObject("final").getBoolean("ready"))
        assertTrue(observation.getLong("waitElapsedMs") in 0..TIMEOUT_MS)
        checkNotNull(scenario).onActivity {
            assertSame(backgroundActivity, it)
            assertSame(backgroundWindow, it.window)
            assertOwnedWindow(it)
            assertTrue(container.notificationCoordinator.isForeground)
        }
    }

    fun rapidNavigation(name: String, nanos: Long) {
        phase(name, JSONObject().put("callbackSpanNanos", nanos).put("beforeDebounce", nanos in 1 until DEBOUNCE_NS))
        assertTrue("UI edit and Up must finish before debounce", nanos in 1 until DEBOUNCE_NS)
    }

    fun assertNoOutbound() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) { noConnection() }
            val snapshot = rows.snapshot()
            assertEquals(checkNotNull(seededRows), snapshot)
            assertTrue(snapshot.getValue("pending_send").isEmpty())
            assertEquals(MESSAGE_COUNT, snapshot.getValue("message").size)
            assertTrue(
                snapshot.getValue("message").all { row ->
                    row.getValue("directionRawValue") == (SQLITE_DATA_INTEGER to MessageDirection.INCOMING.raw.toLong())
                },
            )
        }
    }

    fun phase(name: String, values: JSONObject) {
        require(Regex("[a-z-]+").matches(name))
        assertFalse(name in phases)
        phases += name
        File(directory, "$name.json").writeText(values.put("nonce", nonce).put("phase", name).toString())
    }

    fun screenshot(name: String) {
        require(Regex("[a-z-]+").matches(name))
        val owner = checkNotNull(scenario)
        var window: Window? = null
        owner.onActivity {
            assertOwnedWindow(it)
            window = it.window
        }
        val selected = checkNotNull(window)
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot(selected))
        try {
            owner.onActivity {
                assertOwnedWindow(it)
                assertTrue(it.window === selected)
            }
            File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun record() {
        identity()
        assertTrue(editors.keys == setOf(DM, CHANNEL))
        File(directory, "outcome.json").writeText(
            JSONObject().put("nonce", nonce).put("passed", true).put("pid", Process.myPid()).put("uid", Process.myUid())
                .put("actualApplication", application.javaClass.name).put("actualContainer", container.javaClass.name)
                .put("sameContainer", true).put("sameDmAndChannelEditors", true).put("noOutbound", true)
                .put("phases", JSONArray(phases)).put("processLossTest", false).put("optimizedTest", false).toString(),
        )
    }

    fun close() {
        var closed = false
        try {
            try {
                scenario?.onActivity { backgroundObservation?.close() }
            } finally {
                scenario?.close()
                closed = true
            }
        } finally {
            if (directory.exists()) {
                File(directory, "teardown.json").writeText(
                    JSONObject().put("nonce", nonce).put("activityClosed", closed)
                        .put("sameContainer", application.container === container).toString(),
                )
            }
        }
    }

    private fun resumeSnapshot(activity: MainActivity): JSONObject {
        val decor = activity.window.decorView
        val flags = mapOf(
            "sameActivity" to (activity === backgroundActivity),
            "sameWindow" to (activity.window === backgroundWindow),
            "sameApplication" to (activity.application === application),
            "sameContainer" to (application.container === container),
            "resumed" to (activity.lifecycle.currentState == Lifecycle.State.RESUMED),
            "foreground" to container.notificationCoordinator.isForeground,
            "attached" to decor.isAttachedToWindow,
            "shown" to decor.isShown,
            "focused" to decor.hasWindowFocus(),
        )
        val snapshot = JSONObject()
        for ((key, value) in flags) snapshot.put(key, value)
        return snapshot.put("width", decor.width).put("height", decor.height)
            .put("ready", flags.values.all { it } && decor.width > 0 && decor.height > 0)
    }

    private fun assertOwnedWindow(activity: MainActivity) {
        identity()
        assertTrue(activity.application === application && activity.packageName == TARGET)
        assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
        val decor = activity.window.decorView
        assertTrue(decor.isAttachedToWindow && decor.isShown && decor.hasWindowFocus())
        assertTrue(decor.width > 0 && decor.height > 0)
    }

    private fun identity() {
        assertTrue(application.packageName == TARGET)
        assertEquals(application.applicationInfo.uid, Process.myUid())
        assertSame(container, application.container)
    }

    companion object {
        const val TARGET = "io.pocketmesh.app.debug"
        const val RUN_ARGUMENT = "draftRetentionAppRootRun"
        const val TIMEOUT_MS = 20_000L
        const val DEBOUNCE_NS = 500_000_000L
        const val DM_NAME = "Draft DM"
        const val CHANNEL_NAME = "Draft Channel"
        const val DM_TEXT = "Draft north trail"
        const val CHANNEL_TEXT = "Draft east trail"
        const val BACKGROUND_TEXT = "Draft return via ridge"
        private const val POLL_MS = 25L
        private const val PNG_QUALITY = 100
        private const val MESSAGE_COUNT = 2
        private const val INSERTED = 5
        val RADIO = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val CONTACT = Uuid.normalized("C0000000-0000-0000-0000-000000000001")
        val CHANNEL_ID = Uuid.normalized("C0000000-0000-0000-0000-0000000000AA")
        val DM = ChatConversationID.dm(RADIO, CONTACT)
        private const val CHANNEL_SLOT = 1
        val CHANNEL = ChatConversationID.channel(RADIO, CHANNEL_SLOT)
    }
}
