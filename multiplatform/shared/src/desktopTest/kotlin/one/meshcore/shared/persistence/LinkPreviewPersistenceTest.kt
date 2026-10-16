package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.linkpreview.LinkPreviewData
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Round-trips the link-preview cache tier-2 store against the Room-backed [RoomLinkPreviewStore] on the
 * desktop JVM: hero/icon BLOBs and dimensions survive a save/fetch, a miss returns null, and an upsert on
 * the same URL replaces the row.
 */
class LinkPreviewPersistenceTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var store: RoomLinkPreviewStore

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("link-preview", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        store = RoomLinkPreviewStore(db.linkPreviewDao())
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun roundTripsPreviewWithBytesAndDimensions() = runBlocking {
        val data = LinkPreviewData(
            url = "https://example.com/page",
            title = "Title",
            imageBytes = byteArrayOf(1, 2, 3, 4),
            iconBytes = byteArrayOf(9, 8),
            imageWidth = 1200,
            imageHeight = 630,
            fetchedAtEpochSeconds = 1_700_000_000.0,
        )
        store.saveLinkPreview(data)

        val fetched = store.fetchLinkPreview("https://example.com/page")
        assertEquals("Title", fetched?.title)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), fetched?.imageBytes)
        assertContentEquals(byteArrayOf(9, 8), fetched?.iconBytes)
        assertEquals(1200, fetched?.imageWidth)
        assertEquals(630, fetched?.imageHeight)
        assertEquals(1_700_000_000.0, fetched?.fetchedAtEpochSeconds)
    }

    @Test
    fun fetchMissingUrlReturnsNull() = runBlocking {
        assertNull(store.fetchLinkPreview("https://absent.example/x"))
    }

    @Test
    fun upsertReplacesExistingRow() = runBlocking {
        val url = "https://example.com/page"
        store.saveLinkPreview(LinkPreviewData(url = url, title = "First"))
        store.saveLinkPreview(LinkPreviewData(url = url, title = "Second"))
        assertEquals("Second", store.fetchLinkPreview(url)?.title)
    }
}
