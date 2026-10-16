package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.DebugLogSource
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Room-backed [one.meshcore.shared.settings.DebugLogStore] save / fetch / prune / clear. */
class RoomDebugLogStoreTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var store: RoomDebugLogStore

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-debug-log", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        store = RoomDebugLogStore.fromDatabase(db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun saveAndFetchNewestFirstWithinWindow() = runBlocking {
        store.saveDebugLogEntries(
            listOf(
                entry(id = 1, timestamp = 100, message = "old"),
                entry(id = 2, timestamp = 200, message = "mid"),
                entry(id = 3, timestamp = 300, message = "new"),
            ),
        )
        val rows = store.fetchDebugLogEntries(since = 150, limit = 10)
        assertEquals(listOf("new", "mid"), rows.map { it.message })
    }

    @Test
    fun pruneRemovesEntriesOlderThanCutoff() = runBlocking {
        store.saveDebugLogEntries(
            (1..5).map { entry(id = it, timestamp = it * 10L, message = "m$it") },
        )
        // Cutoff 35 drops timestamps 10/20/30 (m1..m3); the ceiling is high enough to keep the rest.
        store.pruneDebugLogEntries(olderThanMillis = 35, keepCount = 1000)
        val remaining = store.fetchDebugLogEntries(since = 0, limit = 10)
        assertEquals(listOf("m5", "m4"), remaining.map { it.message })
    }

    @Test
    fun pruneEnforcesRowCeilingAfterAgePrune() = runBlocking {
        // All entries are within the age window; only the ceiling should trim to the newest 2.
        store.saveDebugLogEntries(
            (1..5).map { entry(id = it, timestamp = it * 10L, message = "m$it") },
        )
        store.pruneDebugLogEntries(olderThanMillis = 0, keepCount = 2)
        assertEquals(2, store.countDebugLogEntries())
        val remaining = store.fetchDebugLogEntries(since = 0, limit = 10)
        assertEquals(listOf("m5", "m4"), remaining.map { it.message })
    }

    @Test
    fun clearRemovesAll() = runBlocking {
        store.saveDebugLogEntries(listOf(entry(id = 1, timestamp = 1, message = "x")))
        store.clearDebugLogEntries()
        assertEquals(0, store.countDebugLogEntries())
    }

    private fun entry(id: Int, timestamp: Long, message: String) = DebugLogEntryDto.of(
        id = Uuid.normalized("00000000-0000-0000-0000-" + id.toString().padStart(12, '0')),
        timestamp = timestamp,
        level = DebugLogLevel.INFO,
        source = DebugLogSource("test", "c"),
        message = message,
    )
}
