package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.model.Uuid
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Schema 11 is the first production database. An unsupported pre-launch file is dropped. A file
 * already at schema 11, and a file newer than this code, is not dropped.
 */
class MeshSchemaPolicyTest {
    @Test
    fun schema11KeepsTheContactAndAnOlderSchemaDropsIt() = runBlocking {
        val root = File.createTempFile("mesh-schema-", "").apply {
            delete()
            mkdirs()
        }
        try {
            val source = File(root, "source.db")
            val database = meshDatabase(source.absolutePath)
            database.contactDao().upsert(contactRow())
            checkpoint(database)
            database.close()
            assertEquals(MESH_SCHEMA_VERSION, readUserVersion(source))

            val same = File(root, "same.db")
            source.copyTo(same)
            val reopened = meshDatabase(same.absolutePath)
            assertEquals(listOf(CONTACT_NAME), reopened.contactDao().listAll().map { it.name })
            reopened.close()

            val older = File(root, "older.db")
            source.copyTo(older)
            writeUserVersion(older, UNSUPPORTED_USER_VERSION)
            val wiped = meshDatabase(older.absolutePath)
            assertTrue(wiped.contactDao().listAll().isEmpty())
            checkpoint(wiped)
            wiped.close()
            assertEquals(MESH_SCHEMA_VERSION, readUserVersion(older))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aNewerSchemaIsNotDropped() = runBlocking {
        val root = File.createTempFile("mesh-schema-newer-", "").apply {
            delete()
            mkdirs()
        }
        try {
            val source = File(root, "source.db")
            val database = meshDatabase(source.absolutePath)
            database.contactDao().upsert(contactRow())
            checkpoint(database)
            database.close()

            val newer = File(root, "newer.db")
            source.copyTo(newer)
            writeUserVersion(newer, NEWER_USER_VERSION)
            assertEquals(NEWER_USER_VERSION, readUserVersion(newer))
            assertFails {
                val opened = meshDatabase(newer.absolutePath)
                try {
                    opened.contactDao().listAll()
                } finally {
                    opened.close()
                }
            }

            writeUserVersion(newer, MESH_SCHEMA_VERSION)
            val restored = meshDatabase(newer.absolutePath)
            assertEquals(listOf(CONTACT_NAME), restored.contactDao().listAll().map { it.name })
            restored.close()
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun checkpoint(database: MeshDatabase) = database.useConnection(isReadOnly = false) { connection ->
        connection.usePrepared(WAL_CHECKPOINT) { statement ->
            while (statement.step()) Unit
        }
    }

    private fun readUserVersion(file: File): Int {
        val header = ByteArray(HEADER_BYTES)
        file.inputStream().use { input ->
            check(input.read(header) == HEADER_BYTES)
        }
        check(header.decodeToString(0, SQLITE_MAGIC.length) == SQLITE_MAGIC)
        return (header[USER_VERSION_OFFSET].toInt() and BYTE_MASK shl BIT_24) or
            (header[USER_VERSION_OFFSET + 1].toInt() and BYTE_MASK shl BIT_16) or
            (header[USER_VERSION_OFFSET + 2].toInt() and BYTE_MASK shl BIT_8) or
            (header[USER_VERSION_OFFSET + 3].toInt() and BYTE_MASK)
    }

    private fun writeUserVersion(file: File, version: Int) = RandomAccessFile(file, "rw").use { access ->
        access.seek(USER_VERSION_OFFSET.toLong())
        access.write(version ushr BIT_24)
        access.write(version ushr BIT_16)
        access.write(version ushr BIT_8)
        access.write(version)
    }

    private fun contactRow() = ContactEntity(
        id = CONTACT_ID,
        radioID = RADIO,
        publicKey = ByteArray(PUBLIC_KEY_LENGTH) { CONTACT_KEY_FILL.toByte() },
        name = CONTACT_NAME,
        typeRawValue = 1,
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

    private companion object {
        const val CONTACT_NAME = "Cora"
        const val UNSUPPORTED_USER_VERSION = 10
        const val NEWER_USER_VERSION = 12
        const val USER_VERSION_OFFSET = 60
        const val HEADER_BYTES = 100
        const val SQLITE_MAGIC = "SQLite format 3"
        const val WAL_CHECKPOINT = "PRAGMA wal_checkpoint(TRUNCATE)"
        const val BYTE_MASK = 0xFF
        const val BIT_24 = 24
        const val BIT_16 = 16
        const val BIT_8 = 8
        const val PUBLIC_KEY_LENGTH = 32
        const val CONTACT_KEY_FILL = 0x51
        const val FLOOD_OUT_PATH_LENGTH = 255

        val RADIO: Uuid = Uuid.normalized("DDDDDDDD-DDDD-DDDD-DDDD-DDDDDDDDDD44")
        val CONTACT_ID: Uuid = Uuid.normalized("DDDDDDDD-DDDD-DDDD-DDDD-DDDDDDDDDD45")
    }
}
