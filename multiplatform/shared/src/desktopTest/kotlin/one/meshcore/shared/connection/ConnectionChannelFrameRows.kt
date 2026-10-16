package one.meshcore.shared.connection

import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ChannelEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.MessageEntity
import one.meshcore.shared.persistence.MessageRepeatEntity
import one.meshcore.shared.persistence.ReactionEntity
import one.meshcore.shared.persistence.toEntity
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ConnectionChannelFrameRows(private val db: MeshDatabase) {
    private val seededIds = mutableMapOf<Pair<Uuid, Int>, Set<Uuid>>()
    private val radios = mutableSetOf<Uuid>()
    private val common = ConnectionInitialSyncFlagRows(db)

    suspend fun seed(radio: Uuid, info: ChannelInfo, initial: Boolean = false): ChannelEntity {
        radios += radio
        val current = db.channelDao().getByRadioAndIndex(radio, info.index)
        if (!initial) {
            val read = requireNotNull(current)
            assertEquals(channel(defaultRow(read.id, radio, info)), channel(read))
        } else {
            assertEquals(null, current)
        }
        val expected = defaultRow(current?.id ?: Uuid.random(), radio, info).copy(
            name = if (initial) "Cached channel" else info.name,
            isEnabled = false,
            unreadCount = UNREAD,
            unreadMentionCount = MENTIONS,
            notificationLevelRawValue = NotificationLevel.MENTIONS_ONLY.raw,
            isPinned = true,
            floodScopeModeRawValue = ChannelFloodScopeStorage.MODE_SPECIFIC,
            regionScope = "Synthetic region",
            lastMessageDate = NOW,
        )
        db.channelDao().upsert(expected)
        val id = Uuid.random()
        val message = ChatFixtures.message(
            id = id,
            text = "Incoming-only channel history",
            timestamp = NOW.toLong(),
            createdAt = NOW,
            direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED,
            contactID = null,
            channelIndex = info.index,
        ).copy(
            radioID = radio,
            pathNodes = byteArrayOf(2, 3),
            senderKeyPrefix = byteArrayOf(7, 8),
            containsSelfMention = true,
        ).toEntity()
        db.messageDao().upsert(message)
        db.reactionDao().upsert(
            ReactionEntity(
                id = Uuid.random(), messageID = id, emoji = "fixture", senderName = "Fixture peer",
                messageHash = "fixture hash", rawText = "fixture reaction", receivedAt = NOW,
                radioID = radio, channelIndex = info.index,
            ),
        )
        db.messageRepeatDao().upsert(
            MessageRepeatEntity(
                id = Uuid.random(),
                messageID = id,
                receivedAt = NOW,
                pathNodes = byteArrayOf(9, 10),
                pathLength = 2,
                snr = 5.5,
                rssi = -100,
            ),
        )
        seededIds[radio to info.index] = setOf(id)
        assertEquals(channel(expected), channel(requireNotNull(db.channelDao().getByRadioAndIndex(radio, info.index))))
        assertPendingEmpty()
        return expected
    }

    suspend fun slot(radio: Uuid, index: Int): List<Any?> {
        val messages = db.messageDao().listByRadio(radio).filter { it.channelIndex == index }.sortedBy { it.id.value }
        val ids = messages.map { it.id }.toSet() + seededIds[radio to index].orEmpty()
        return listOf(
            db.channelDao().getByRadioAndIndex(radio, index)?.let(::channel),
            messages.map(::message),
            db.reactionDao().listByRadio(radio).filter { it.channelIndex == index }.sortedBy { it.id.value },
            db.messageRepeatDao().listAll().filter { it.messageID in ids }.sortedBy { it.id.value }.map {
                it.copy(pathNodes = EMPTY) to
                    it.pathNodes.toList()
            },
            db.pendingSendDao().listByRadio(radio).filter { it.channelIndex == index }.sortedBy { it.id.value },
        )
    }

    suspend fun globals() = listOf(
        common.snapshot().take(3),
        db.discoveredNodeDao().listAll().sortedBy { it.id.value }.map {
            it.copy(publicKey = EMPTY) to
                it.publicKey.toList()
        },
        db.channelDao().listAll().filter {
            it.index !in listOf(TARGET, CONTRAST)
        }.sortedBy { it.id.value }.map(::channel),
    )

    suspend fun all() = listOf(
        common.snapshot(),
        db.messageDao().listAll().sortedBy { it.id.value }.map(::message),
        db.reactionDao().listAll().sortedBy { it.id.value },
        db.messageRepeatDao().listAll().sortedBy { it.id.value }.map {
            it.copy(pathNodes = EMPTY) to
                it.pathNodes.toList()
        },
        radios.sortedBy { it.value }.map { db.pendingSendDao().listByRadio(it).sortedBy { row -> row.id.value } },
    )

    suspend fun assertPendingEmpty() {
        radios.forEach { assertTrue(db.pendingSendDao().listByRadio(it).isEmpty()) }
    }

    fun channel(row: ChannelEntity): List<Any> = listOf(row.copy(secret = EMPTY), row.secret.toList())
    private fun message(row: MessageEntity): List<Any?> = listOf(
        row.copy(pathNodes = null, senderKeyPrefix = null, linkPreviewImageData = null, linkPreviewIconData = null),
        row.pathNodes?.toList(),
        row.senderKeyPrefix?.toList(),
        row.linkPreviewImageData?.toList(),
        row.linkPreviewIconData?.toList(),
    )

    private fun defaultRow(id: Uuid, radio: Uuid, info: ChannelInfo) = ChannelEntity(
        id = id,
        radioID = radio,
        index = info.index,
        name = info.name,
        secret = info.secret,
        isEnabled = true,
        unreadCount = 0,
    )

    companion object {
        const val TARGET = 1
        const val CONTRAST = 2
        const val NOW = 1_700_000_000.0
        private const val UNREAD = 1
        private const val MENTIONS = 1
        private val EMPTY = ByteArray(0)
    }
}
