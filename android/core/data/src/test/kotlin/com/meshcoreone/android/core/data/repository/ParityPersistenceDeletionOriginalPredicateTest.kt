// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ParsedRxLogData
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ParityPersistenceDeletionOriginalPredicateTest : RepositoryTest() {
    private fun nowTimestamp(): UInt = Instant.now().epochSecond.toUInt()

    private suspend fun radio(): RadioId {
        val id = UUID.randomUUID()
        val dto = DeviceDTO(
            id = id, radioId = RadioId(id), publicKey = Bytes(ByteArray(32) { 0x44 }),
            nodeName = "TestDevice", firmwareVersion = 8u, firmwareVersionString = "v1.11.0",
            manufacturerName = "TestMfg", buildDate = "01 Jan 2025",
            latitude = 37.7749, longitude = -122.4194, multiAcks = 0u, isActive = false,
        )
        store.saveDevice(dto)
        return dto.radioId
    }

    private fun originalFrame(byte: Int, name: String) = ContactFrame(
        Bytes(ByteArray(32) { byte.toByte() }), ContactType.CHAT, 0u, 2u, Bytes.of(1, 2),
        name, nowTimestamp(), 37.7749, -122.4194, nowTimestamp(),
    )

    private fun literalFrame(byte: Int, name: String) = ContactFrame(
        Bytes(ByteArray(32) { byte.toByte() }), ContactType.CHAT, 0u, 0u, Bytes.EMPTY,
        name, 1u, 0.0, 0.0, 1u,
    )

    private fun originalMessage(
        radio: RadioId, contact: UUID? = null, channel: UByte? = null, text: String,
        timestamp: UInt = nowTimestamp(), sender: String? = null,
    ) = MessageDTO(
        id = UUID.randomUUID(), radioId = radio, contactID = contact, channelIndex = channel,
        text = text, timestamp = timestamp, createdAt = Instant.now(),
        direction = MessageDirection.OUTGOING, status = MessageStatus.PENDING,
        senderNodeName = sender,
    )

    private fun originalPending(radio: RadioId, messageID: UUID, sequence: Long) = PendingSendDTO(
        UUID.randomUUID(), radio, messageID, PendingSendKind.DM, UUID.randomUUID(), null,
        false, "", 0u, null, sequence, Instant.now(), 0,
    )

    private suspend fun originalRepeat(radio: RadioId, messageID: UUID) =
        store.saveMessageRepeat(radio, MessageRepeatDTO(
            messageID = messageID, receivedAt = Instant.now(), pathNodes = Bytes.of(0x31),
            pathLength = 0u, snr = 8.5, rssi = -90, rxLogEntryID = null,
        ))

    private suspend fun originalReaction(
        radio: RadioId, messageID: UUID, sender: String, hash: String, contact: UUID? = null,
    ) = store.saveReaction(ReactionDTO(
        messageID = messageID, emoji = "\uD83D\uDC4D", senderName = sender, messageHash = hash,
        rawText = "\uD83D\uDC4D", contactID = contact, radioId = radio,
    ))

    private fun originalRx(radio: RadioId) = RxLogEntryDTO.fromParsed(
        radio, ParsedRxLogData(
            snr = 10.5, rssi = -65, rawPayload = Bytes.of(0x15, 1, 2, 3),
            routeType = RouteType.FLOOD, payloadType = PayloadType.GROUP_TEXT,
            payloadVersion = 0u, payloadTypeBits = 5u, transportCode = null,
            pathLength = 1u, pathNodes = listOf(0x42u), packetPayload = Bytes.of(0xAB, 0xCD, 0xEF),
        ),
        receivedAt = Instant.now(), channelIndex = 1u, channelName = "TestChannel",
        decryptStatus = DecryptStatus.SUCCESS, senderTimestamp = 42u,
        regionScope = null, regionScopeMatches = SnapshotList.empty(), decodedText = "Hello mesh!",
    )

    private suspend fun rejectAfterDelete(table: String, action: suspend () -> Unit) {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_original_delete AFTER DELETE ON $table BEGIN SELECT RAISE(FAIL, 'test'); END",
        )
        try {
            assertFailsWith<PersistenceStoreException> { action() }
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_original_delete")
        }
    }

    @OriginalCase("PersistenceStoreTests::deleteContact cascades messages, reactions, repeats, and pending sends()")
    @Test fun originalDoomedAndSurvivorRetainSingleMessageReactionRepeatPendingCascadeAndAllSevenPostconditions() = runTest {
        val radio = radio()
        val doomed = store.saveContact(radio, originalFrame(0x44, "Doomed")).id
        val survivor = store.saveContact(radio, originalFrame(0x55, "Survivor")).id
        val message = originalMessage(radio, contact = doomed, text = "cascade me")
        store.saveMessage(message)
        store.upsertPendingSend(originalPending(radio, message.id, 1))
        originalReaction(radio, message.id, "Doomed", "hash", doomed)
        originalRepeat(radio, message.id)
        store.saveMessage(originalMessage(radio, contact = survivor, text = "keep me"))

        store.deleteContact(EntityKey(radio, doomed))

        assertNull(store.fetchContact(EntityKey(radio, doomed)))
        assertTrue(store.fetchMessages(EntityKey(radio, doomed)).isEmpty())
        assertTrue(store.fetchReactions(EntityKey(radio, message.id)).isEmpty())
        assertTrue(store.fetchMessageRepeats(EntityKey(radio, message.id)).isEmpty())
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        assertNotNull(store.fetchContact(EntityKey(radio, survivor)))
        assertEquals(1, store.fetchMessages(EntityKey(radio, survivor)).size)
    }

    @OriginalCase("PersistenceStoreTests::deleteContacts skippingPublicKeys leaves those rows()")
    @Test fun originalEarlyB1B2SkipRetainsExactDeletedSetBothContactsAndKeptHistory() = runTest {
        val radio = radio()
        val a = literalFrame(0xB1, "Keep")
        val b = literalFrame(0xB2, "Drop")
        val savedA = store.saveContact(radio, a).id
        val savedB = store.saveContact(radio, b).id
        store.saveMessage(originalMessage(radio, contact = savedA, text = "keep"))

        val deleted = store.deleteContacts(radio, listOf(a.publicKey, b.publicKey).snapshotSet()) {
            listOf(a.publicKey).snapshotSet()
        }

        assertEquals(setOf(savedB), deleted.toSet())
        assertNotNull(store.fetchContact(EntityKey(radio, savedA)))
        assertNull(store.fetchContact(EntityKey(radio, savedB)))
        assertEquals(1, store.fetchMessages(EntityKey(radio, savedA), 10, 0).size)
    }

    @OriginalCase("PersistenceStoreTests::deleteContacts skippingPublicKeys after fetch leaves those rows()")
    @Test fun originalLateC1C2ThirdCallbackSkipRetainsIndependentStateExactDeletedSetContactsAndHistory() = runTest {
        val radio = radio()
        val a = literalFrame(0xC1, "KeepLate")
        val b = literalFrame(0xC2, "DropLate")
        val savedA = store.saveContact(radio, a).id
        val savedB = store.saveContact(radio, b).id
        store.saveMessage(originalMessage(radio, contact = savedA, text = "keep"))
        var calls = 0

        val deleted = store.deleteContacts(radio, listOf(a.publicKey, b.publicKey).snapshotSet()) {
            calls += 1
            if (calls <= 2) SnapshotSet.empty() else listOf(a.publicKey).snapshotSet()
        }

        assertEquals(setOf(savedB), deleted.toSet())
        assertNotNull(store.fetchContact(EntityKey(radio, savedA)))
        assertNull(store.fetchContact(EntityKey(radio, savedB)))
        assertEquals(1, store.fetchMessages(EntityKey(radio, savedA), 10, 0).size)
    }

    @OriginalCase("PersistenceStoreTests::deleteContacts save throw rolls back contact and cascade()")
    @Test fun originalD1StagedDeleteFailureRetainsContactMessageReactionRepeatAndMatchedPendingCounts() = runTest {
        val radio = radio()
        val frame = literalFrame(0xD1, "KeepOnThrow")
        val saved = store.saveContact(radio, frame).id
        val message = originalMessage(radio, contact = saved, text = "keep")
        store.saveMessage(message)
        originalRepeat(radio, message.id)
        originalReaction(radio, message.id, "Reactor", "AABBCCDD", saved)
        store.upsertPendingSend(PendingSendDTO(
            UUID.randomUUID(), radio, message.id, PendingSendKind.DM, saved, null,
            false, "keep", 0u, null, 1, Instant.now(), 0,
        ))

        rejectAfterDelete("contacts") {
            store.deleteContacts(radio, listOf(frame.publicKey).snapshotSet())
        }

        assertEquals(saved, store.fetchContact(EntityKey(radio, saved))?.id)
        assertEquals(1, store.fetchMessages(EntityKey(radio, saved), 10, 0).size)
        assertEquals(1, store.fetchReactions(EntityKey(radio, message.id)).size)
        assertEquals(1, store.fetchMessageRepeats(EntityKey(radio, message.id)).size)
        assertEquals(1, store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).size)
    }

    @OriginalCase("PersistenceStoreTests::deleteContacts rollback does not drop unsaved RxLog inserts()")
    @Test fun originalD2FaultRetainsFreshQueued42ChannelOneZeroBeforeMatchingIdAndFinalFlush() = runTest {
        val radio = radio()
        val frame = literalFrame(0xD2, "RxLog")
        store.saveContact(radio, frame)
        val dto = originalRx(radio)
        store.saveRxLogEntry(dto)
        assertEquals(0L, store.rxInitiatedSaveCount)

        rejectAfterDelete("contacts") {
            store.deleteContacts(radio, listOf(frame.publicKey).snapshotSet())
        }

        assertEquals(dto.id, store.findRxLogEntry(radio, 1u, 42u)?.id)
        store.flushPendingRxLogEntries()
    }

    @OriginalCase("PersistenceStoreTests::deleteContact removes local data when the contact row is already gone()")
    @Test fun originalAbsentContactWithOrphanedMessageAndFreshPendingRetainsBothEmptyPostconditions() = runTest {
        val radio = radio()
        val ghost = UUID.randomUUID()
        val message = originalMessage(radio, contact = ghost, text = "orphaned")
        store.saveMessage(message)
        store.upsertPendingSend(originalPending(radio, message.id, 1))

        store.deleteContact(EntityKey(radio, ghost))

        assertTrue(store.fetchMessages(EntityKey(radio, ghost)).isEmpty())
        assertTrue(store.fetchPendingSends(radio).isEmpty())
    }

    @OriginalCase("PersistenceStoreTests::deleteMessagesForChannel cascades PendingSends and spares other channels()")
    @Test fun originalThreeChannelZeroAndOneChannelOneRowsRetainFreshSequencesOneToThreeAnd99MatchingSurvivor() = runTest {
        val radio = radio()
        for (index in 0 until 3) {
            val message = originalMessage(radio, channel = 0u, text = "Ch0 $index", timestamp = nowTimestamp() + index.toUInt())
            store.saveMessage(message)
            store.upsertPendingSend(originalPending(radio, message.id, index + 1L))
        }
        val untouched = originalMessage(radio, channel = 1u, text = "Ch1 keep", timestamp = nowTimestamp() + 100u)
        store.saveMessage(untouched)
        store.upsertPendingSend(originalPending(radio, untouched.id, 99))

        store.deleteMessagesForChannel(radio, 0u)

        val remaining = store.fetchPendingSends(radio)
        assertEquals(1, remaining.size)
        assertEquals(untouched.id, remaining.firstOrNull()?.messageID)
        assertEquals(1, store.fetchMessages(radio, 1u).size)
    }

    @OriginalCase("PersistenceStoreTests::deleteMessagesForChannel save throw rolls back messages and cascade()")
    @Test fun originalChannelZeroStagedFailureAndLaterPiggybackRetainAllFiveCascadeAndSiblingPredicates() = runTest {
        val radio = radio()
        val message = originalMessage(radio, channel = 0u, text = "Ch0 keep")
        store.saveMessage(message)
        store.upsertPendingSend(originalPending(radio, message.id, 1))
        originalReaction(radio, message.id, "Reactor", "AABBCCDD")
        originalRepeat(radio, message.id)
        store.saveMessage(originalMessage(radio, channel = 1u, text = "Ch1 keep", timestamp = nowTimestamp() + 100u))

        rejectAfterDelete("messages") { store.deleteMessagesForChannel(radio, 0u) }
        store.saveMessage(originalMessage(radio, channel = 1u, text = "Ch1 extra", timestamp = nowTimestamp() + 200u))

        assertEquals(1, store.fetchMessages(radio, 0u).size)
        assertEquals(1, store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).size)
        assertEquals(1, store.fetchReactions(EntityKey(radio, message.id)).size)
        assertEquals(1, store.fetchMessageRepeats(EntityKey(radio, message.id)).size)
        assertEquals(2, store.fetchMessages(radio, 1u).size)
    }

    @OriginalCase("PersistenceStoreTests::deleteMessagesForChannel rollback does not drop unsaved RxLog inserts()")
    @Test fun originalChannelZeroCh0AndFreshQueued42FaultRetainZeroBeforeMatchingIdAndFinalFlush() = runTest {
        val radio = radio()
        store.saveMessage(originalMessage(radio, channel = 0u, text = "Ch0"))
        val dto = originalRx(radio)
        store.saveRxLogEntry(dto)
        assertEquals(0L, store.rxInitiatedSaveCount)

        rejectAfterDelete("messages") { store.deleteMessagesForChannel(radio, 0u) }

        assertEquals(dto.id, store.findRxLogEntry(radio, 1u, 42u)?.id)
        store.flushPendingRxLogEntries()
    }

    @OriginalCase("PersistenceStoreTests::deleteChannelMessages(fromSender:) cascades PendingSends and spares other senders()")
    @Test fun originalThreeSpammerAndOneFriendChannelZeroRowsRetainOneMatchingFriendPending99() = runTest {
        val radio = radio()
        for (index in 0 until 3) {
            val message = originalMessage(
                radio, channel = 0u, text = "spam $index",
                timestamp = nowTimestamp() + index.toUInt(), sender = "Spammer",
            )
            store.saveMessage(message)
            store.upsertPendingSend(originalPending(radio, message.id, index + 1L))
        }
        val untouched = originalMessage(
            radio, channel = 0u, text = "friend", timestamp = nowTimestamp() + 100u, sender = "Friend",
        )
        store.saveMessage(untouched)
        store.upsertPendingSend(originalPending(radio, untouched.id, 99))

        store.deleteChannelMessages("Spammer", radio)

        val remaining = store.fetchPendingSends(radio)
        assertEquals(1, remaining.size)
        assertEquals(untouched.id, remaining.firstOrNull()?.messageID)
    }
}
