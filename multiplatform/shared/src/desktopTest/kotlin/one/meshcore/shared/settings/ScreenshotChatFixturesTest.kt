package one.meshcore.shared.settings

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.text.MentionTapEvaluator
import one.meshcore.shared.chat.text.MentionTapResult
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.persistence.ContactEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.RemoteNodeSessionEntity
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.persistence.toDto
import one.meshcore.shared.remotenode.isRoom
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Seeds the offline demo graph and asserts the chat P0 surfaces have data. */
class ScreenshotChatFixturesTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("screenshot-fixtures", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun seedPopulatesChannelContactsRoomAndAmbiguousMention() = runTest {
        ScreenshotChatFixtures.seed(db)

        val radio = ScreenshotChatFixtures.RADIO_ID
        val contacts: List<ContactEntity> = db.contactDao().listByRadio(radio)
        assertEquals(5, contacts.size)
        assertEquals(2, contacts.count { it.name == ScreenshotChatFixtures.ALICE_NAME })
        assertTrue(contacts.any { it.typeRawValue == ContactType.ROOM.raw })
        assertTrue(contacts.any { it.typeRawValue == ContactType.REPEATER.raw })
        assertTrue(contacts.any { it.name == ScreenshotChatFixtures.REPEATER_NAME })

        val channels = db.channelDao().listByRadio(radio)
        assertEquals(1, channels.size)
        assertEquals(ScreenshotChatFixtures.CHANNEL_NAME, channels.single().name)

        val channelMessages = db.messageDao().listChannelNewestFirst(
            radio,
            ScreenshotChatFixtures.CHANNEL_INDEX,
            limit = 10,
            offset = 0,
        )
        assertTrue(channelMessages.size >= 2)
        assertTrue(channelMessages.any { it.text.contains(ScreenshotChatFixtures.ALICE_NAME) })

        val sessions: List<RemoteNodeSessionEntity> = db.remoteNodeSessionDao().listByRadio(radio)
        assertEquals(1, sessions.size)
        assertTrue(sessions.single().toDto().isRoom)
        assertTrue(sessions.single().isConnected)

        val roomMessages = db.roomMessageDao().listBySession(ScreenshotChatFixtures.ROOM_SESSION_ID)
        assertEquals(1, roomMessages.size)

        val mention = MentionTapEvaluator.evaluate(
            name = ScreenshotChatFixtures.ALICE_NAME,
            contacts = contacts.map { it.toDto() },
            connectedDeviceName = ScreenshotChatFixtures.NODE_NAME,
        )
        assertIs<MentionTapResult.Picker>(mention)
        assertEquals(2, mention.matches.size)
    }
}
