package one.meshcore.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.BackupEnvelopeBuilder
import one.meshcore.shared.backup.BackupExportInput
import one.meshcore.shared.backup.decodeBackupEnvelope
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.DraftStore
import one.meshcore.shared.chat.draftStorageKey
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class AppPendingColdFixture(private val nonce: String, private val phase: String) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as MeshCoreApplication
    val container = application.container
    val rows = AppPendingColdRows(container.database)
    private val directory = File(application.filesDir, "pending-cold-$nonce")
    private var scenario: ActivityScenario<MainActivity>? = null
    private val conversation = ChatConversationID.channel(AppPendingColdRows.RADIO, AppPendingColdRows.SLOT)

    init {
        require(Regex("[0-9a-f]{32}").matches(nonce))
        check(application.packageName == PACKAGE)
        val args = InstrumentationRegistry.getArguments()
        check(args.getString(PHASE_ARGUMENT) == phase)
        val method = when (phase) {
            "seed" -> "seedDefaultAppPendingAndHoldOpenRoom"
            "verify" -> "freshDefaultAppRestoresPendingBeforeOpeningActivity"
            else -> error("Unknown pending-cold phase")
        }
        check(args.getString("class") == "one.meshcore.android.AppPendingColdInstrumentedTest#$method")
    }

    suspend fun seed() {
        prepare()
        assertFalse(directory.exists())
        assertTrue(directory.mkdir())
        val before = JSONObject(rows.snapshot())
        assertTrue(before.keys().asSequence().all { before.getJSONArray(it).length() == 0 })
        val base = decodeBackupEnvelope(
            instrumentation.context.assets.open("backup-postcommit.json").bufferedReader().use { it.readText() },
        )
        val envelope = BackupEnvelopeBuilder.encode(
            BackupExportInput(
                appVersion = "fixture", appBuild = "default-cold", exportDate = 0.0,
                devices = base.devices.map { it.copy(isActive = false, connectionMethods = emptyList()) },
                contacts = base.contacts, messages = base.messages,
                channels = base.channels.map {
                    it.copy(name = AppPendingColdRows.CHANNEL, secret = AppPendingColdRows.SECRET)
                },
                reactions = base.reactions, messageRepeats = base.messageRepeats,
                remoteNodeSessions = base.remoteNodeSessions, roomMessages = base.roomMessages,
                savedTracePaths = base.savedTracePaths, blockedChannelSenders = base.blockedChannelSenders,
                nodeStatusSnapshots = base.nodeStatusSnapshots, discoveredNodes = base.discoveredNodes,
            ),
        )
        withContext(Dispatchers.Main.immediate) {
            assertEquals(BackupPostcommitRows.INSERTED, container.importBackupJson(envelope).totalInserted)
            container.onboarding.complete()
        }
        assertOffline()
        rows.seedPending()
    }

    suspend fun verifyCold() {
        prepare()
        assertTrue(directory.isDirectory)
        val original = JSONObject(File(directory, "seed-held.json").readText())
        assertEquals(nonce, original.getString("nonce"))
        assertEquals("seed-held", original.getString("stage"))
        assertEquals("seed", original.getString("phase"))
        assertTrue(original.getInt("pid") > 0)
        assertTrue(original.getLong("heldUntilElapsedMs") > original.getLong("elapsedRealtimeMs"))
        assertTrue(original.getBoolean("actualDefaultApplication"))
        assertFalse(original.getBoolean("graphReplaced"))
        assertEquals(1, original.getInt("pendingRows"))
        assertEquals(0, original.getInt("attemptCount"))
        assertEquals(AppPendingColdRows.TABLE_COUNT, original.getInt("snapshotTables"))
        assertEquals(AppPendingColdRows.SCHEMA, original.getLong("schemaVersion"))
        assertEquals(DATABASE, original.getString("databaseName"))
        assertTrue(original.getBoolean("databaseConnectionHeld"))
        assertTrue(original.getBoolean("assertionsComplete"))
        val expected = File(directory, "expected.json").readBytes()
        assertEquals(expected.size.toLong(), original.getLong("snapshotBytes"))
        assertEquals(digest(expected), original.getString("snapshotSha256"))
        assertEquals(Process.myUid(), original.getInt("uid"))
        assertTrue(Process.myPid() != original.getInt("pid"))
        assertEquals(File(directory, "expected.json").readText(), rows.snapshot())
        rows.assertPending()
        assertEquals(AppPendingColdRows.DRAFT, savedDraft())
        record("cold-before-ui", heldUntil = null)
    }

    private suspend fun prepare() {
        withContext(Dispatchers.Main.immediate) { container.awaitInitialOfflineData() }
        rows.assertSchema()
        assertOffline()
    }

    suspend fun assertOffline() {
        assertTrue(application.container === container)
        for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) {
            assertEquals(PackageManager.PERMISSION_DENIED, application.checkSelfPermission(permission))
        }
        withContext(Dispatchers.Main.immediate) {
            assertEquals(ConnectionState.Disconnected, container.connectionManager.state.value)
            assertNull(container.connectionManager.services)
            assertNull(container.lastConnectionStore.deviceId())
            assertNull(container.lastConnectionStore.handle())
            for (name in listOf("link", "session")) {
                val field = ConnectionManager::class.java.getDeclaredField(name).apply { isAccessible = true }
                assertNull(field.get(container.connectionManager))
            }
        }
        assertTrue(container.database.deviceDao().listAll().all { it.connectionMethods.isEmpty() })
    }

    suspend fun launch() = coroutineScope {
        val foreground = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(TIMEOUT_MS) { container.appForeground.first() }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        checkNotNull(scenario).onActivity {
            assertTrue((it.application as MeshCoreApplication).container === container)
        }
        foreground.await()
    }

    suspend fun persistBackgroundDraft() {
        checkNotNull(scenario).moveToState(Lifecycle.State.CREATED)
        withTimeout(TIMEOUT_MS) {
            while (savedDraft() != AppPendingColdRows.DRAFT) delay(POLL_MS)
        }
        assertOffline()
    }

    private suspend fun savedDraft(): String? {
        val raw = container.prefs.getString(DraftStore.STORAGE_KEY) ?: return null
        return JSONObject(raw).optString(conversation.draftStorageKey).takeIf { it.isNotEmpty() }
    }

    suspend fun holdSeed() {
        rows.assertPending()
        assertOffline()
        write("expected.json", rows.snapshot())
        container.database.useConnection(isReadOnly = true) { connection ->
            connection.usePrepared("SELECT 1") { statement -> assertTrue(statement.step()) }
            record("seed-held", SystemClock.elapsedRealtime() + HOLD_MS)
            delay(HOLD_MS)
            error("Seed hold expired without the qualified driver stop")
        }
    }

    suspend fun complete() {
        assertOffline()
        rows.assertPending()
        assertEquals(AppPendingColdRows.DRAFT, savedDraft())
        record("cold-complete", heldUntil = null)
    }

    private fun record(stage: String, heldUntil: Long?) {
        val expected = File(directory, "expected.json").readBytes()
        write(
            "$stage.json",
            JSONObject().put("nonce", nonce).put("stage", stage).put("pid", Process.myPid())
                .put("uid", Process.myUid()).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("phase", phase).put("databaseName", DATABASE).put("assertionsComplete", true)
                .put("databaseConnectionHeld", heldUntil != null).put("snapshotBytes", expected.size)
                .put("snapshotSha256", digest(expected))
                .put("heldUntilElapsedMs", heldUntil ?: JSONObject.NULL).put("schemaVersion", AppPendingColdRows.SCHEMA)
                .put("actualDefaultApplication", true).put("graphReplaced", false).put("liveQueueProved", false)
                .put("pendingRows", 1).put("attemptCount", 0).put("snapshotTables", AppPendingColdRows.TABLE_COUNT)
                .toString(),
        )
    }

    private fun write(name: String, value: String) {
        val file = File(directory, name)
        check(!file.exists())
        val temporary = File(directory, "$name.writing")
        check(!temporary.exists())
        FileOutputStream(temporary).use { stream ->
            stream.write(value.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun close() {
        scenario?.close()
    }

    companion object {
        const val PACKAGE = "io.pocketmesh.app.queueoffline"
        const val DATABASE = "mesh.db"
        const val PHASE_ARGUMENT = "mc1PendingColdPhase"
        const val ARGUMENT = "mc1PendingColdNonce"
        const val TIMEOUT_MS = 15_000L
        const val POLL_MS = 25L
        const val HOLD_MS = 90_000L
    }
}
