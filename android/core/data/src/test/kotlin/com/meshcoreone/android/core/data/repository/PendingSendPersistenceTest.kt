// PortedFrom: MC1Services/Tests/MC1ServicesTests/PendingSendPersistenceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.*
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PendingSendPersistenceTest : RepositoryTest() {
    @Test @OriginalCase("PendingSendPersistenceTests::PendingSendDTO round-trips through Model and back()")
    fun pendingDTOFieldsRoundTripThroughTheRealRepository() = runTest {
        val dto = pending(sequence = 1).copy(kind = PendingSendKind.CHANNEL, contactID = null, channelIndex = 3u,
            isResend = true, messageText = "test", messageTimestamp = 1_700_000_000u, localNodeName = "Alice")
        store.upsertPendingSend(dto)
        assertEquals(listOf(dto), store.fetchPendingSends(RADIO_A))
    }

    @Test @OriginalCase("PendingSendPersistenceTests::Pending sends are scoped by radioID()")
    fun equalRowIDsDoNotCrossRadios() = runTest {
        val a = pending(sequence = 1)
        val b = pending(RADIO_B, sequence = 1)
        store.upsertPendingSend(a)
        store.upsertPendingSend(b)
        assertEquals(RADIO_A, store.fetchPendingSends(RADIO_A).single().radioId)
        assertEquals(RADIO_B, store.fetchPendingSends(RADIO_B).single().radioId)
        store.upsertPendingSend(a.copy(radioId = RADIO_B))
        assertEquals(a.id, store.fetchPendingSends(RADIO_A).single().id)
        assertEquals(setOf(a.id, b.id), store.fetchPendingSends(RADIO_B).map { it.id }.toSet())
    }

    @Test @OriginalCase("PendingSendPersistenceTests::fetchPendingSends returns rows in sequence order()")
    fun pendingRowsAreSequenceOrdered() = runTest {
        for (sequence in listOf(3L, 1L, 2L)) store.upsertPendingSend(pending(sequence = sequence))
        assertEquals(listOf(1L, 2L, 3L), store.fetchPendingSends(RADIO_A).map { it.sequence })
    }

    @Test @OriginalCase("PendingSendPersistenceTests::deletePendingSend removes the row()")
    fun deletePendingRowCommitsAndIsRadioScoped() = runTest {
        val dto = pending(sequence = 1)
        store.upsertPendingSend(dto)
        store.upsertPendingSend(dto.copy(radioId = RADIO_B))
        store.deletePendingSend(entity(id = dto.id))
        assertTrue(store.fetchPendingSends(RADIO_A).isEmpty())
        assertEquals(dto.id, store.fetchPendingSends(RADIO_B).single().id)
    }

    @Test @OriginalCase("PendingSendPersistenceTests::insertPendingSendAssigningSequence returns 1 on an empty table()")
    fun firstAssignedSequenceIgnoresTheDTOSequence() = runTest {
        val dto = pending(sequence = 0)
        assertEquals(1L, store.insertPendingSendAssigningSequence(dto))
        assertEquals(1L, store.fetchPendingSends(RADIO_A).single().sequence)
        assertEquals(1L, store.insertPendingSendAssigningSequence(pending(RADIO_B, sequence = 99)))
        assertEquals(1L, store.fetchPendingSends(RADIO_B).single().sequence)
    }

    @Test @OriginalCase("PendingSendPersistenceTests::insertPendingSendAssigningSequence assigns per-radio monotonic sequences()")
    fun assignedSequencesArePerRadio() = runTest {
        assertEquals(1L, store.insertPendingSendAssigningSequence(pending()))
        assertEquals(2L, store.insertPendingSendAssigningSequence(pending()))
        assertEquals(1L, store.insertPendingSendAssigningSequence(pending(RADIO_B)))
    }

    @Test @OriginalCase("PendingSendPersistenceTests::Concurrent inserts on the same radio produce distinct sequences()")
    fun concurrentEnqueuesCannotReuseTheSameSequence() = runTest {
        val assigned = listOf(async { store.insertPendingSendAssigningSequence(pending()) },
            async { store.insertPendingSendAssigningSequence(pending()) }).awaitAll().sorted()
        assertEquals(listOf(1L, 2L), assigned)
        assertEquals(listOf(1L, 2L), store.fetchPendingSends(RADIO_A).map { it.sequence })
    }

    @Test @OriginalCase("PendingSendPersistenceTests::attemptCount round-trips through insertPendingSendAssigningSequence and fetchPendingSends()")
    fun nilZeroAndPositiveAttemptsRemainDifferent() = runTest {
        val dtos = listOf(null, 0L, 4L).map { pending(attempt = it) }
        for (dto in dtos) store.insertPendingSendAssigningSequence(dto)
        val rows = store.fetchPendingSends(RADIO_A)
        assertEquals(listOf(null, 0L, 4L), rows.map { it.attemptCount })
        for (dto in dtos) {
            assertEquals(dto.attemptCount, assertNotNull(rows.singleOrNull { it.messageID == dto.messageID }).attemptCount)
        }
    }

    @Test @OriginalCase("PendingSendPersistenceTests::deletePendingSendsForMessage removes every row matching the messageID()", "radio-key-adaptation")
    fun deletingMessagePendingRowsRetainsTheOtherRadio() = runTest {
        val id = UUID.randomUUID()
        store.upsertPendingSend(pending(messageID = id, sequence = 1))
        store.upsertPendingSend(pending(messageID = id, sequence = 1))
        store.upsertPendingSend(pending(RADIO_B, id, sequence = 1))
        val unrelated = pending(sequence = 2)
        store.upsertPendingSend(unrelated)
        store.deletePendingSendsForMessage(entity(id = id))
        assertEquals(listOf(unrelated), store.fetchPendingSends(RADIO_A))
        assertEquals(id, store.fetchPendingSends(RADIO_B).single().messageID)
    }

    @Test @OriginalCase("PendingSendPersistenceTests::deletePendingSendsForMessage is a no-op when no rows match()")
    fun absentMessageDeleteDoesNotTouchOtherRows() = runTest {
        val kept = pending(sequence = 1)
        store.upsertPendingSend(kept)
        store.deletePendingSendsForMessage(entity(id = UUID.randomUUID()))
        assertEquals(listOf(kept), store.fetchPendingSends(RADIO_A))
    }

    @Test @OriginalCase("PendingSendPersistenceTests::fetchPendingSends skips rows whose kindRawValue is unknown()")
    fun unknownFutureKindIsReportedAndPreservedOnDisk() = runTest {
        val kept = pending(sequence = 1)
        store.upsertPendingSend(kept)
        val unknown = pending(sequence = 2).toEntity().copy(kindRawValue = 99, contactID = null, attemptCount = null)
        db.pendingSends().insert(unknown)
        assertEquals(listOf(kept), store.fetchPendingSends(RADIO_A))
        assertEquals(99L, db.pendingSends().forRadio(RADIO_A.value).last().kindRawValue)
        assertEquals(1, issues.size)
    }

    @Test @OriginalCase("PendingSendPersistenceTests::replacePendingSendForRetry flips status and inserts a new PendingSend row()")
    fun retryReplacementCommitsStatusAndAuthoritativeEnvelopeTogether() = runTest {
        val m = message(text = "retry me", status = MessageStatus.FAILED).copy(isRead = true)
        store.saveMessage(m)
        store.upsertPendingSend(pending(messageID = m.id, sequence = 1))
        val replacement = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(m.id, CONTACT_A), RADIO_A, enqueuedAt = AT)
        val assigned = store.replacePendingSendForRetry(m.id, replacement)
        assertEquals(1L, assigned)
        assertEquals(MessageStatus.PENDING, assertNotNull(store.fetchMessage(entity(id = m.id))).status)
        assertEquals(listOf(replacement.copy(sequence = assigned)), store.fetchPendingSends(RADIO_A))
    }

    @Test @OriginalCase("PendingSendPersistenceTests::replacePendingSendForRetry preserves .delivered status()")
    fun lateDeliveryWinsOverManualRetry() = runTest {
        val m = message(text = "retry me", status = MessageStatus.DELIVERED).copy(isRead = true)
        store.saveMessage(m)
        store.replacePendingSendForRetry(m.id,
            PendingSendDTO.fromEnvelope(DirectMessageEnvelope(m.id, CONTACT_A), RADIO_A, enqueuedAt = AT))
        assertEquals(MessageStatus.DELIVERED, assertNotNull(store.fetchMessage(entity(id = m.id))).status)
        assertEquals(1, store.fetchPendingSends(RADIO_A).size)
    }

    @Test @OriginalCase("PendingSendPersistenceTests::replacePendingSendForRetry removes every existing PendingSend row for the messageID()")
    fun replacementReapsAllPriorRowsAndRetainsUnrelatedSequence() = runTest {
        val m = message(text = "retry me", status = MessageStatus.FAILED).copy(isRead = true)
        store.saveMessage(m)
        store.upsertPendingSend(pending(messageID = m.id, sequence = 1))
        store.upsertPendingSend(pending(messageID = m.id, sequence = 2))
        val unrelated = pending(sequence = 3)
        store.upsertPendingSend(unrelated)
        val replacement = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(m.id, CONTACT_A), RADIO_A, enqueuedAt = AT)
        assertEquals(4L, store.replacePendingSendForRetry(m.id, replacement))
        assertEquals(listOf(unrelated, replacement.copy(sequence = 4)), store.fetchPendingSends(RADIO_A))
    }

    @Test @OriginalCase("PendingSendPersistenceTests::replacePendingSendForRetry assigns the next per-radio sequence()")
    fun otherRadioSequenceCannotAdvanceThisRadio() = runTest {
        val m = message(text = "retry me", status = MessageStatus.FAILED).copy(isRead = true)
        store.saveMessage(m)
        store.upsertPendingSend(pending(sequence = 7))
        store.upsertPendingSend(pending(RADIO_B, sequence = 99))
        val replacement = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(m.id, CONTACT_A), RADIO_A, enqueuedAt = AT)
        assertEquals(8L, store.replacePendingSendForRetry(m.id, replacement))
        val rows = store.fetchPendingSends(RADIO_A)
        val inserted = assertNotNull(rows.firstOrNull { it.messageID == m.id })
        assertEquals(8L, inserted.sequence)
    }
}
