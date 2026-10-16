package one.meshcore.android

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.AppBackupError
import one.meshcore.shared.backup.BackupEnvelopeBuilder
import one.meshcore.shared.backup.BackupExportInput
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException

/**
 * Backup import starts at [MainActivityDocuments.pickBackupFileJson] and [AppContainer.importBackupJson].
 * A cancelled picker returns null and does not open the document stream. Malformed JSON throws
 * [AppBackupError.InvalidFile] and writes nothing. A decoded envelope inserts the contact, and a later
 * import while the radio is connecting throws [AppBackupError.RadioConnected] and leaves that contact.
 * The container uses its own database. Nothing here opens a radio.
 */
@RunWith(AndroidJUnit4::class)
class BackupImportFixtureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application: MeshCoreApplication
        get() = instrumentation.targetContext.applicationContext as MeshCoreApplication

    @Before
    fun setUp() {
        assertEquals(DEBUG_PACKAGE, application.packageName)
        if (fixture == null) openFixture()
        setConnectionState(ConnectionState.Disconnected)
    }

    @After
    fun tearDown() {
        if (fixture != null) setConnectionState(ConnectionState.Disconnected)
    }

    @Test
    fun aCancelledBackupPickReturnsNullWithoutOpeningAStream() {
        val provider = RecordingBackupProvider()
        var launches = 0
        val documents = MainActivityDocuments(
            container = requireFixture(),
            contentResolver = ContentResolver.wrap(provider),
            createBackupDocument = UnusedLauncher(),
            openBackupDocument = OfferOnLaunch {
                launches += 1
                requireFixture().backupPickWaiter.offer(null)
            },
            openNodeConfigDocument = UnusedLauncher(),
        )
        val json = runBlocking { documents.pickBackupFileJson() }
        assertNull(json)
        assertEquals(1, launches)
        assertEquals(0, provider.openedStreams)
    }

    @Test
    fun aMalformedBackupWritesNothing() {
        val before = contactCount()
        assertThrows(AppBackupError.InvalidFile::class.java) {
            runBlocking { requireFixture().importBackupJson(MALFORMED_JSON) }
        }
        assertEquals(before, contactCount())
    }

    @Test
    fun aRestoredContactSurvivesAConnectedImportRefusal() {
        val contactId = Uuid.random()
        val json = BackupEnvelopeBuilder.encode(
            BackupExportInput(
                appVersion = APP_VERSION,
                appBuild = APP_BUILD,
                exportDate = EXPORT_DATE,
                contacts = listOf(contact(contactId)),
            ),
        )
        try {
            val restored = runBlocking { requireFixture().importBackupJson(json) }
            assertEquals(1, restored.contactsInserted)
            assertEquals(CONTACT_NAME, contactName(contactId))
            // Connecting is connected for import, and services stay null so Ready cleanup does not run.
            setConnectionState(ConnectionState.Connecting(FIXTURE_HANDLE))
            assertThrows(AppBackupError.RadioConnected::class.java) {
                runBlocking { requireFixture().importBackupJson(json) }
            }
            assertEquals(CONTACT_NAME, contactName(contactId))
            assertEquals(1, contactsNamed(CONTACT_NAME))
        } finally {
            setConnectionState(ConnectionState.Disconnected)
        }
    }

    private fun openFixture() {
        val directory = File(application.cacheDir, FIXTURE_DIRECTORY).apply { mkdirs() }
        fixtureRoot = directory
        instrumentation.runOnMainSync {
            fixture = AppContainer(IsolatedProcessContext(application, directory))
        }
        runBlocking { requireFixture().awaitInitialOfflineData() }
    }

    private fun setConnectionState(state: ConnectionState) {
        @Suppress("UNCHECKED_CAST")
        val flow = stateField.get(requireFixture().connectionManager) as MutableStateFlow<ConnectionState>
        flow.value = state
    }

    private fun contactCount(): Int = runBlocking { requireFixture().database.contactDao().listAll().size }

    private fun contactName(id: Uuid): String? = runBlocking {
        requireFixture().database.contactDao().getById(id)?.name
    }

    private fun contactsNamed(name: String): Int = runBlocking {
        requireFixture().database.contactDao().listAll().count { it.name == name }
    }

    private fun contact(id: Uuid) = ContactDto(
        id = id,
        radioID = RADIO,
        publicKey = CONTACT_PUBLIC_KEY,
        name = CONTACT_NAME,
        typeRawValue = ContactType.CHAT.raw,
        flags = 0,
        outPathLength = FLOOD_OUT_PATH_LENGTH,
        outPath = byteArrayOf(),
        lastAdvertTimestamp = 0L,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0L,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )

    private class RecordingBackupProvider : ContentProvider() {
        var openedStreams = 0

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor? {
            openedStreams += 1
            throw FileNotFoundException(STREAM_OPENED)
        }
    }

    private class OfferOnLaunch(private val offer: () -> Unit) : ActivityResultLauncher<Array<String>>() {
        override val contract: ActivityResultContract<Array<String>, *> =
            ActivityResultContracts.OpenDocument()

        override fun launch(input: Array<String>, options: ActivityOptionsCompat?) = offer()

        override fun unregister() = Unit
    }

    private class UnusedLauncher<I> : ActivityResultLauncher<I>() {
        override val contract: ActivityResultContract<I, *>
            get() = error("unused document launcher")

        override fun launch(input: I, options: ActivityOptionsCompat?) = error("unused document launcher")

        override fun unregister() = Unit
    }

    private class IsolatedProcessContext(base: Context, private val root: File) : ContextWrapper(base) {
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
        const val FIXTURE_DIRECTORY = "backup-import-fixture"
        const val FIXTURE_HANDLE = "backup-fixture-handle"
        const val CONTACT_NAME = "Mina"
        const val MALFORMED_JSON = "{"
        const val APP_VERSION = "1.0.0"
        const val APP_BUILD = "1"
        const val EXPORT_DATE = 1.0
        const val FLOOD_OUT_PATH_LENGTH = 255
        const val PUBLIC_KEY_LENGTH = 32
        const val CONTACT_KEY_FILL = 0x44
        const val STREAM_OPENED = "backup fixture stream opened"

        val RADIO: Uuid = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCC33")
        val CONTACT_PUBLIC_KEY = ByteArray(PUBLIC_KEY_LENGTH) { CONTACT_KEY_FILL.toByte() }

        val stateField = ConnectionManager::class.java.getDeclaredField("mutableState").apply { isAccessible = true }

        var fixture: AppContainer? = null
        var fixtureRoot: File? = null

        fun requireFixture(): AppContainer = checkNotNull(fixture)

        @AfterClass
        @JvmStatic
        fun closeFixture() {
            fixture?.appScope?.coroutineContext?.get(Job)?.cancel()
            fixture?.database?.close()
            fixtureRoot?.deleteRecursively()
            fixture = null
            fixtureRoot = null
        }
    }
}
