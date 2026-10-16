package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.ChatErrorMessages
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.previewText
import one.meshcore.shared.chat.reload
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [RoomConversationListStore]: the batch preview fetch returns each conversation's rows in
 * ascending time (newest last, so the preview is the last element), and the optimistic mute write persists.
 */
class RoomConversationListStoreTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var store: RoomConversationListStore

    private val radioID = ChatFixtures.RADIO
    private val contactID = ChatFixtures.CONTACT

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("room-list-store", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        store = RoomConversationListStore(
            radioID = radioID,
            daos = db.conversationDaos(),
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun lastContactMessagesReturnNewestAsTheLastElement() = runBlocking {
        db.contactDao().upsert(ChatFixtures.contact(name = "Alice", id = contactID).toEntity())
        db.messageDao().upsert(dm(sortDate = 10.0, text = "older").toEntity())
        db.messageDao().upsert(dm(sortDate = 20.0, text = "newer").toEntity())

        val previews = store.fetchLastContactMessages(listOf(contactID), limit = 5)
        assertEquals("newer", previews[contactID]?.last()?.text)
    }

    @Test
    fun conversationContactsExcludeContactsWithoutMessages() = runBlocking {
        db.contactDao().upsert(
            ChatFixtures.contact(name = "Alice", id = contactID, lastMessageDate = 10.0).toEntity(),
        )
        db.contactDao().upsert(ChatFixtures.contact(name = "Bob").toEntity())

        val conversations = store.fetchConversationContacts(radioID)
        assertEquals(listOf("Alice"), conversations.map { it.name })
    }

    @Test
    fun contactIDsIncludeMessagelessBlockedAndRepeaterRowsOnlyForTheRequestedRadio() = runBlocking {
        val empty = ChatFixtures.contact("Empty")
        val blocked = ChatFixtures.contact("Blocked", isBlocked = true)
        val repeater = ChatFixtures.contact("Repeater").copy(typeRawValue = ContactType.REPEATER.raw)
        val foreign = ChatFixtures.contact("Foreign").copy(radioID = Uuid.random())
        for (contact in listOf(empty, blocked, repeater, foreign)) db.contactDao().upsert(contact.toEntity())

        assertEquals(setOf(empty.id, blocked.id, repeater.id), store.fetchContactIDs(radioID))
        assertEquals(setOf(foreign.id), store.fetchContactIDs(foreign.radioID))
        assertTrue(store.fetchConversationContacts(radioID).isEmpty())
        db.contactDao().deleteById(empty.id)
        assertEquals(setOf(blocked.id, repeater.id), store.fetchContactIDs(radioID))
    }

    @Test
    fun setContactMutedPersists() = runBlocking {
        db.contactDao().upsert(ChatFixtures.contact(name = "Alice", id = contactID).toEntity())
        store.setContactMuted(contactID, isMuted = true)
        assertTrue(db.contactDao().getById(contactID)!!.isMuted)
    }

    @Test
    fun localPinsSurviveStaleLiveWritesWithoutChangingRadioFavorites() = runBlocking {
        val contact = ChatFixtures.contact(name = "Alice", isFavorite = true).toEntity()
        val channel = ChatFixtures.channel(name = "Ops").toEntity()
        val room = ChatFixtures.room(name = "Room").toEntity()
        db.contactDao().upsert(contact)
        db.channelDao().upsert(channel)
        db.remoteNodeSessionDao().upsert(room)

        store.setContactPinned(contact.id, true)
        store.setChannelPinned(channel.id, true)
        store.setSessionPinned(room.id, true)
        db.contactDao().upsertPreservingPin(contact.copy(name = "Updated", unreadCount = 3))
        db.channelDao().upsertPreservingPin(channel.copy(name = "Updated"))
        db.remoteNodeSessionDao().upsertPreservingPin(room.copy(isConnected = true))

        val savedContact = db.contactDao().getById(contact.id)!!
        assertTrue(savedContact.isPinned)
        assertTrue(savedContact.isFavorite)
        assertEquals(3, savedContact.unreadCount)
        assertTrue(db.channelDao().getById(channel.id)!!.isPinned)
        assertTrue(db.remoteNodeSessionDao().getById(room.id)!!.isPinned)

        store.setContactPinned(contact.id, false)
        store.setChannelPinned(channel.id, false)
        store.setSessionPinned(room.id, false)
        assertFalse(db.contactDao().getById(contact.id)!!.isPinned)
        assertTrue(db.contactDao().getById(contact.id)!!.isFavorite)
        assertEquals(3, db.contactDao().getById(contact.id)!!.unreadCount)
        assertFalse(db.channelDao().getById(channel.id)!!.isPinned)
        assertFalse(db.remoteNodeSessionDao().getById(room.id)!!.isPinned)
    }

    @Test
    fun missingPinTargetsFail() = runBlocking {
        assertFailsWith<IllegalStateException> { store.setContactPinned(contactID, true) }
        assertFailsWith<IllegalStateException> { store.setChannelPinned(contactID, true) }
        assertFailsWith<IllegalStateException> { store.setSessionPinned(contactID, true) }
        Unit
    }

    @Test
    fun lastChannelMessagesReturnNewestAsTheLastElement() = runBlocking {
        val channel = ChatFixtures.channel(name = "Public", index = 0)
        db.channelDao().upsert(channel.toEntity())
        db.messageDao().upsert(
            ChatFixtures.message(
                id = ChatFixtures.freshUuid(),
                text = "older channel",
                sortDate = 10.0,
                contactID = null,
                channelIndex = 0,
            ).toEntity(),
        )
        db.messageDao().upsert(
            ChatFixtures.message(
                id = ChatFixtures.freshUuid(),
                text = "newer channel",
                sortDate = 20.0,
                contactID = null,
                channelIndex = 0,
            ).toEntity(),
        )

        val previews = store.fetchLastChannelMessages(
            listOf(one.meshcore.shared.chat.ChannelPreviewKey(channel.id, radioID, channelIndex = 0)),
            limit = 5,
        )
        assertEquals("newer channel", previews[channel.id]?.last()?.text)
    }

    @Test
    fun screenshotFixturesBindChannelPreviewThroughViewModel() = runBlocking {
        one.meshcore.shared.settings.ScreenshotChatFixtures.seed(db)
        val listStore = RoomConversationListStore(
            radioID = one.meshcore.shared.settings.ScreenshotChatFixtures.RADIO_ID,
            daos = db.conversationDaos(),
        )
        val vm = one.meshcore.shared.chat.ConversationListViewModel(
            one.meshcore.shared.chat.ConversationListViewModel.Dependencies(
                errorMessages = { ChatErrorMessages.developerEnglish() },
                store = { listStore },
                radioId = { one.meshcore.shared.settings.ScreenshotChatFixtures.RADIO_ID },
            ),
        )
        vm.reload()
        val publicId = one.meshcore.shared.settings.ScreenshotChatFixtures.CHANNEL_ID
        val preview = vm.previewText(publicId)
        assertTrue(preview != null && preview.isNotBlank(), "Public channel should show last-message preview")
        assertTrue(
            preview!!.contains("point") || preview.contains("RV") || preview.contains("check-in"),
            "preview should match fixture message text, got: $preview",
        )
    }

    private fun dm(sortDate: Double, text: String) =
        ChatFixtures.message(id = ChatFixtures.freshUuid(), text = text, sortDate = sortDate, contactID = contactID)

    @Test
    fun unseenOutgoingFailedDmIsInFailedSendKeys() = runBlocking {
        db.messageDao().upsert(
            ChatFixtures.message(
                text = "fail",
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.FAILED,
                contactID = contactID,
            ).toEntity(),
        )

        val keys = store.fetchFailedSendConversationKeys(radioID)
        assertEquals(setOf(contactID), keys.contactIDs)
    }

    @Test
    fun seenOutgoingFailedDmIsAbsentFromFailedSendKeys() = runBlocking {
        db.messageDao().upsert(
            ChatFixtures.message(
                text = "fail",
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.FAILED,
                contactID = contactID,
            ).copy(failureSeen = true).toEntity(),
        )

        val keys = store.fetchFailedSendConversationKeys(radioID)
        assertTrue(keys.contactIDs.isEmpty())
    }

    @Test
    fun incomingFailedDmIsAbsentFromFailedSendKeys() = runBlocking {
        db.messageDao().upsert(
            ChatFixtures.message(
                text = "fail",
                direction = MessageDirection.INCOMING,
                status = MessageStatus.FAILED,
                contactID = contactID,
            ).toEntity(),
        )

        val keys = store.fetchFailedSendConversationKeys(radioID)
        assertTrue(keys.contactIDs.isEmpty())
    }
}
