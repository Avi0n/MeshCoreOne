package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.TracePathRunDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toLowercaseHex
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Room adapter for Tools → Trace Path: create/list/rename/append/delete round-trip and cascade
 * delete against a real on-disk MeshDatabase (survives process death via the same tables).
 */
class RoomSavedTracePathStoreTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var store: RoomSavedTracePathStore

    private val radio = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
    private val otherRadio = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")

    private var idCounter = 0
    private var clock = 0.0

    @BeforeTest
    fun setUp() {
        idCounter = 0
        clock = 0.0
        dbFile = File.createTempFile("room-saved-trace", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        store = RoomSavedTracePathStore(
            pathDao = db.savedTracePathDao(),
            runDao = db.tracePathRunDao(),
            newId = {
                Uuid.normalized("00000000-0000-0000-0000-" + (++idCounter).toString().padStart(12, '0'))
            },
            now = { clock },
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    private fun run(id: String, date: Double, success: Boolean, rtt: Int) = TracePathRunDto(
        id = Uuid.normalized(
            "00000000-0000-0000-0000-" + id.encodeToByteArray().toLowercaseHex().padStart(12, '0').takeLast(12),
        ),
        date = date,
        success = success,
        roundTripMs = rtt,
    )

    @Test
    fun createFetchAndListNewestFirst() = runBlocking {
        clock = 10.0
        val first = store.createSavedTracePath(radio, "Tower", byteArrayOf(0x31, 0x4F), 1, null)
        clock = 20.0
        val second = store.createSavedTracePath(radio, "Barn", byteArrayOf(0x11, 0x22), 1, null)
        store.createSavedTracePath(otherRadio, "Other", byteArrayOf(0x99.toByte()), 1, null)

        val reloaded = store.fetchSavedTracePath(first.id)!!
        assertEquals(first.id, reloaded.id)
        assertEquals(first.name, reloaded.name)
        assertTrue(first.pathBytes.contentEquals(reloaded.pathBytes))
        val listed = store.fetchSavedTracePaths(radio)
        assertEquals(listOf(second.id, first.id), listed.map { it.id })
    }

    @Test
    fun appendRunAndRenameAndDeleteCascade() = runBlocking {
        clock = 1.0
        val path = store.createSavedTracePath(radio, "Path", byteArrayOf(0xAA.toByte()), 1, null)
        store.appendTracePathRun(path.id, run("A", date = 1.0, success = true, rtt = 100))
        store.appendTracePathRun(path.id, run("B", date = 2.0, success = false, rtt = 0))

        val reloaded = store.fetchSavedTracePath(path.id)!!
        assertEquals(2, reloaded.runs.size)
        assertEquals(100, reloaded.runs.first().roundTripMs)

        store.updateSavedTracePathName(path.id, "Renamed")
        assertEquals("Renamed", store.fetchSavedTracePath(path.id)!!.name)

        store.deleteSavedTracePath(path.id)
        assertNull(store.fetchSavedTracePath(path.id))
        assertTrue(store.fetchSavedTracePaths(radio).isEmpty())
        assertTrue(db.tracePathRunDao().listByPath(path.id).isEmpty())
    }

    @Test
    fun createWithInitialRunPersistsRun() = runBlocking {
        clock = 5.0
        val initial = run("I", date = 5.0, success = true, rtt = 42)
        val path = store.createSavedTracePath(
            radioID = radio,
            name = "Seeded",
            pathBytes = byteArrayOf(0x01),
            hashSize = 1,
            initialRun = initial,
        )
        assertEquals(listOf(initial), store.fetchSavedTracePath(path.id)!!.runs)
    }
}
