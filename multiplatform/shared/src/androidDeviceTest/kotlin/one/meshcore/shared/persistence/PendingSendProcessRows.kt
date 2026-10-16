package one.meshcore.shared.persistence

import one.meshcore.shared.chat.PendingSendDto
import one.meshcore.shared.chat.PendingSendKind
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.simulator.MockDataProvider
import org.junit.Assert.assertEquals

@Suppress("MagicNumber")
internal object PendingSendProcessRows {
    val radioA = uid(1)
    val radioB = uid(2)
    val channelID = uid(12)
    private const val TIME = 1_700_000_000L
    private const val DRAINED_ATTEMPTS = 5

    val expected = listOf(
        row(11, radioA, 1, null),
        row(12, radioA, 2, 0, channel = true),
        row(13, radioA, 3, DRAINED_ATTEMPTS),
        row(14, radioB, 1, 0),
    )

    suspend fun seed(db: MeshDatabase, store: RoomPendingSendStore) {
        for (radio in listOf(radioA, radioB)) {
            db.deviceDao().upsert(
                MockDataProvider.simulatorDevice(TIME.toDouble()).copy(id = radio, radioID = radio),
            )
        }
        for (dto in expected) {
            val initial = if (dto.id == uid(13)) dto.copy(attemptCount = DRAINED_ATTEMPTS - 1) else dto
            assertEquals(dto.sequence, store.insertPendingSendAssigningSequence(initial.copy(sequence = 0)))
        }
        assertEquals(DRAINED_ATTEMPTS, store.incrementPendingSendAttemptCount(uid(113)))
    }

    suspend fun verify(db: MeshDatabase, store: RoomPendingSendStore, deleted: Boolean) {
        val rows = expected.filterNot { deleted && it.id == channelID }
        for (radio in listOf(radioA, radioB)) {
            assertEquals(rows.filter { it.radioID == radio }, store.fetchPendingSends(radio))
            assertEquals(radio, db.deviceDao().listByRadio(radio).single().radioID)
        }
        assertEquals(emptyList<PendingSendDto>(), store.fetchPendingSends(uid(3)))
    }

    private fun row(number: Int, radio: Uuid, sequence: Int, attempts: Int?, channel: Boolean = false) = PendingSendDto(
        id = uid(number),
        radioID = radio,
        messageID = uid(number + 100),
        kind = if (channel) PendingSendKind.CHANNEL else PendingSendKind.DM,
        sequence = sequence,
        enqueuedAtEpochSeconds = TIME.toDouble() + number,
        contactID = if (channel) null else uid(number + 200),
        channelIndex = if (channel) 3 else null,
        isResend = channel,
        messageText = if (channel) "Synthetic pending channel Δ" else "",
        messageTimestamp = if (channel) TIME else 0,
        localNodeName = if (channel) "Synthetic sender" else null,
        attemptCount = attempts,
    )

    private fun uid(number: Int): Uuid =
        Uuid.normalized("A0000000-0000-0000-0000-" + number.toString().padStart(12, '0'))
}
