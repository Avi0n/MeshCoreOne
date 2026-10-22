// PortedFrom: MC1Services/Tests/MC1ServicesTests/DiscoveredNodeUnsignedFetchTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.data.backup.AppBackupCodec
import com.meshcoreone.android.core.data.backup.AppBackupEnvelope
import com.meshcoreone.android.core.data.backup.AppBackupService
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UnsignedPathBackupOriginalPredicateTest {
    @Test fun discoveredLegacyMinusOneSurvivesClosedFileReopenAndActualBackupExport() = runTest {
        val frame = frame(0x44, ContactType.REPEATER, "LegacyFlood")
        legacySnapshot(
            "discovered_nodes",
            seed = { store, radio -> store.upsertDiscoveredNode(radio, frame) },
        ) { snapshot, radio ->
            assertEquals(1, snapshot.discoveredNodes.size)
            val node = snapshot.discoveredNodes.single()
            assertEquals(255.toUByte(), node.outPathLength)
            assertEquals(radio, node.radioId)
            assertEquals(frame.publicKey, node.publicKey)
            assertEquals(2.toUByte(), node.typeRawValue)
            assertEquals("LegacyFlood", node.name)
            assertEquals(Bytes.EMPTY, node.outPath)
            assertEquals(1u, node.lastAdvertTimestamp)
            assertEquals(0.0, node.latitude, 0.0)
            assertEquals(0.0, node.longitude, 0.0)
        }
    }

    @Test fun contactLegacyMinusOneSurvivesClosedFileReopenAndActualBackupExport() = runTest {
        val frame = frame(0x55, ContactType.CHAT, "LegacyContact")
        legacySnapshot(
            "contacts",
            seed = { store, radio -> store.saveContact(radio, frame) },
        ) { snapshot, radio ->
            assertEquals(1, snapshot.contacts.size)
            val contact = snapshot.contacts.single()
            assertEquals(255.toUByte(), contact.outPathLength)
            assertEquals(radio, contact.radioId)
            assertEquals(frame.publicKey, contact.publicKey)
            assertEquals(1.toUByte(), contact.typeRawValue)
            assertEquals("LegacyContact", contact.name)
            assertEquals(0.toUByte(), contact.flags)
            assertEquals(Bytes.EMPTY, contact.outPath)
            assertEquals(1u, contact.lastAdvertTimestamp)
            assertEquals(0.0, contact.latitude, 0.0)
            assertEquals(0.0, contact.longitude, 0.0)
            assertEquals(0u, contact.lastModified)
        }
    }

    private fun frame(keyByte: Int, type: ContactType, name: String) = ContactFrame(
        publicKey = Bytes(ByteArray(32) { keyByte.toByte() }),
        type = type,
        flags = 0u,
        outPathLength = 255u,
        outPath = Bytes.EMPTY,
        name = name,
        lastAdvertTimestamp = 1u,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0u,
    )

    private suspend fun TestScope.legacySnapshot(
        table: String,
        seed: suspend (RoomPersistenceStore, RadioId) -> Unit,
        assertSnapshot: (AppBackupEnvelope, RadioId) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric isolates each method's directory; keep its native SQLite path within Windows limits.
        val name = "int8.db"
        val file = context.getDatabasePath(name)
        val radio = RadioId(UUID.randomUUID())
        try {
            withFileStore(context, name) { seed(it, radio) }
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
                raw.rawQuery("PRAGMA busy_timeout = 5000", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(5000, cursor.getInt(0))
                }
                raw.execSQL("UPDATE $table SET outPathLength = -1")
                raw.rawQuery("SELECT outPathLength FROM $table", null).use { cursor ->
                    assertEquals(1, cursor.count)
                    assertTrue(cursor.moveToFirst())
                    assertEquals("-1", cursor.getString(0))
                }
            }
            withFileStore(context, name) { reopened ->
                val storage = MeshCoreStorage.get(context)
                try {
                    val service = AppBackupService(storage.backupPreferences, "synthetic", "1", clock)
                    assertSnapshot(service.exportEnvelope(reopened), radio)
                    assertSnapshot(AppBackupCodec().parseBackup(service.export(reopened).data), radio)
                } finally {
                    storage.close()
                }
            }
        } finally {
            if (file.exists()) {
                assertTrue("Delete only the test's closed database and SQLite sidecars", context.deleteDatabase(name))
            }
        }
    }

    private suspend fun TestScope.withFileStore(
        context: Context,
        name: String,
        block: suspend (RoomPersistenceStore) -> Unit,
    ) {
        val immediate = Executor { it.run() }
        val database = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        try {
            val persistence = RoomPersistenceStore(database, backgroundScope, clock)
            try {
                block(persistence)
            } finally {
                persistence.close()
            }
        } finally {
            database.close()
        }
    }

    private val clock: Clock = Clock.fixed(Instant.ofEpochSecond(1_704_067_200), ZoneOffset.UTC)
}
