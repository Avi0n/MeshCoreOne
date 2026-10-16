package one.meshcore.android

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ContactEntity
import one.meshcore.shared.persistence.MESH_SCHEMA_VERSION
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * File-level restore of `mesh.db` through [meshDatabase], separate from a `.mc1backup` import.
 * This fixture copies the database into an isolated directory; it does not run system backup.
 */
@RunWith(AndroidJUnit4::class)
class CloudBackupDatabaseRestoreFixtureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val roots = mutableListOf<File>()

    @After
    fun deleteRoots() {
        roots.forEach { root -> root.deleteRecursively() }
        roots.clear()
    }

    @Test
    fun debugPackageDisablesSystemBackupWithoutABackupAgent() {
        val target = instrumentation.targetContext
        assertEquals(DEBUG_PACKAGE, target.packageName)
        assertSystemBackupDisabled(target.applicationInfo)
    }

    @Test
    fun copiedMeshDatabaseKeepsTheContactUntilTheSchemaVersionDiffers() = runBlocking {
        val source = isolated("source")
        val sourceDatabase = meshDatabase(source, Dispatchers.IO, DATABASE_NAME)
        sourceDatabase.contactDao().upsert(contactRow())
        val opened = File(databaseFile(sourceDatabase)).canonicalFile
        assertEquals(source.getDatabasePath(DATABASE_NAME).canonicalFile, opened)
        checkpoint(sourceDatabase)
        sourceDatabase.close()
        val sourceFile = source.getDatabasePath(DATABASE_NAME)
        assertTrue(sourceFile.isFile)
        assertEquals(MESH_SCHEMA_VERSION, readUserVersion(sourceFile))
        assertTrue(sidecarIsEmpty(sourceFile, WAL_SUFFIX))
        assertTrue(sidecarIsEmpty(sourceFile, SHM_SUFFIX))

        val same = isolated("same-schema")
        val sameFile = same.getDatabasePath(DATABASE_NAME)
        copyDatabaseFile(sourceFile, sameFile)
        val reopened = meshDatabase(same, Dispatchers.IO, DATABASE_NAME)
        val names = reopened.contactDao().listAll().map { contact -> contact.name }
        reopened.close()
        assertEquals(listOf(CONTACT_NAME), names)

        // An unsupported pre-production user_version intentionally drops the old tables.
        val foreign = isolated("foreign-schema")
        val foreignFile = foreign.getDatabasePath(DATABASE_NAME)
        copyDatabaseFile(sourceFile, foreignFile)
        writeUserVersion(foreignFile, FOREIGN_USER_VERSION)
        assertEquals(FOREIGN_USER_VERSION, readUserVersion(foreignFile))
        val wiped = meshDatabase(foreign, Dispatchers.IO, DATABASE_NAME)
        val remaining = wiped.contactDao().listAll()
        checkpoint(wiped)
        wiped.close()
        assertTrue(remaining.isEmpty())
        assertEquals(MESH_SCHEMA_VERSION, readUserVersion(foreignFile))
    }

    private fun isolated(name: String): IsolatedProcessContext {
        val root = File(instrumentation.targetContext.cacheDir, "$FIXTURE_DIRECTORY/$name")
        root.deleteRecursively()
        root.mkdirs()
        roots += root
        return IsolatedProcessContext(instrumentation.targetContext, root)
    }

    private suspend fun checkpoint(database: MeshDatabase) = database.useConnection(isReadOnly = false) { connection ->
        connection.usePrepared(WAL_CHECKPOINT) { statement ->
            while (statement.step()) Unit
        }
    }

    private suspend fun databaseFile(database: MeshDatabase): String =
        database.useConnection(isReadOnly = true) { connection ->
            connection.usePrepared(DATABASE_LIST) { statement ->
                check(statement.step())
                statement.getText(DATABASE_FILE_COLUMN)
            }
        }

    private fun copyDatabaseFile(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = true)
    }

    private fun sidecarIsEmpty(database: File, suffix: String): Boolean {
        val sidecar = File(database.parentFile, database.name + suffix)
        return !sidecar.exists() || sidecar.length() == 0L
    }

    private fun readUserVersion(file: File): Int {
        val header = ByteArray(HEADER_BYTES)
        file.inputStream().use { input ->
            check(input.read(header) == HEADER_BYTES)
        }
        check(header.decodeToString(0, SQLITE_MAGIC.length) == SQLITE_MAGIC)
        return (header[USER_VERSION_OFFSET].toInt() and 0xFF shl 24) or
            (header[USER_VERSION_OFFSET + 1].toInt() and 0xFF shl 16) or
            (header[USER_VERSION_OFFSET + 2].toInt() and 0xFF shl 8) or
            (header[USER_VERSION_OFFSET + 3].toInt() and 0xFF)
    }

    private fun writeUserVersion(file: File, version: Int) = RandomAccessFile(file, "rw").use { access ->
        access.seek(USER_VERSION_OFFSET.toLong())
        access.write(version ushr 24)
        access.write(version ushr 16)
        access.write(version ushr 8)
        access.write(version)
    }

    private fun assertSystemBackupDisabled(info: ApplicationInfo) {
        assertFalse(info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
        assertNull(info.backupAgentName)
    }

    private fun contactRow() = ContactEntity(
        id = CONTACT_ID,
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

    private class IsolatedProcessContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = directory("files")

        override fun getCacheDir(): File = directory("cache")

        override fun getNoBackupFilesDir(): File = directory("nobackup")

        override fun getDataDir(): File = root

        override fun getDatabasePath(name: String): File {
            val requested = File(name)
            if (requested.isAbsolute) {
                requested.parentFile?.mkdirs()
                return requested
            }
            return File(directory("databases"), name)
        }

        private fun directory(name: String): File = File(root, name).apply { mkdirs() }
    }

    private companion object {
        const val DEBUG_PACKAGE = "io.pocketmesh.app.debug"
        const val FIXTURE_DIRECTORY = "cloud-backup-fixture"
        const val DATABASE_NAME = "mesh.db"
        const val CONTACT_NAME = "Cora"
        const val FOREIGN_USER_VERSION = 10
        const val USER_VERSION_OFFSET = 60
        const val HEADER_BYTES = 64
        const val SQLITE_MAGIC = "SQLite format 3"
        const val WAL_CHECKPOINT = "PRAGMA wal_checkpoint(TRUNCATE)"
        const val DATABASE_LIST = "PRAGMA database_list"
        const val DATABASE_FILE_COLUMN = 2
        const val WAL_SUFFIX = "-wal"
        const val SHM_SUFFIX = "-shm"
        const val PUBLIC_KEY_LENGTH = 32
        const val CONTACT_KEY_FILL = 0x51
        const val FLOOD_OUT_PATH_LENGTH = 255

        val RADIO: Uuid = Uuid.normalized("DDDDDDDD-DDDD-DDDD-DDDD-DDDDDDDDDD44")
        val CONTACT_ID: Uuid = Uuid.normalized("DDDDDDDD-DDDD-DDDD-DDDD-DDDDDDDDDD45")
        val CONTACT_PUBLIC_KEY = ByteArray(PUBLIC_KEY_LENGTH) { CONTACT_KEY_FILL.toByte() }
    }
}
