package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.pathdetail.HeardRepeatEcho
import one.meshcore.shared.chat.pathdetail.HeardRepeatsService
import one.meshcore.shared.model.MessageDirection
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Room-backed heard-repeat correlation: find outgoing channel row, dedupe by RX-log id, increment
 * `heardRepeats` — the persistence half of F-008 wiring.
 */
class RoomHeardRepeatStoreTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var store: RoomHeardRepeatStore

    private val radioID = ChatFixtures.RADIO
    private val nodeName = "TestNode"

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("room-heard-repeat", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        store = RoomHeardRepeatStore(db.messageDao(), db.messageRepeatDao())
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun correlatesEchoToOutgoingChannelMessageAndIncrementsHeardRepeats() = runBlocking {
        val messageID = ChatFixtures.freshUuid()
        val sendTs = 1_700_000_000L
        val text = "north repeater check"
        db.messageDao().upsert(
            ChatFixtures.message(
                id = messageID,
                text = text,
                timestamp = sendTs,
                channelIndex = 2,
                direction = MessageDirection.OUTGOING,
            ).toEntity(),
        )

        val events = mutableListOf<one.meshcore.shared.chat.pathdetail.HeardRepeatEvent>()
        val service = HeardRepeatsService(
            store = store,
            newRepeatId = { ChatFixtures.freshUuid() },
            onRepeat = { events += it },
        )
        service.configure(radioID = radioID)

        val rxId = ChatFixtures.freshUuid()
        val count = service.processForRepeats(
            HeardRepeatEcho(
                id = rxId,
                isGroupText = true,
                isDecryptSuccess = true,
                decodedText = "$nodeName: $text",
                channelIndex = 2,
                senderTimestamp = sendTs,
                receivedAt = 1_700_000_100.0,
                pathNodes = byteArrayOf(0x11),
                pathLength = 1,
                snr = 5.0,
                rssi = -80,
            ),
        )

        assertEquals(1, count)
        assertEquals(1, db.messageDao().getById(messageID)!!.heardRepeats)
        assertEquals(1, store.fetchMessageRepeats(messageID).size)
        assertTrue(store.messageRepeatExists(rxId))
        assertEquals(listOf(messageID), events.map { it.messageID })

        // Same RX-log entry must not double-count.
        assertNull(
            service.processForRepeats(
                HeardRepeatEcho(
                    id = rxId,
                    isGroupText = true,
                    isDecryptSuccess = true,
                    decodedText = "$nodeName: $text",
                    channelIndex = 2,
                    senderTimestamp = sendTs,
                    receivedAt = 1_700_000_101.0,
                    pathNodes = byteArrayOf(0x11),
                    pathLength = 1,
                ),
            ),
        )
        assertEquals(1, db.messageDao().getById(messageID)!!.heardRepeats)
    }

    @Test
    fun ignoresIncomingChannelRows() = runBlocking {
        val sendTs = 1_700_000_000L
        db.messageDao().upsert(
            ChatFixtures.message(
                text = "echo",
                timestamp = sendTs,
                channelIndex = 1,
                direction = MessageDirection.INCOMING,
            ).toEntity(),
        )
        assertNull(
            store.findSentChannelMessage(
                radioID = radioID,
                channelIndex = 1,
                timestamp = sendTs,
                text = "echo",
            ),
        )
    }

    @Test
    fun incrementHeardRepeatsDoesNotRewriteStatus() = runBlocking {
        val messageID = ChatFixtures.freshUuid()
        db.messageDao().upsert(
            ChatFixtures.message(
                id = messageID,
                text = "status race",
                timestamp = 1,
                channelIndex = 0,
                direction = MessageDirection.OUTGOING,
                status = one.meshcore.shared.model.MessageStatus.PENDING,
            ).toEntity(),
        )
        val pending = db.messageDao().getById(messageID)!!
        db.messageDao().upsert(pending.copy(statusRawValue = one.meshcore.shared.model.MessageStatus.SENT.raw))

        assertEquals(1, store.incrementMessageHeardRepeats(messageID))
        val after = db.messageDao().getById(messageID)!!
        assertEquals(one.meshcore.shared.model.MessageStatus.SENT.raw, after.statusRawValue)
        assertEquals(1, after.heardRepeats)
    }

    @Test
    fun saveMessageRepeatRoundTrips() = runBlocking {
        val messageID = ChatFixtures.freshUuid()
        val repeatId = ChatFixtures.freshUuid()
        val rxId = ChatFixtures.freshUuid()
        db.messageDao().upsert(
            ChatFixtures.message(id = messageID, text = "x", timestamp = 1, channelIndex = 0).toEntity(),
        )
        store.saveMessageRepeat(
            MessageRepeatDto(
                id = repeatId,
                messageID = messageID,
                receivedAt = 42.0,
                pathNodes = byteArrayOf(1, 2),
                pathLength = 2,
                snr = 1.5,
                rssi = -90,
                rxLogEntryID = rxId,
            ),
        )
        val listed = store.fetchMessageRepeats(messageID)
        assertEquals(1, listed.size)
        assertEquals(repeatId, listed.single().id)
        assertEquals(rxId, listed.single().rxLogEntryID)
        assertEquals(42.0, listed.single().receivedAt)
    }
}
