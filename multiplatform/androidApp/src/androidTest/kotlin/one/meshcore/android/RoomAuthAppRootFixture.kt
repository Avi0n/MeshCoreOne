package one.meshcore.android

import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import android.view.Window
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.BackupEnvelopeBuilder
import one.meshcore.shared.backup.BackupExportInput
import one.meshcore.shared.backup.decodeBackupEnvelope
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.remotenode.KeystoreNodePasswordStore
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import java.security.KeyStore

internal class RoomAuthAppRootFixture(private val nonce: String) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as MeshCoreApplication
    val container = application.container
    private val rows = BackupPostcommitRows(container.database)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var ownsTestState = false
    private var before: Map<String, List<Map<String, Any?>>>? = null
    val directory = File(application.getExternalFilesDir(null), "room-auth-app-root-$nonce")

    init {
        require(Regex("[0-9a-f]{32}").matches(nonce))
        identity()
    }

    fun open() = runBlocking {
        identity()
        assertTrue(Regex("[0-9a-f]{32}").matches(nonce))
        assertFalse(directory.exists())
        assertTrue(directory.mkdirs())
        withTimeout(TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                container.awaitInitialOfflineData()
                noConnection()
                assertNull(container.currentRadioId())
            }
            rows.assertCounts(rows.snapshot(), copies = 0)
            assertFalse(aliasExists())
            for (index in KEYS.indices) assertTrue(entryAbsent(index))
            ownsTestState = true
            assertTrue(container.nodePasswordStore is KeystoreNodePasswordStore)
            assertTrue(container.nodePasswordStoreProvider() === container.nodePasswordStore)
            val base =
                decodeBackupEnvelope(
                    instrumentation.context.assets.open("backup-postcommit.json").bufferedReader().use {
                        it.readText()
                    },
                )
            val contacts = NAMES.indices.map { index ->
                base.contacts.single().copy(
                    id = Uuid(CONTACT_IDS[index]), publicKey = KEYS[index], name = NAMES[index], nickname = null,
                    typeRawValue = RemoteNodeRole.ROOM_SERVER.raw, outPathLength = FLOOD, outPath = byteArrayOf(),
                    isBlocked = false, isMuted = false, isFavorite = false, isPinned = false,
                    unreadCount = 0, unreadMentionCount = 0, lastMessageDate = null, avatarImageData = null,
                )
            }
            val sessions = NAMES.indices.map { index ->
                base.remoteNodeSessions.single().copy(
                    id = Uuid(SESSION_IDS[index]),
                    publicKey = KEYS[index],
                    name = NAMES[index],
                    role = RemoteNodeRole.ROOM_SERVER,
                    isConnected = false,
                    permissionLevel = RoomPermissionLevel.GUEST,
                    unreadCount = 0,
                    isPinned = false,
                )
            }
            val json = BackupEnvelopeBuilder.encode(
                BackupExportInput(
                    appVersion = "fixture",
                    appBuild = "room-auth",
                    exportDate = 0.0,
                    devices = base.devices.map { it.copy(isActive = false, connectionMethods = emptyList()) },
                    contacts = contacts,
                    remoteNodeSessions = sessions,
                ),
            )
            withContext(Dispatchers.Main) {
                assertTrue(container.importBackupJson(json).totalInserted == INSERTED)
                container.onboarding.complete()
                noConnection()
                assertTrue(container.currentRadioId() == base.devices.single().radioID)
            }
            for (index in KEYS.indices) container.nodePasswordStore.storePassword(KEYS[index], PASSWORDS[index])
            assertCredentialsRetained()
            before = rows.snapshot()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario?.onActivity { assertTrue((it.application as MeshCoreApplication).container === container) }
    }

    suspend fun noConnection() {
        assertFalse(container.connectionManager.state.value.isConnected)
        assertNull(container.connectionManager.services)
        assertNull(container.lastConnectionStore.deviceId())
    }

    suspend fun assertCredentialsRetained() {
        for (index in KEYS.indices) {
            assertTrue(
                "Saved synthetic credential retained",
                container.nodePasswordStore.retrievePassword(KEYS[index]) == PASSWORDS[index],
            )
        }
    }

    suspend fun entryAbsent(index: Int): Boolean =
        container.prefs.getString(PREF_PREFIX + KEYS[index].toUppercaseHex()) == null

    fun removeKey() = runBlocking {
        identity()
        withTimeout(TIMEOUT_MS) {
            for (index in KEYS.indices) assertFalse(entryAbsent(index))
            assertTrue(aliasExists())
            deleteAlias()
            assertFalse(aliasExists())
            for (index in KEYS.indices) assertFalse(entryAbsent(index))
        }
    }

    fun assertUnchangedRows() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            withContext(Dispatchers.Main) { noConnection() }
            assertTrue("Authentication UI leaves Room unchanged", before == rows.snapshot())
        }
    }

    fun screenshot(name: String) {
        val owner = checkNotNull(scenario)
        var window: Window? = null
        owner.onActivity { activity ->
            assertOwnedWindow(activity)
            window = activity.window
        }
        val ownedWindow = checkNotNull(window)
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            checkNotNull(instrumentation.uiAutomation.takeScreenshot(ownedWindow))
        } else {
            error("Window capture requires API 34")
        }
        try {
            owner.onActivity { activity ->
                assertTrue(activity.window === ownedWindow)
                assertOwnedWindow(activity)
            }
            File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertOwnedWindow(activity: MainActivity) {
        assertTrue(activity.application === application && activity.packageName == TARGET)
        assertTrue((activity.application as MeshCoreApplication).container === container)
        assertTrue(activity.lifecycle.currentState == Lifecycle.State.RESUMED)
        val decor = activity.window.decorView
        assertTrue(decor.isAttachedToWindow && decor.isShown && decor.hasWindowFocus())
        assertTrue(decor.width > 0 && decor.height > 0)
    }

    fun record() {
        identity()
        File(directory, "outcome.json").writeText(
            JSONObject().put("nonce", nonce).put("passed", true).put("pid", Process.myPid()).put("uid", Process.myUid())
                .put("actualApplication", application.javaClass.name).put("actualContainer", container.javaClass.name)
                .put("sameContainer", application.container === container).put("roomRowsUnchanged", true)
                .put("savedBothAndDismissRetained", true).put("uiDiscardedBothAfterAliasLoss", true).toString(),
        )
    }

    fun close() {
        var activityClosed = false
        var credentialsCleared = false
        try {
            scenario?.close()
            activityClosed = true
        } finally {
            try {
                if (ownsTestState) {
                    runBlocking {
                        withTimeout(TIMEOUT_MS) {
                            identity()
                            check(ownsTestState)
                            for (key in KEYS) container.nodePasswordStore.deletePassword(key)
                            deleteAlias()
                            for (index in KEYS.indices) assertTrue(entryAbsent(index))
                            assertFalse(aliasExists())
                            credentialsCleared = true
                        }
                    }
                }
            } finally {
                if (directory.exists()) {
                    File(directory, "teardown.json").writeText(
                        JSONObject().put("nonce", nonce).put("activityClosed", activityClosed)
                            .put(
                                "cleanupRequired",
                                ownsTestState,
                            ).put("syntheticCredentialsAndAliasCleared", credentialsCleared)
                            .put("sameContainer", application.container === container).toString(),
                    )
                }
            }
        }
    }

    private fun identity() {
        assertTrue(application.packageName == TARGET)
        assertTrue(Process.myUid() == application.applicationInfo.uid)
        assertTrue(application.container === container)
    }

    private suspend fun aliasExists(): Boolean = withContext(Dispatchers.IO) {
        identity()
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.containsAlias(ALIAS)
    }

    private suspend fun deleteAlias() = withContext(Dispatchers.IO) {
        identity()
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
    }

    companion object {
        const val TARGET = "io.pocketmesh.app.debug"
        const val RUN_ARGUMENT = "roomAuthAppRootRun"
        const val TIMEOUT_MS = 20_000L
        const val UNSAVED = "not-saved"
        private const val INSERTED = 5
        private const val FLOOD = 0xFF
        private const val PNG_QUALITY = 100
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "one.meshcore.node_password_key"
        private const val PREF_PREFIX = "nodepw."
        private const val KEY_LENGTH = 32
        private const val FIRST_KEY_BYTE = 0x75
        private const val SECOND_KEY_BYTE = 0x76
        val KEYS =
            listOf(
                ByteArray(KEY_LENGTH) {
                    FIRST_KEY_BYTE.toByte()
                },
                ByteArray(KEY_LENGTH) { SECOND_KEY_BYTE.toByte() },
            )
        val NAMES = listOf("Auth Room Alpha", "Auth Room Bravo")
        val PASSWORDS = listOf("auth-alpha", "auth-bravo2")
        val CONTACT_IDS = listOf("CA000000-0000-0000-0000-000000000001", "CA000000-0000-0000-0000-000000000002")
        val SESSION_IDS = listOf("CB000000-0000-0000-0000-000000000001", "CB000000-0000-0000-0000-000000000002")
    }
}
