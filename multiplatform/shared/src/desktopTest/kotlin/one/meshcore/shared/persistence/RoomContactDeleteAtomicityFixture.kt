package one.meshcore.shared.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import java.io.File

internal class RoomContactDeleteAtomicityFixture(buildDatabase: (String) -> MeshDatabase = ::meshDatabase) {
    private val file = File.createTempFile("contact-delete-atomicity", ".db").also { it.delete() }
    val database: MeshDatabase = buildDatabase(file.absolutePath)
    private val messageIds = mutableMapOf<Uuid, List<Uuid>>()
    private val json = Json { encodeDefaults = true }
    private val foreignRadio = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")
    private var failingContact: Uuid? = null
    private var failure: Throwable? = null
    private var failingMessageContact: Uuid? = null
    private var messageFailure: Throwable? = null

    val firstContact: Uuid = ChatFixtures.freshUuid()
    val secondContact: Uuid = ChatFixtures.freshUuid()
    val foreignContact: Uuid = ChatFixtures.freshUuid()
    val firstKey: ByteArray = ByteArray(KEY_BYTES) { FIRST_KEY_BYTE }
    val secondKey: ByteArray = ByteArray(KEY_BYTES) { SECOND_KEY_BYTE }
    var childRowsClearedAtFault: Boolean = false
        private set
    var earlierContactRemovedAtFault: Boolean = false
        private set
    var priorChildRowsClearedAtMessageFault: Boolean = false
        private set

    private val contacts = FinalDeleteFaultDao(database.contactDao()) { id ->
        if (id == failingContact) {
            childRowsClearedAtFault = childRowCount(id) == 0
            earlierContactRemovedAtFault = database.contactDao().getById(firstContact) == null
            throw checkNotNull(failure)
        }
    }
    private val messages = FinalMessageDeleteFaultDao(database.messageDao()) { id ->
        if (id == failingMessageContact) {
            val directRows = database.messageDao().directMessageIdsForContact(id).size
            priorChildRowsClearedAtMessageFault = directRows > 0 && childRowCount(id) == directRows
            throw checkNotNull(messageFailure)
        }
    }
    val store = RoomContactStore(
        RoomContactParts(database, Uuid::random, contactDao = contacts, messageDao = messages),
    )

    suspend fun seed() {
        seedContact(firstContact, ChatFixtures.RADIO, firstKey)
        seedContact(secondContact, ChatFixtures.RADIO, secondKey)
        seedContact(foreignContact, foreignRadio, firstKey)
        database.messageDao().upsert(
            ChatFixtures.message(contactID = null, channelIndex = CHANNEL_SLOT).toEntity(),
        )
    }

    fun failFinalDelete(contactID: Uuid, error: Throwable) {
        failingContact = contactID
        failure = error
    }

    fun failFinalMessageDelete(contactID: Uuid, error: Throwable) {
        failingMessageContact = contactID
        messageFailure = error
    }

    suspend fun prepareUnreferencedFirstContact() {
        val ids = checkNotNull(messageIds[firstContact])
        database.messageDao().deleteDirectByContact(firstContact)
        database.messageRepeatDao().deleteByMessageIds(ids)
    }

    suspend fun snapshot(): Pair<JsonObject, List<PendingSendEntity>> {
        val rows = buildJsonObject {
            put(
                "contacts",
                json.encodeToJsonElement(database.contactDao().listAll().sortedBy { it.id.value }.map { it.toDto() }),
            )
            put(
                "messages",
                json.encodeToJsonElement(database.messageDao().listAll().sortedBy { it.id.value }.map { it.toDto() }),
            )
            put(
                "reactions",
                json.encodeToJsonElement(database.reactionDao().listAll().sortedBy { it.id.value }.map { it.toDto() }),
            )
            put(
                "repeats",
                json.encodeToJsonElement(
                    database.messageRepeatDao().listAll().sortedBy { it.id.value }.map { it.toDto() },
                ),
            )
        }
        val pending = database.pendingSendDao().listByRadio(ChatFixtures.RADIO) +
            database.pendingSendDao().listByRadio(foreignRadio)
        return rows to pending.sortedBy { it.id.value }
    }

    suspend fun survivors(): Pair<JsonObject, List<PendingSendEntity>> {
        val rows = buildJsonObject {
            put(
                "foreignContact",
                json.encodeToJsonElement(database.contactDao().getById(foreignContact)?.toDto()),
            )
            put(
                "foreignMessages",
                json.encodeToJsonElement(
                    database.messageDao().listByRadio(foreignRadio).sortedBy { it.id.value }.map { it.toDto() },
                ),
            )
            put(
                "foreignReactions",
                json.encodeToJsonElement(
                    database.reactionDao().listByRadio(foreignRadio).sortedBy { it.id.value }.map { it.toDto() },
                ),
            )
            val foreignIds = checkNotNull(messageIds[foreignContact]).toSet()
            put(
                "foreignRepeats",
                json.encodeToJsonElement(
                    database.messageRepeatDao().listAll()
                        .filter { it.messageID in foreignIds }.sortedBy { it.id.value }.map { it.toDto() },
                ),
            )
            put(
                "channelMessages",
                json.encodeToJsonElement(
                    database.messageDao().listAll().filter { it.channelIndex == CHANNEL_SLOT }.map { it.toDto() },
                ),
            )
        }
        return rows to database.pendingSendDao().listByRadio(foreignRadio).sortedBy { it.id.value }
    }

