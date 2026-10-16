package one.meshcore.shared.connection

import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.contact.VContactIdentity
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ContactEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.toEntity
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ConnectionInitialContactReturnRows(private val database: MeshDatabase) {
    private val seededMessages = mutableMapOf<Uuid, MutableSet<Uuid>>()

    suspend fun enrich(radio: Uuid, selfKey: ByteArray) {
        val contacts = database.contactDao().listByRadio(radio)
        assertTrue(contacts.isNotEmpty())
        for (row in contacts) {
            assertFalse(VContactIdentity.isVContact(row.publicKey, selfKey))
            database.contactDao().upsert(
                row.copy(
                    flags = row.flags or FAVORITE,
                    isFavorite = true,
                    isMuted = true,
                    isPinned = true,
                    unreadCount = UNREAD,
                    unreadMentionCount = MENTIONS,
                    nickname = "Local nickname",
                    lastMessageDate = NOW,
                    lastHeardTimestamp = NOW.toLong(),
                    ocvPreset = "fixture curve",
                    customOCVArrayString = "[3.4,3.8,4.2]",
                    avatarImageData = byteArrayOf(3, 4),
                ),
            )
            val id = Uuid.random()
            seededMessages.getOrPut(radio) { mutableSetOf() }.add(id)
            database.messageDao().upsert(
                ChatFixtures.message(
                    id = id,
                    contactID = row.id,
                    text = "Incoming-only fixture history",
                    direction = MessageDirection.INCOMING,
                    status = MessageStatus.SENT,
                ).copy(radioID = radio, pathNodes = byteArrayOf(7, 8)).toEntity(),
            )
            database.reactionDao().upsert(
                one.meshcore.shared.persistence.ReactionEntity(
                    id = Uuid.random(), messageID = id, emoji = "fixture", senderName = "Fixture peer",
                    messageHash = "fixture hash", rawText = "fixture reaction", receivedAt = NOW,
                    radioID = radio, contactID = row.id,
                ),
            )
            database.messageRepeatDao().upsert(
                one.meshcore.shared.persistence.MessageRepeatEntity(
                    id = Uuid.random(),
                    messageID = id,
                    receivedAt = NOW,
                    pathNodes = byteArrayOf(9),
                ),
            )
        }
    }

    suspend fun snapshot(radio: Uuid): List<Any> {
        val messages = database.messageDao().listByRadio(radio).sortedBy { it.id.value }
        val ids = messages.map { it.id }.toSet() + seededMessages[radio].orEmpty()
        return listOf(
            database.contactDao().listByRadio(radio).sortedBy { it.id.value }.map {
                contactSnapshot(it)
            },
            messages.map {
                listOf(
                    it.copy(
                        pathNodes = null,
                        senderKeyPrefix = null,
                        linkPreviewImageData = null,
                        linkPreviewIconData = null,
                    ),
                    it.pathNodes?.toList(),
                    it.senderKeyPrefix?.toList(),
                    it.linkPreviewImageData?.toList(),
                    it.linkPreviewIconData?.toList(),
                )
            },
            database.reactionDao().listByRadio(radio).sortedBy { it.id.value },
            database.messageRepeatDao().listAll().filter { it.messageID in ids }.sortedBy { it.id.value }.map {
                it.copy(pathNodes = EMPTY) to it.pathNodes.toList()
            },
            database.pendingSendDao().listByRadio(radio),
        )
    }

    fun contactSnapshot(row: ContactEntity): List<Any?> = listOf(
        row.copy(
            publicKey = EMPTY,
            outPath = EMPTY,
            avatarImageData = null,
        ),
        row.publicKey.toList(),
        row.outPath.toList(),
        row.avatarImageData?.toList(),
    )

    companion object {
        val KEY_P = ByteArray(32) { 51 }
        val KEY_N = ByteArray(32) { 68 }
        val KEY_B = ByteArray(32) { 85 }
        const val OLD_TIME = 1_699_999_000L
        const val NEW_TIME = 1_699_999_500L
        private const val NOW = 1_700_000_000.0
        private const val FAVORITE = 1
        private const val UNREAD = 7
        private const val MENTIONS = 2
        private val EMPTY = ByteArray(0)

        fun frame(key: ByteArray, fresh: Boolean) = MeshContact(
            id = if (fresh) "new" else "old", publicKey = key.copyOf(), typeRawValue = if (fresh) 2 else 1,
            flags = if (fresh) 4 else 2, outPathLength = if (fresh) 2 else 1,
            outPath = if (fresh) byteArrayOf(5, 6) else byteArrayOf(1),
            advertisedName = if (fresh) "Fresh radio name" else "Old radio name",
            lastAdvertisement = if (fresh) NEW_TIME else OLD_TIME,
            latitude = if (fresh) 51.5 else 50.0, longitude = if (fresh) -0.1 else -1.0,
            lastModified = if (fresh) NEW_TIME else OLD_TIME,
        )
    }
}
