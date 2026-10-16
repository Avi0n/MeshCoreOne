package one.meshcore.android

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.decodeBackupEnvelope
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.DraftStore
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.RoomDiscoveredNodeStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import java.io.File

internal class BackupPostcommitGraph(val gate: BackupPostcommitGate) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as MeshCoreApplication
    private val root = File(application.cacheDir, "backup-postcommit-${Uuid.random().value}")
    private var instance: AppContainer? = null
    private var dataStoreJob: Job? = null
    private var originalPrefs: Any? = null
    private var originalDao: Any? = null
    val container get() = checkNotNull(instance)
    val rows get() = BackupPostcommitRows(container.database)
    val json = instrumentation.context.assets.open("backup-postcommit.json").bufferedReader().use { it.readText() }
    private val envelope = decodeBackupEnvelope(json)
    private val radio = envelope.devices.single().radioID
    val target = ChatConversationID.channel(radio, SLOT)
    val contrast = ChatConversationID.channel(radio, CONTRAST_SLOT)
    var containerStopped = false
        private set
    var dataStoreStopped = false
        private set
    var databaseClosed = false
        private set
    var rootAbsent = false
        private set

    fun open() {
        assertEquals(BackupPostcommitUi.DEBUG_PACKAGE, application.packageName)
        assertTrue(root.mkdirs())
        instrumentation.runOnMainSync { instance = AppContainer(IsolatedContext(application, root)) }
        assertNotSame(application.container, container)
        assertNotSame(application.container.prefs, container.prefs)
        val prefs = readField(container.prefs, "dataStore")
        check(prefs.javaClass.name == "androidx.datastore.preferences.core.PreferenceDataStore")
        val androidDelegate = readField(prefs, "delegate")
        check(androidDelegate.javaClass.name == "androidx.datastore.preferences.core.PreferenceDataStore")
        val core = readField(androidDelegate, "delegate")
        check(core.javaClass.name == "androidx.datastore.core.DataStoreImpl")
        val scope = readField(core, "scope") as CoroutineScope
        dataStoreJob = scope.coroutineContext.job
        assertNotSame(container.appScope.coroutineContext.job, dataStoreJob)
        assertNotSame(application.container.appScope.coroutineContext.job, dataStoreJob)
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                container.awaitInitialOfflineData()
                assertFalse(container.connectionManager.state.value.isConnected)
                assertEquals(null, container.connectionManager.services)
                assertEquals(null, container.lastConnectionStore.deviceId())
                rows.assertCounts(rows.snapshot(), copies = 0)
                container.draftStore.setDraft(TARGET_DRAFT, target)
                container.draftStore.setDraft(CONTRAST_DRAFT, contrast)
            }
        }
        originalPrefs =
            replaceField(container.draftStore, "preferences", BackupPostcommitGate.Drafts(container.prefs, gate))
        assertTrue(originalPrefs === container.prefs)
        val actualDao = container.database.discoveredNodeDao()
        originalDao = replaceField(
            container.discoveredNodeStoreImpl,
            "dao",
            BackupPostcommitGate.Discover(actualDao, gate),
        )
        assertTrue(originalDao === actualDao)
    }

    suspend fun assertCommittedDraftSafety() {
        val durable = DraftStore.create(container.prefs)
        assertEquals(null, durable.draft(target))
        assertEquals(CONTRAST_DRAFT, durable.draft(contrast))
        assertTrue(container.database.deviceDao().listAll().all { it.connectionMethods.isEmpty() && !it.isActive })
        assertTrue(container.database.messageDao().listAll().all { it.directionRawValue == 0 })
        assertTrue(container.database.roomMessageDao().listAll().none { it.isFromSelf })
    }

    fun publishedDiscover(): Boolean {
        val actual = container.discoveredNodeStoreImpl.snapshot.value.singleOrNull() ?: return false
        val expected = envelope.discoveredNodes.single()
        return actual.radioID == expected.radioID &&
            actual.publicKey.contentEquals(expected.publicKey) &&
            actual.name == expected.name
    }

    fun close() {
        gate.release.complete(Unit)
        try {
            stopContainer()
        } finally {
            try {
                stopDataStore()
            } finally {
                closeFiles()
            }
        }
    }

    private fun stopContainer() {
        val value = instance ?: return
        try {
            originalPrefs?.let { replaceField(value.draftStore, "preferences", it) }
        } finally {
            try {
                originalDao?.let { replaceField(value.discoveredNodeStoreImpl, "dao", it) }
            } finally {
                stopContainerScope(value)
            }
        }
    }

    private fun stopContainerScope(value: AppContainer) {
        runBlocking { withTimeout(TIMEOUT_MS) { value.appScope.coroutineContext.job.cancelAndJoin() } }
        containerStopped = value.appScope.coroutineContext.job.isCompleted
        check(containerStopped)
    }

    private fun stopDataStore() {
        val job = dataStoreJob ?: return
        runBlocking { withTimeout(TIMEOUT_MS) { job.cancelAndJoin() } }
        dataStoreStopped = job.isCompleted
        check(dataStoreStopped)
    }

    private fun closeFiles() {
        try {
            instance?.database?.close()
            databaseClosed = instance != null
        } finally {
            val removed = root.deleteRecursively()
            rootAbsent = !root.exists()
            check(removed && rootAbsent)
        }
    }

    fun teardown(): JSONObject = JSONObject().put("containerScopeStopped", containerStopped)
        .put("isolatedDataStoreScopeStopped", dataStoreStopped).put("databaseClosed", databaseClosed)
        .put("ownedRootAbsent", rootAbsent).put("radioOrSessionOpened", false)

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory("files")
        override fun getCacheDir(): File = directory("cache")
        override fun getNoBackupFilesDir(): File = directory("nobackup")
        override fun getDataDir(): File = root
        override fun getDatabasePath(name: String): File = File(directory("databases"), name)
        private fun directory(name: String): File = File(root, name).apply { mkdirs() }
    }

    companion object {
        const val TIMEOUT_MS = 15_000L
        private const val SLOT = 1
        private const val CONTRAST_SLOT = 2
        private const val TARGET_DRAFT = "Synthetic affected-slot draft"
        private const val CONTRAST_DRAFT = "Synthetic unrelated draft"
        private fun readField(instance: Any, name: String): Any = instance.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(instance)
        private fun replaceField(instance: Any, name: String, replacement: Any): Any {
            val type = when (instance) {
                is DraftStore -> DraftStore::class.java
                is RoomDiscoveredNodeStore -> RoomDiscoveredNodeStore::class.java
                else -> error("Unsupported isolated dependency")
            }
            val field = type.getDeclaredField(name).apply { isAccessible = true }
            val old = checkNotNull(field.get(instance))
            field.set(instance, replacement)
            check(field.get(instance) === replacement)
            return old
        }
    }
}
