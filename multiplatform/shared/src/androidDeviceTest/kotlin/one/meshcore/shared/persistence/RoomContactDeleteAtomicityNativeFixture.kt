package one.meshcore.shared.persistence

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import org.json.JSONObject
import java.io.File

internal class RoomContactDeleteAtomicityNativeFixture(queryDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "contact-atomicity-${Uuid.random().value}.db"
    val database = meshDatabase(context, queryDispatcher, databaseName)
    val radioID = Uuid.random()
    private val foreignRadio = Uuid.random()
    val firstContact = Uuid.random()
    val secondContact = Uuid.random()
    private val foreignContact = Uuid.random()
    val firstKey = ByteArray(KEY_BYTES) { FIRST_KEY_BYTE }
    val secondKey = ByteArray(KEY_BYTES) { SECOND_KEY_BYTE }
    private val json = Json { encodeDefaults = true }
    private val messageIds = mutableMapOf<Uuid, List<Uuid>>()
    private var failingContact: Uuid? = null
    private var failure: Throwable? = null
    var childRowsClearedAtFault = false
        private set
    var earlierContactRemovedAtFault = false
        private set

    private val contacts = FinalDeleteFaultDao(database.contactDao()) { id ->
        if (id == failingContact) {
            childRowsClearedAtFault = childRowCount(id) == 0
            earlierContactRemovedAtFault = database.contactDao().getById(firstContact) == null
            throw checkNotNull(failure)
        }
    }
    val store = RoomContactStore(RoomContactParts(database, Uuid::random, contactDao = contacts))

    suspend fun seed() {
        seedContact(firstContact, radioID, firstKey)
        seedContact(secondContact, radioID, secondKey)
        seedContact(foreignContact, foreignRadio, firstKey)
        database.messageDao().upsert(
            message(radioID, null, MessageStatus.SENT).copy(channelIndex = CHANNEL_SLOT).toEntity(),
        )
    }

    fun failFinalDelete(id: Uuid, error: Throwable) {
        failingContact = id
        failure = error
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
        val pending = database.pendingSendDao().listByRadio(radioID) +
            database.pendingSendDao().listByRadio(foreignRadio)
        return rows to pending.sortedBy { it.id.value }
    }

    suspend fun survivors(): Pair<JsonObject, List<PendingSendEntity>> {
        val rows = buildJsonObject {
            put("foreignContact", json.encodeToJsonElement(database.contactDao().getById(foreignContact)?.toDto()))
            put("neighborContact", json.encodeToJsonElement(database.contactDao().getById(secondContact)?.toDto()))
            val survivingIds = checkNotNull(messageIds[foreignContact])
                .plus(checkNotNull(messageIds[secondContact])).toSet()
            put(
                "messages",
                json.encodeToJsonElement(
                    database.messageDao().listAll()
                        .filter { it.id in survivingIds || it.channelIndex != null }
                        .sortedBy { it.id.value }.map { it.toDto() },
                ),
            )
            put(
                "reactions",
                json.encodeToJsonElement(
                    database.reactionDao().listAll()
                        .filter { it.messageID in survivingIds }.sortedBy { it.id.value }.map { it.toDto() },
                ),
            )
            put(
                "repeats",
                json.encodeToJsonElement(
                    database.messageRepeatDao().listAll()
                        .filter { it.messageID in survivingIds }.sortedBy { it.id.value }.map { it.toDto() },
                ),
            )
        }
        val pending = database.pendingSendDao().listByRadio(radioID) +
            database.pendingSendDao().listByRadio(foreignRadio)
        return rows to pending.filter { it.contactID != firstContact }.sortedBy { it.id.value }
    }

    suspend fun childRowCount(id: Uuid): Int {
        val ids = checkNotNull(messageIds[id]).toSet()
        val messages = database.messageDao().directMessageIdsForContact(id).size
        val reactions = database.reactionDao().listAll().count { it.contactID == id }
        val repeats = database.messageRepeatDao().listAll().count { it.messageID in ids }
        val pending = database.pendingSendDao().listByRadio(radioID).count { it.contactID == id || it.messageID in ids }
        return messages + reactions + repeats + pending
    }

    fun record(
        name: String,
        before: Pair<JsonObject, List<PendingSendEntity>>,
        after: Pair<JsonObject, List<PendingSendEntity>>,
        error: Throwable?,
    ) {
        val receipt = JSONObject().put("case", name).put("wholeSnapshotEqual", before == after)
            .put("exactInjectedException", failure != null && failure === error)
            .put("childRowsClearedAtFault", childRowsClearedAtFault)
            .put("earlierContactRemovedAtFault", earlierContactRemovedAtFault)
            .put("radioOrSessionConstructed", false)
        for (key in listOf("contacts", "messages", "reactions", "repeats")) {
            receipt.put("before$key", checkNotNull(before.first[key]).jsonArray.size)
                .put("after$key", checkNotNull(after.first[key]).jsonArray.size)
        }
        receipt.put("beforePending", before.second.size).put("afterPending", after.second.size)
        save(name, receipt)
    }

    fun close(name: String) {
        var closed = false
        try {
            database.close()
            closed = true
        } finally {
            val deleted = context.deleteDatabase(databaseName)
            save(
                "$name-teardown",
                JSONObject().put("databaseClosed", closed).put("ownedDatabaseRemoved", deleted)
                    .put("mainFileAbsent", !context.getDatabasePath(databaseName).exists())
                    .put("walAbsent", !File(context.getDatabasePath(databaseName).path + "-wal").exists())
                    .put("shmAbsent", !File(context.getDatabasePath(databaseName).path + "-shm").exists())
                    .put("radioOrSessionConstructed", false),
            )
        }
    }

    private fun save(name: String, receipt: JSONObject) {
        val directory = File(context.getExternalFilesDir(null), "contact-delete-atomicity")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$name.json").writeText(receipt.toString())
    }

    private suspend fun seedContact(id: Uuid, radio: Uuid, key: ByteArray) {
        store.saveContact(
            ContactDto(
                id = id,
                radioID = radio,
                publicKey = key,
                name = "Fabricated native contact",
                typeRawValue = ContactType.CHAT.raw,
                flags = FAVORITE_FLAG,
                outPathLength = 0,
                outPath = byteArrayOf(PATH_BYTE),
                lastAdvertTimestamp = EPOCH.toLong(),
                latitude = 0.0,
                longitude = 0.0,
                lastModified = EPOCH.toLong(),
                isBlocked = false,
                isMuted = true,
                isFavorite = true,
                isPinned = true,
                unreadCount = UNREAD_COUNT,
                nickname = "Fixture local name",
            ),
        )
        val ids = mutableListOf<Uuid>()
        for ((sequence, status) in MessageStatus.entries.withIndex()) {
            val row = message(radio, id, status)
            ids += row.id
            database.messageDao().upsert(row.toEntity())
            database.reactionDao().upsert(
                ReactionEntity(
                    id = Uuid.random(),
                    messageID = row.id,
                    emoji = "fixture",
                    senderName = "Fixture sender",
                    messageHash = "fixture hash",
                    rawText = "fixture reaction",
                    receivedAt = EPOCH,
                    radioID = radio,
                    contactID = id,
                ),
            )
            database.messageRepeatDao().upsert(
                MessageRepeatEntity(
                    id = Uuid.random(),
                    messageID = row.id,
                    receivedAt = EPOCH,
                    pathNodes = byteArrayOf(PATH_BYTE),
                ),
            )
            database.pendingSendDao().upsert(
                PendingSendEntity(
                    id = Uuid.random(),
                    radioID = radio,
                    messageID = row.id,
                    kindRawValue = DM_KIND,
                    isResend = true,
                    messageText = "Fixture pending",
                    messageTimestamp = EPOCH.toLong(),
                    sequence = sequence,
                    enqueuedAt = EPOCH,
                    contactID = id,
                    attemptCount = 0,
                ),
            )
        }
        messageIds[id] = ids
    }

    private fun message(radio: Uuid, contact: Uuid?, status: MessageStatus) = MessageDto(
        id = Uuid.random(),
        radioID = radio,
        text = "Fabricated native message",
        timestamp = EPOCH.toLong(),
        createdAt = EPOCH,
        direction = MessageDirection.OUTGOING,
        status = status,
        textType = TextType.PLAIN,
        pathLength = 0,
        isRead = false,
        heardRepeats = 0,
        sendCount = 1,
        retryAttempt = 0,
        maxRetryAttempts = 0,
        linkPreviewFetched = false,
        containsSelfMention = false,
        mentionSeen = false,
        timestampCorrected = false,
        contactID = contact,
        pathNodes = byteArrayOf(PATH_BYTE),
    )

    class FinalDeleteFailure : Exception("Native fixture final contact write failed")

    private class FinalDeleteFaultDao(
        private val delegate: ContactDao,
        private val beforeDelete: suspend (Uuid) -> Unit,
    ) : ContactDao by delegate {
        override suspend fun deleteById(id: Uuid) {
            beforeDelete(id)
            delegate.deleteById(id)
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
        const val EPOCH = 1_700_000_000.0
    }
}
