// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.ContactIdentity
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.database.toDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TextType
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.services.reactions.ReactionParser
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PersistenceOrphanAdoptionOriginalPredicateTest : RoomProcessTest() {
    private suspend fun device(): DeviceDTO {
        val id = UUID.randomUUID()
        return DeviceDTO(
            id = id, radioId = RadioId(id), publicKey = Bytes(ByteArray(32) { 0x44 }),
            nodeName = "TestDevice", firmwareVersion = 8u, firmwareVersionString = "v1.11.0",
            manufacturerName = "TestMfg", buildDate = "01 Jan 2025",
            latitude = 37.7749, longitude = -122.4194, multiAcks = 0u, isActive = false,
        ).also { store.saveDevice(it) }
    }

    private fun frame(name: String = "TestContact") = ContactFrame(
        Bytes(ByteArray(32) { 0x55 }), ContactType.CHAT, 0u, 2u, Bytes.of(1, 2), name,
        Instant.now().epochSecond.toUInt(), 37.7749, -122.4194, Instant.now().epochSecond.toUInt(),
    )

    private fun orphan(
        radio: RadioId,
        prefix: Bytes,
        text: String,
        stamp: UInt,
        id: UUID = UUID.randomUUID(),
        channel: UByte? = null,
        direction: MessageDirection = MessageDirection.INCOMING,
        status: MessageStatus = MessageStatus.DELIVERED,
        senderName: String? = null,
        isRead: Boolean = false,
        date: Instant = Instant.now(),
        selfMention: Boolean = false,
    ) = MessageDTO(
        id = id, radioId = radio, contactID = null, channelIndex = channel, text = text,
        timestamp = stamp, createdAt = date, sortDate = date, direction = direction, status = status,
        textType = TextType.PLAIN, ackCode = null, pathLength = 0u, snr = null, pathNodes = null,
        senderKeyPrefix = prefix, senderNodeName = senderName, isRead = isRead, replyToID = null,
        roundTripTime = null, heardRepeats = 0, sendCount = 1, retryAttempt = 0,
        maxRetryAttempts = 0, containsSelfMention = selfMention,
    )

    private suspend fun allMessages(radio: RadioId) =
        db.messages().backupPageWithoutPreviewBlobs(radio.value, Long.MAX_VALUE, 0).map { it.toDTO() }

    @OriginalCase("PersistenceStoreTests::adoptOrphanedDirectMessages links orphan DM and updates unread()")
    @Test fun originalLinkAndIdempotenceRetainReturnedCountsRequiredRowsCanonicalRekeyAndBothCounters() = runTest {
        val device = device()
        val frame = frame("LateContact")
        val contactID = store.saveContact(device.radioId, frame).id
        val stamp = 1_700_000_200u
        store.saveMessage(orphan(
            device.radioId, frame.publicKey.prefix(6), "hello orphan", stamp,
            date = Instant.ofEpochSecond(stamp.toLong()), selfMention = true,
        ))
        val identities = SnapshotList.of(ContactIdentity(contactID, frame.publicKey))

        val adopted = store.adoptOrphanedDirectMessages(device.radioId, identities)
        assertEquals(1L, adopted[contactID])
        val messages = store.fetchMessages(EntityKey(device.radioId, contactID), 10, 0)
        assertEquals(1, messages.size)
        assertEquals(contactID, messages[0].contactID)
        assertEquals(true, messages[0].deduplicationKey?.contains(contactID.canonicalString()))
        val contact = assertNotNull(store.fetchContact(EntityKey(device.radioId, contactID)))
        assertEquals(1L, contact.unreadCount)
        assertEquals(1L, contact.unreadMentionCount)
        assertNotNull(contact.lastMessageDate)

        val second = store.adoptOrphanedDirectMessages(device.radioId, identities)
        assertTrue(second.isEmpty())
        val after = assertNotNull(store.fetchContact(EntityKey(device.radioId, contactID)))
        assertEquals(1L, after.unreadCount)
        assertEquals(1L, after.unreadMentionCount)
    }

    @OriginalCase("PersistenceStoreTests::newestUnreadIncomingMessage returns nil when no unread incoming exists()")
    @Test fun originalNilUnreadResultRetainsActualReadIncomingRowNotAnEmptyStore() = runTest {
        val device = device()
        val contactID = store.saveContact(device.radioId, frame()).id
        store.saveMessage(MessageDTO(
            radioId = device.radioId, contactID = contactID, text = "already read",
            timestamp = 1_700_000_100u, direction = MessageDirection.INCOMING, isRead = true,
        ))

        assertNull(store.newestUnreadIncomingMessage(EntityKey(device.radioId, contactID)))
    }

    @OriginalCase("PersistenceStoreTests::adoptOrphanedDirectMessages never adopts a channel message()")
    @Test fun originalChannelSkipRetainsEmptyAdoptionRequiredMessageContactNilAndChannelOne() = runTest {
        val device = device()
        val frame = frame("Only")
        val contactID = store.saveContact(device.radioId, frame).id
        val messageID = UUID.randomUUID()
        store.saveMessage(orphan(
            device.radioId, frame.publicKey.prefix(6), "channel", 1_700_000_300u,
            id = messageID, channel = 1u, senderName = "Sender",
        ))

        val adopted = store.adoptOrphanedDirectMessages(
            device.radioId, SnapshotList.of(ContactIdentity(contactID, frame.publicKey)),
        )
        assertTrue(adopted.isEmpty())
        val row = assertNotNull(allMessages(device.radioId).firstOrNull { it.id == messageID })
        assertNull(row.contactID)
        assertEquals(1.toUByte(), row.channelIndex)
    }

    @OriginalCase("PersistenceStoreTests::adoptOrphanedDirectMessages never adopts an outgoing message()")
    @Test fun originalOutgoingSkipRetainsSentReadInputEmptyAdoptionRequiredOriginalRowAndContactNil() = runTest {
        val device = device()
        val frame = frame("Only")
        val contactID = store.saveContact(device.radioId, frame).id
        val messageID = UUID.randomUUID()
        store.saveMessage(orphan(
            device.radioId, frame.publicKey.prefix(6), "outgoing", 1_700_000_301u,
            id = messageID, direction = MessageDirection.OUTGOING, status = MessageStatus.SENT, isRead = true,
        ))

        val adopted = store.adoptOrphanedDirectMessages(
            device.radioId, SnapshotList.of(ContactIdentity(contactID, frame.publicKey)),
        )
        assertTrue(adopted.isEmpty())
        val row = assertNotNull(allMessages(device.radioId).firstOrNull { it.id == messageID })
        assertNull(row.contactID)
    }

    @OriginalCase("PersistenceStoreTests::adoptOrphanedDirectMessages never adopts reaction wire text()")
    @Test fun originalReactionSkipRetainsAffirmativeDmParserSoleMatchRequiredOriginalRowAndContactNil() = runTest {
        val device = device()
        val frame = frame("Only")
        val contactID = store.saveContact(device.radioId, frame).id
        val messageID = UUID.randomUUID()
        assertTrue(ReactionParser.isReactionText("r:abcd:01", isDM = true))
        store.saveMessage(orphan(
            device.radioId, frame.publicKey.prefix(6), "r:abcd:01", 1_700_000_302u, id = messageID,
        ))

        val adopted = store.adoptOrphanedDirectMessages(
            device.radioId, SnapshotList.of(ContactIdentity(contactID, frame.publicKey)),
        )
        assertTrue(adopted.isEmpty())
        val row = assertNotNull(allMessages(device.radioId).firstOrNull { it.id == messageID })
        assertNull(row.contactID)
    }

    @OriginalCase("PersistenceStoreTests::adoptOrphanedDirectMessages skips multi-match prefix()")
    @Test fun originalAmbiguousPrefixRetainsWrappingByteSixMutationTwoFullContactsAndOptionalUnlinkedRead() = runTest {
        val device = device()
        val frameA = frame("A")
        val idA = store.saveContact(device.radioId, frameA).id
        val keyB = ByteArray(32) { 0x55 }.apply { this[6] = (this[6].toInt() + 1).toByte() }
        val frameB = ContactFrame(
            Bytes(keyB), frameA.type, frameA.flags, 0u, Bytes.EMPTY, "B",
            frameA.lastAdvertTimestamp, 0.0, 0.0, frameA.lastModified,
        )
        val idB = store.saveContact(device.radioId, frameB).id
        val messageID = UUID.randomUUID()
        store.saveMessage(orphan(
            device.radioId, frameA.publicKey.prefix(6), "ambiguous", 1_700_000_303u, id = messageID,
        ))

        val multi = store.adoptOrphanedDirectMessages(device.radioId, SnapshotList.of(
            ContactIdentity(idA, frameA.publicKey), ContactIdentity(idB, frameB.publicKey),
        ))
        assertTrue(multi.isEmpty())
        assertNull(allMessages(device.radioId).firstOrNull { it.id == messageID }?.contactID)
    }

    @OriginalCase("PersistenceStoreTests::adoptOrphanedDirectMessages adopts blocked contact without unread bump()")
    @Test fun originalBlockedAdoptionRetainsDistinctResetDtoRequiredBeforeAfterRowsCountAndHistoryWithoutUnread() = runTest {
        val device = device()
        val frame = frame("Blocked")
        val contactID = store.saveContact(device.radioId, frame).id
        val blockedDTO = assertNotNull(store.fetchContact(EntityKey(device.radioId, contactID)))
        store.saveContact(ContactDTO(
            id = contactID, radioId = device.radioId, publicKey = frame.publicKey, name = "Blocked",
            typeRawValue = blockedDTO.typeRawValue, flags = blockedDTO.flags,
            outPathLength = blockedDTO.outPathLength, outPath = blockedDTO.outPath,
            lastAdvertTimestamp = blockedDTO.lastAdvertTimestamp, latitude = 0.0, longitude = 0.0,
            lastModified = blockedDTO.lastModified, lastHeardTimestamp = null, isBlocked = true,
            nickname = null, isMuted = false, isFavorite = false, lastMessageDate = null,
            unreadCount = 0, unreadMentionCount = 0, avatarImageData = null,
        ))
        store.saveMessage(orphan(
            device.radioId, frame.publicKey.prefix(6), "blocked orphan", 1_700_000_304u,
        ))

        val adopted = store.adoptOrphanedDirectMessages(
            device.radioId, SnapshotList.of(ContactIdentity(contactID, frame.publicKey)),
        )
        assertEquals(1L, adopted[contactID])
        val blocked = assertNotNull(store.fetchContact(EntityKey(device.radioId, contactID)))
        assertEquals(0L, blocked.unreadCount)
        assertEquals(1, store.fetchMessages(EntityKey(device.radioId, contactID), 5, 0).size)
    }
}
