package one.meshcore.shared.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.model.ContactType
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class RoomContactPeerFrameInsertFixture(private val kind: RoomContactPeerFrameCase) {
    private val rows = RoomContactDeleteAtomicityFixture()
    private val database get() = rows.database
    private val node = RoomNodeStore(
        radioId = ChatFixtures.RADIO,
        sessionDao = database.remoteNodeSessionDao(),
        roomMessageDao = database.roomMessageDao(),
        contactDao = database.contactDao(),
        now = { 0.0 },
    )
    private val chat = RoomChatStore(
        ChatFixtures.RADIO,
        database.chatDaos(),
        RxLogCorrelator(database.rxLogEntryDao(), nowEpochMs = { 0L }),
    )
    private val firstKey = rows.firstKey.copyOf().also { it[it.lastIndex] = FIRST_SUFFIX }
    private val secondKey = firstKey.copyOf().also { it[it.lastIndex] = SECOND_SUFFIX }
    private val first = ContactFrame(
        publicKey = firstKey, type = ContactType.CHAT, typeRawValue = UNKNOWN_TYPE,
        flags = FAVORITE_FLAGS, outPathLength = PATH_LENGTH, outPath = byteArrayOf(11, 12), name = FIRST_NAME,
        lastAdvertTimestamp = FIRST_TIME, latitude = LATITUDE, longitude = LONGITUDE, lastModified = FIRST_TIME,
    )
    private val second = first.copy(publicKey = secondKey, name = SECOND_NAME, flags = 0)
    private val repeated = first.copy(name = REPEATED_NAME, flags = NON_FAVORITE_FLAGS, lastModified = REPEAT_TIME)

    suspend fun exercise() {
        rows.seed()
        val foreign = requireNotNull(database.contactDao().getById(rows.foreignContact))
        database.contactDao().upsert(foreign.copy(publicKey = firstKey.copyOf()))
        val before = rows.snapshot()
        val survivors = rows.survivors()
        val originals = database.contactDao().listAll().map { it.toDto() }
        assertTrue(originals.none { it.radioID == ChatFixtures.RADIO && it.publicKey.contentEquals(firstKey) })
        kind.invoke(node, chat, ChatFixtures.RADIO, first)
        val inserted = current(firstKey)
        assertTrue(originals.none { it.id == inserted.id })
        assertEquals(
            JSON.encodeToJsonElement(expected(first, inserted, FAVORITE_FLAGS, favorite = true)),
            JSON.encodeToJsonElement(inserted),
        )
        kind.invoke(node, chat, ChatFixtures.RADIO, second)
        val other = current(secondKey)
        assertNotEquals(inserted.id, other.id)
        assertTrue(originals.none { it.id == other.id })
        kind.invoke(node, chat, ChatFixtures.RADIO, repeated)
        val latest = current(firstKey)
        assertEquals(inserted.id, latest.id)
        assertContentEquals(firstKey, latest.publicKey)
        assertEquals(ChatFixtures.RADIO, latest.radioID)
        val expectedRows = (
            originals + listOf(
                expected(repeated, inserted, MERGED_FLAGS, favorite = true),
                expected(second, other, 0, favorite = false),
            )
            ).sortedBy { it.id.value }
        val expectedSnapshot = JsonObject(
            before.first.toMutableMap().apply {
                put("contacts", JSON.encodeToJsonElement(expectedRows))
            },
        ) to before.second
        assertEquals(survivors, rows.survivors())
        assertEquals(expectedSnapshot, rows.snapshot(), "LAST: new sibling frame identity/default policy changed")
    }

    private suspend fun current(key: ByteArray): ContactDto = database.contactDao().listByRadio(ChatFixtures.RADIO)
        .single { it.publicKey.contentEquals(key) }.toDto()

    private fun expected(frame: ContactFrame, assigned: ContactDto, flags: Int, favorite: Boolean) = ContactDto(
        id = assigned.id, radioID = ChatFixtures.RADIO, publicKey = frame.publicKey,
        name = frame.name, typeRawValue = UNKNOWN_TYPE, flags = flags,
        outPathLength = PATH_LENGTH, outPath = frame.outPath, lastAdvertTimestamp = FIRST_TIME,
        latitude = LATITUDE, longitude = LONGITUDE, lastModified = frame.lastModified,
        isBlocked = false, isMuted = false, isFavorite = favorite, isPinned = false, unreadCount = 0,
        unreadMentionCount = 0, nickname = null, lastMessageDate = null, lastHeardTimestamp = null,
        ocvPreset = null, customOCVArrayString = null, avatarImageData = null,
    )

    fun close() = rows.close()

    private companion object {
        const val FIRST_SUFFIX: Byte = 0x77
        const val SECOND_SUFFIX: Byte = 0x78
        const val UNKNOWN_TYPE = 0x7F
        const val FAVORITE_FLAGS = 1
        const val NON_FAVORITE_FLAGS = 2
        const val MERGED_FLAGS = 3
        const val PATH_LENGTH = 2
        const val FIRST_TIME = 1_700_000_100L
        const val REPEAT_TIME = 1_700_000_200L
        const val LATITUDE = 45.2
        const val LONGITUDE = -122.3
        const val FIRST_NAME = "Inserted sibling contact"
        const val SECOND_NAME = "Distinct full key"
        const val REPEATED_NAME = "Repeated first key"
        val JSON = Json { encodeDefaults = true }
    }
}
