package one.meshcore.shared.chat

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ChatDaos
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.RoomChatStore
import one.meshcore.shared.persistence.RxLogCorrelator
import one.meshcore.shared.persistence.meshDatabase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The paged thread query against real SQLite. A reconnect-drained block shares one `sortDate`, so without
 * the send-time tie-breakers the rows come back in storage order: the page window then holds the wrong
 * rows and a row can fall between two pages and never be shown. Mirrors the Swift fetch descriptor's
 * sortDate / timestamp / createdAt ordering.
 */
class ThreadPageOrderTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var store: RoomChatStore

    private val radioID = ChatFixtures.RADIO
    private val contactID = ChatFixtures.CONTACT

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("thread-page-order", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        store = RoomChatStore(
            radioID,
            ChatDaos(
                db.messageDao(),
                db.contactDao(),
                db.channelDao(),
                db.messageRepeatDao(),
                db.pendingSendDao(),
                db.reactionDao(),
            ),
            RxLogCorrelator(db.rxLogEntryDao()),
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun `a drained block pages by send time, not by insertion order`() = runBlocking {
        // One block anchor for every row; inserted oldest-send-time first, so storage order is the reverse
        // of the order the timeline needs.
        val sendTimes = listOf(10L, 20L, 30L, 40L)
        sendTimes.forEach { store.saveMessage(blockRow(timestamp = it)) }

        val newest = store.fetchDirectMessages(contactID, limit = PAGE, offset = 0)
        val older = store.fetchDirectMessages(contactID, limit = PAGE, offset = PAGE)

        assertEquals(listOf("send-30", "send-40"), newest.map { it.text })
        assertEquals(listOf("send-10", "send-20"), older.map { it.text })
        assertEquals(sendTimes.size, (newest + older).distinctBy { it.id }.size, "no row is skipped or repeated")
    }

    @Test
    fun `rows tied on send time page by creation instant`() = runBlocking {
        val createdAts = listOf(1.0, 2.0, 3.0, 4.0)
        createdAts.forEach { store.saveMessage(blockRow(timestamp = TIED_SEND_TIME, createdAt = it)) }

        val newest = store.fetchDirectMessages(contactID, limit = PAGE, offset = 0)
        val older = store.fetchDirectMessages(contactID, limit = PAGE, offset = PAGE)

        assertEquals(listOf(3.0, 4.0), newest.map { it.createdAt })
        assertEquals(listOf(1.0, 2.0), older.map { it.createdAt })
    }

    @Test
    fun `fully tied rows still page without overlap`() = runBlocking {
        // Same anchor, same send time, same creation instant: only the row identity can order them, and it
        // must order them the same way for every page of the window.
        val ids = (0 until TIE_COUNT).map { ChatFixtures.freshUuid() }
        ids.forEach { store.saveMessage(blockRow(timestamp = TIED_SEND_TIME, createdAt = 1.0, id = it)) }

        val newest = store.fetchDirectMessages(contactID, limit = PAGE, offset = 0)
        val older = store.fetchDirectMessages(contactID, limit = PAGE, offset = PAGE)
        val seen = (newest + older).map { it.id }

        assertEquals(TIE_COUNT, seen.distinct().size, "every row appears exactly once across the boundary")
        assertEquals(newest.map { it.id }, store.fetchDirectMessages(contactID, PAGE, 0).map { it.id })
    }

    @Test
    fun `a same-sender burst relayed out of order reads in send order on the first page`() = runBlocking {
        // Every fetch restores send order inside the burst, so the first paint reads the same as the thread
        // does after paging back — it does not silently re-order itself later.
        listOf(30L, 10L, 20L).forEachIndexed { index, sendTime ->
            store.saveMessage(
                ChatFixtures.message(
                    text = "burst-$sendTime",
                    timestamp = sendTime,
                    createdAt = BLOCK_ANCHOR + index,
                    sortDate = BLOCK_ANCHOR + index,
                    contactID = contactID,
                    channelIndex = null,
                ),
            )
        }

        val page = store.fetchDirectMessages(contactID, limit = 10, offset = 0)

        assertEquals(listOf("burst-10", "burst-20", "burst-30"), page.map { it.text })
    }

    private fun blockRow(
        timestamp: Long,
        createdAt: Double = timestamp.toDouble(),
        id: Uuid = ChatFixtures.freshUuid(),
    ): MessageDto = ChatFixtures.message(
        id = id,
        text = "send-$timestamp",
        timestamp = timestamp,
        createdAt = createdAt,
        sortDate = BLOCK_ANCHOR,
        contactID = contactID,
        channelIndex = null,
    )

    private companion object {
        const val PAGE = 2
        const val TIE_COUNT = 4
        const val TIED_SEND_TIME = 500L
        const val BLOCK_ANCHOR = 9_000.0
    }
}
