package one.meshcore.shared.persistence

import kotlinx.serialization.json.Json
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.PendingSendDto
import one.meshcore.shared.chat.PendingSendKind
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.simulator.MockDataProvider
import org.junit.Assert.assertEquals

@Suppress("MagicNumber")
internal object RetryProcessRows {
    val radioA = uid(1)
    val radioB = uid(2)
    val messageID = uid(10)
    val oldID = uid(20)
    val replacementID = uid(21)
    const val NEXT_SEQUENCE = 6
    private const val TIME = 1_700_000_000L
    val message = MessageDto(
        id = messageID, radioID = radioA, text = "Synthetic retry Δ", timestamp = TIME,
        createdAt = TIME.toDouble(), sortDateRaw = TIME.toDouble(),
        direction = MessageDirection.OUTGOING, status = MessageStatus.FAILED, textType = TextType.PLAIN,
        pathLength = 2, isRead = true, heardRepeats = 3, sendCount = 2, retryAttempt = 2, maxRetryAttempts = 3,
        linkPreviewFetched = false, containsSelfMention = false, mentionSeen = true, failureSeen = true,
        timestampCorrected = true, contactID = uid(30), senderNodeName = "Synthetic sender",
        pathNodes = byteArrayOf(1, 2), senderKeyPrefix = byteArrayOf(3, 4), deduplicationKey = "synthetic-retry",
    )
    private val parked = row(oldID, radioA, messageID, 1, attempts = 4)
    private val sameRadio = row(uid(22), radioA, uid(11), 5, attempts = 2)
    private val otherRadio = row(uid(23), radioB, uid(12), 40, attempts = 3)
    val replacement = row(replacementID, radioA, messageID, 0, attempts = 0)
    val oldRows = listOf(parked, sameRadio, otherRadio)

    suspend fun seed(db: MeshDatabase) {
        for (radio in listOf(radioA, radioB)) {
            db.deviceDao().upsert(MockDataProvider.simulatorDevice(TIME.toDouble()).copy(id = radio, radioID = radio))
        }
        db.messageDao().upsert(message.toEntity())
        val pending = RoomPendingSendStore(db.pendingSendDao())
        for (row in oldRows) pending.upsertPendingSend(row)
        verify(db, committed = false)
    }

    suspend fun verify(db: MeshDatabase, committed: Boolean) {
        val expectedMessage = if (committed) message.copy(status = MessageStatus.PENDING) else message
        val actualMessage = requireNotNull(db.messageDao().getById(messageID)).toDto()
        assertEquals(
            Json.encodeToString(MessageDto.serializer(), expectedMessage),
            Json.encodeToString(MessageDto.serializer(), actualMessage),
        )
        val rows = if (committed) listOf(sameRadio, replacement.copy(sequence = NEXT_SEQUENCE), otherRadio) else oldRows
        val pending = RoomPendingSendStore(db.pendingSendDao())
        for (radio in listOf(radioA, radioB)) {
            assertEquals(rows.filter { it.radioID == radio }, pending.fetchPendingSends(radio))
            assertEquals(radio, db.deviceDao().listByRadio(radio).single().radioID)
        }
        assertEquals(
            listOf(if (committed) replacement.copy(sequence = NEXT_SEQUENCE) else parked),
            pending.fetchPendingSendsForMessage(messageID),
        )
    }

    private fun row(id: Uuid, radio: Uuid, message: Uuid, sequence: Int, attempts: Int) = PendingSendDto(
        id = id, radioID = radio, messageID = message, kind = PendingSendKind.DM,
        sequence = sequence, enqueuedAtEpochSeconds = TIME.toDouble(), contactID = uid(30),
        isResend = true, attemptCount = attempts,
    )

    private fun uid(number: Int): Uuid =
        Uuid.normalized("B0000000-0000-0000-0000-" + number.toString().padStart(12, '0'))
}