    suspend fun childRowCount(contactID: Uuid): Int {
        val ids = checkNotNull(messageIds[contactID]).toSet()
        val messages = database.messageDao().directMessageIdsForContact(contactID).size
        val reactions = database.reactionDao().listAll().count { it.contactID == contactID }
        val repeats = database.messageRepeatDao().listAll().count { it.messageID in ids }
        val pending = database.pendingSendDao().listByRadio(ChatFixtures.RADIO)
            .count { it.contactID == contactID || it.messageID in ids }
        return messages + reactions + repeats + pending
    }

    fun close() {
        try {
            database.close()
        } finally {
            val owned = listOf("", "-wal", "-shm").map { File(file.absolutePath + it) }
            owned.forEach { it.delete() }
            check(owned.none { it.exists() })
        }
    }

    private suspend fun seedContact(id: Uuid, radioID: Uuid, key: ByteArray) {
        store.saveContact(
            ChatFixtures.contact(
                name = "Fabricated contact",
                isFavorite = true,
                isPinned = true,
                unreadCount = UNREAD_COUNT,
                lastMessageDate = ChatFixtures.BASE_EPOCH_SECONDS,
                nickname = "Local fixture name",
                id = id,
            ).copy(radioID = radioID, publicKey = key, flags = FAVORITE_FLAG),
        )
        val ids = mutableListOf<Uuid>()
        for ((index, status) in MessageStatus.entries.withIndex()) {
            val messageID = ChatFixtures.freshUuid().also { ids += it }
            database.messageDao().upsert(
                ChatFixtures.message(id = messageID, contactID = id, status = status)
                    .copy(radioID = radioID, pathNodes = byteArrayOf(PATH_BYTE)).toEntity(),
            )
            seedDependents(id, radioID, messageID, index)
        }
        messageIds[id] = ids
    }

    private suspend fun seedDependents(contactID: Uuid, radioID: Uuid, messageID: Uuid, sequence: Int) {
        database.reactionDao().upsert(
            ReactionEntity(
                id = ChatFixtures.freshUuid(),
                messageID = messageID,
                emoji = "fixture",
                senderName = "Fabricated sender",
                messageHash = "fixture hash",
                rawText = "fixture reaction",
                receivedAt = ChatFixtures.BASE_EPOCH_SECONDS,
                radioID = radioID,
                contactID = contactID,
            ),
        )
        database.messageRepeatDao().upsert(
            MessageRepeatEntity(
                id = ChatFixtures.freshUuid(),
                messageID = messageID,
                receivedAt = ChatFixtures.BASE_EPOCH_SECONDS,
                pathNodes = byteArrayOf(PATH_BYTE),
            ),
        )
        database.pendingSendDao().upsert(
            PendingSendEntity(
                id = ChatFixtures.freshUuid(),
                radioID = radioID,
                messageID = messageID,
                kindRawValue = DM_KIND,
                isResend = true,
                messageText = "Fabricated pending text",
                messageTimestamp = ChatFixtures.BASE_EPOCH_SECONDS.toLong(),
                sequence = sequence,
                enqueuedAt = ChatFixtures.BASE_EPOCH_SECONDS,
                contactID = contactID,
                attemptCount = 0,
            ),
        )
    }

    class FinalDeleteFailure : Exception("Final fixture contact write failed")

    private class FinalDeleteFaultDao(
        private val delegate: ContactDao,
        private val beforeDelete: suspend (Uuid) -> Unit,
    ) : ContactDao by delegate {
        override suspend fun deleteById(id: Uuid) {
            beforeDelete(id)
            delegate.deleteById(id)
        }
    }

    private class FinalMessageDeleteFaultDao(
        private val delegate: MessageDao,
        private val beforeDelete: suspend (Uuid) -> Unit,
    ) : MessageDao by delegate {
        override suspend fun deleteDirectByContact(contactID: Uuid) {
            beforeDelete(contactID)
            delegate.deleteDirectByContact(contactID)
        }
    }

    private companion object {
        const val KEY_BYTES = 32
        const val FIRST_KEY_BYTE: Byte = 0x31
        const val SECOND_KEY_BYTE: Byte = 0x32
        const val PATH_BYTE: Byte = 0x01
        const val FAVORITE_FLAG = 1
        const val UNREAD_COUNT = 3
        const val CHANNEL_SLOT = 1
        const val DM_KIND = 0
    }
}
