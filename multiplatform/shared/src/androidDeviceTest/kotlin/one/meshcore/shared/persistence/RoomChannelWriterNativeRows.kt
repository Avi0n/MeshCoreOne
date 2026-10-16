package one.meshcore.shared.persistence

import androidx.test.platform.app.InstrumentationRegistry
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.PendingSendKind
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import org.json.JSONObject
import org.junit.Assert.assertEquals
import java.io.File

internal class RoomChannelWriterNativeRows(queries: kotlinx.coroutines.CoroutineDispatcher) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "channel-writer-${Uuid.random().value}.db"
    private val file = context.getDatabasePath(name)
    val database = meshDatabase(context, queries, name)
    private val channels = mutableListOf<ChannelEntity>()
    private val messages = mutableListOf<MessageEntity>()
    private val reactions = mutableListOf<ReactionEntity>()
    private val repeats = mutableListOf<MessageRepeatEntity>()
    private val pending = mutableListOf<PendingSendEntity>()
    val radio = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
    val foreign = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")
    val replacement = ChannelInfo(TARGET, "Replacement frame", ByteArray(KEY_SIZE) { NEW_KEY })
    val targetID get() = channels.single { it.radioID == radio && it.index == TARGET }.id

    suspend fun seed() {
        for (index in listOf(TARGET, CONTRAST, VACATED, PRUNED)) seedSlot(radio, index)
        seedSlot(foreign, TARGET)
        assertEquals(expected(null), snapshot())
        assertEquals(CHILDREN_PER_SLOT, children(radio, TARGET))
    }

    private suspend fun seedSlot(owner: Uuid, index: Int) {
        val row = ChannelEntity(
            id = Uuid.random(), radioID = owner, index = index, name = "Seed $index",
            secret = ByteArray(KEY_SIZE) { OLD_KEY }, isEnabled = false,
            unreadCount = UNREAD, unreadMentionCount = MENTIONS,
            notificationLevelRawValue = NotificationLevel.MENTIONS_ONLY.raw, isPinned = true,
            floodScopeModeRawValue = ChannelFloodScopeStorage.MODE_SPECIFIC,
            regionScope = "Synthetic region", lastMessageDate = NOW,
        )
        channels += row
        database.channelDao().upsert(row)
        val message = MessageDto(
            id = Uuid.random(),
            text = "Synthetic history",
            timestamp = NOW.toLong(),
            createdAt = NOW,
            radioID = owner, textType = TextType.PLAIN, pathLength = 0, isRead = false,
            heardRepeats = 0, sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0,
            linkPreviewFetched = false, containsSelfMention = false, mentionSeen = false,
            timestampCorrected = false, contactID = null,
            channelIndex = index,
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.PENDING,
        ).copy(pathNodes = byteArrayOf(2, 3), senderKeyPrefix = byteArrayOf(4, 5)).toEntity()
        messages += message
        database.messageDao().upsert(message)
        val reaction = ReactionEntity(
            id = Uuid.random(), messageID = message.id, emoji = "fixture", senderName = "Fixture",
            messageHash = "hash", rawText = "reaction", receivedAt = NOW, radioID = owner, channelIndex = index,
        )
        reactions += reaction
        database.reactionDao().upsert(reaction)
        val repeat = MessageRepeatEntity(
            id = Uuid.random(),
            messageID = message.id,
            receivedAt = NOW,
            pathNodes = byteArrayOf(6, 7),
            pathLength = 2,
            snr = 5.5,
            rssi = -100,
        )
        repeats += repeat
        database.messageRepeatDao().upsert(repeat)
        val send = PendingSendEntity(
            id = Uuid.random(), radioID = owner, messageID = message.id, kindRawValue = PendingSendKind.CHANNEL.raw,
            isResend = true, messageText = message.text, messageTimestamp = message.timestamp,
            sequence = index, enqueuedAt = NOW, channelIndex = index, localNodeName = "Fixture", attemptCount = 2,
        )
        pending += send
        database.pendingSendDao().upsert(send)
    }

    suspend fun snapshot(): List<Any> = normalized(
        database.channelDao().listAll(),
        database.messageDao().listAll(),
        database.reactionDao().listAll(),
        database.messageRepeatDao().listAll(),
        database.pendingSendDao().listByRadio(radio) + database.pendingSendDao().listByRadio(foreign),
    )

    fun expected(kind: RoomChannelWriterNativeCase?): List<Any> {
        val removed = when (kind) {
            RoomChannelWriterNativeCase.BATCH -> setOf(TARGET, VACATED, PRUNED)
            null -> emptySet()
            else -> setOf(TARGET)
        }
        val removedIDs = messages.filter { it.radioID == radio && it.channelIndex in removed }.map { it.id }.toSet()
        val expectedChannels = channels.mapNotNull { row ->
            if (row.radioID != radio) return@mapNotNull row
            when {
                kind == RoomChannelWriterNativeCase.DELETE && row.index == TARGET -> null
                kind == RoomChannelWriterNativeCase.BATCH && row.index in setOf(VACATED, PRUNED) -> null
                kind in setOf(
                    RoomChannelWriterNativeCase.SAVE,
                    RoomChannelWriterNativeCase.BATCH,
                ) &&
                    row.index == TARGET -> row.copy(
                    name = replacement.name,
                    secret = replacement.secret,
                    unreadCount = 0,
                    unreadMentionCount = 0,
                    lastMessageDate = null,
                )
                else -> row
            }
        }
        return normalized(
            expectedChannels,
            messages.filter {
                it.id !in removedIDs
            },
            reactions.filter { it.messageID !in removedIDs },
            repeats.filter { it.messageID !in removedIDs },
            pending.filter { it.messageID !in removedIDs },
        )
    }

    suspend fun children(owner: Uuid, index: Int): Int {
        val ids = messages.filter { it.radioID == owner && it.channelIndex == index }.map { it.id }.toSet()
        return database.messageDao().listAll().count { it.id in ids } +
            database.reactionDao().listAll().count { it.messageID in ids } +
            database.messageRepeatDao().listAll().count { it.messageID in ids } +
            database.pendingSendDao().listByRadio(owner).count { it.messageID in ids }
    }

    private fun normalized(
        channelRows: List<ChannelEntity>,
        messageRows: List<MessageEntity>,
        reactionRows: List<ReactionEntity>,
        repeatRows: List<MessageRepeatEntity>,
        pendingRows: List<PendingSendEntity>,
    ): List<Any> = listOf(
        channelRows.sortedBy { it.id.value }.map { it.copy(secret = EMPTY) to it.secret.toList() },
        messageRows.sortedBy { it.id.value }.map {
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
        reactionRows.sortedBy { it.id.value },
        repeatRows.sortedBy { it.id.value }.map { it.copy(pathNodes = EMPTY) to it.pathNodes.toList() },
        pendingRows.sortedBy { it.id.value },
    )

    fun record(name: String, receipt: JSONObject) {
        val directory = File(context.getExternalFilesDir(null), "channel-writer")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$name.json").writeText(receipt.toString())
    }

    fun close(caseName: String, workersSettled: Boolean, scopeStopped: Boolean, executorStopped: Boolean) {
        var closed = false
        try {
            database.close()
            closed = true
        } finally {
            val owned = listOf("", "-wal", "-shm").map { File(file.absolutePath + it) }
            val removed = owned.map { runCatching { !it.exists() || it.delete() }.getOrDefault(false) }
            record(
                "$caseName-teardown",
                JSONObject().put("databaseClosed", closed).put("mainFileAbsent", !owned[0].exists())
                    .put("walAbsent", !owned[1].exists()).put("shmAbsent", !owned[2].exists())
                    .put("allOwnedFilesRemoved", removed.all { it }).put("workersSettled", workersSettled)
                    .put("scopeStopped", scopeStopped).put("executorStopped", executorStopped)
                    .put("radioOrSessionConstructed", false),
            )
            check(removed.all { it } && owned.none { it.exists() })
        }
    }

    companion object {
        const val TARGET = 1
        const val CONTRAST = 2
        const val VACATED = 3
        const val PRUNED = 10
        const val CAP = 8
        private const val KEY_SIZE = 16
        private const val OLD_KEY: Byte = 0x31
        private const val NEW_KEY: Byte = 0x62
        private const val UNREAD = 4
        private const val MENTIONS = 2
        private const val NOW = 1_700_000_000.0
        private const val CHILDREN_PER_SLOT = 4
        private val EMPTY = ByteArray(0)
    }
}
