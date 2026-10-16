package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.MessageActionAvailability
import one.meshcore.shared.chat.MessagePathFormatter
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.RouteType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.tools.RxLogEntry
import one.meshcore.shared.tools.RxLogPayloadType
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RoomChatStore] against a real (file-backed) Room database, covering the behavior the
 * committed chat services rely on: the DTO round-trip, the newest-window/oldest-first thread paging, the
 * §2 status write guards, the ingest counters/dedup, and the last-message re-derivation on delete.
 */
class RoomChatStoreTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var store: RoomChatStore

    private val radioID = ChatFixtures.RADIO
    private val contactID = ChatFixtures.CONTACT

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("room-chat-store", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        store = RoomChatStore(
            radioID,
            db.chatDaos(),
            RxLogCorrelator(db.rxLogEntryDao(), nowEpochMs = { FIXED_NOW_MS }),
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun savedMessageRoundTripsThroughTheStore() = runBlocking {
        val message = ChatFixtures.message(text = "round-trip", status = MessageStatus.SENT)
        store.saveMessage(message)

        val fetched = store.fetchMessage(message.id)
        assertEquals(message.text, fetched?.text)
        assertEquals(MessageStatus.SENT, fetched?.status)
        assertEquals(message.direction, fetched?.direction)
    }

    @Test
    fun shadeMarkAsReadSetsPerMessageIsRead() = runBlocking {
        val message = ChatFixtures.message(text = "unread", status = MessageStatus.SENT).copy(isRead = false)
        store.saveMessage(message)
        store.markMessageAsRead(message.id)
        assertTrue(store.fetchMessage(message.id)?.isRead == true)
    }

    @Test
    fun openingAConversationMarksItsUnseenMentionsSeen() = runBlocking {
        val unseen = dm(sortDate = 1.0, text = "@me").copy(containsSelfMention = true, mentionSeen = false)
        val alreadySeen = dm(sortDate = 2.0, text = "@me-seen").copy(
            containsSelfMention = true,
            mentionSeen = true,
        )
        val plain = dm(sortDate = 3.0, text = "hi")
        store.saveMessage(unseen)
        store.saveMessage(alreadySeen)
        store.saveMessage(plain)

        val flipped = store.markConversationMentionsSeen(ChatConversationID.dm(radioID, contactID))
        assertEquals(listOf(unseen.id), flipped)
        assertTrue(store.fetchMessage(unseen.id)?.mentionSeen == true)
        assertTrue(store.fetchMessage(alreadySeen.id)?.mentionSeen == true)
        assertFalse(store.fetchMessage(plain.id)?.mentionSeen == true)
    }

    @Test
    fun directThreadPageReturnsNewestWindowOldestFirst() = runBlocking {
        // Three DMs at increasing sort dates; the page must be the newest two, oldest-first.
        listOf(10.0, 20.0, 30.0).forEach { store.saveMessage(dm(sortDate = it, text = "at-$it")) }

        val page = store.fetchDirectMessages(contactID, limit = 2, offset = 0)
        assertEquals(listOf("at-20.0", "at-30.0"), page.map { it.text })
    }

    @Test
    fun statusGuardBlocksDowngradeOfDeliveredButAllowsSentToFailed() = runBlocking {
        val delivered = dm(sortDate = 1.0, text = "d").copy(status = MessageStatus.DELIVERED)
        val sent = dm(sortDate = 2.0, text = "s").copy(status = MessageStatus.SENT)
        store.saveMessage(delivered)
        store.saveMessage(sent)

        assertFalse(store.updateMessageStatusUnlessDelivered(delivered.id, MessageStatus.FAILED))
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(delivered.id)?.status)

        assertTrue(store.markMessageFailedUnlessDelivered(sent.id))
        assertEquals(MessageStatus.FAILED, store.fetchMessage(sent.id)?.status)
        assertEquals(false, store.fetchMessage(sent.id)?.failureSeen)
    }

    @Test
    fun openingAConversationMarksUnseenFailedSendsSeen() = runBlocking {
        val unseen = dm(sortDate = 1.0, text = "fail").copy(
            status = MessageStatus.FAILED,
            direction = one.meshcore.shared.model.MessageDirection.OUTGOING,
        )
        val alreadySeen = dm(sortDate = 2.0, text = "seen").copy(
            status = MessageStatus.FAILED,
            direction = one.meshcore.shared.model.MessageDirection.OUTGOING,
            failureSeen = true,
        )
        store.saveMessage(unseen)
        store.saveMessage(alreadySeen)

        store.markFailedSendsSeen(ChatConversationID.dm(radioID, contactID))

        assertTrue(store.fetchMessage(unseen.id)?.failureSeen == true)
        assertTrue(store.fetchMessage(alreadySeen.id)?.failureSeen == true)
    }

    @Test
    fun transitionToFailedFromSentClearsFailureSeen() = runBlocking {
        val sent = dm(sortDate = 1.0, text = "s").copy(
            status = MessageStatus.SENT,
            failureSeen = true,
        )
        store.saveMessage(sent)

        store.updateMessageStatus(sent.id, MessageStatus.FAILED)

        assertEquals(false, store.fetchMessage(sent.id)?.failureSeen)
    }

    @Test
    fun idempotentFailedWriteDoesNotClearFailureSeen() = runBlocking {
        val failed = dm(sortDate = 1.0, text = "f").copy(
            status = MessageStatus.FAILED,
            failureSeen = true,
        )
        store.saveMessage(failed)

        store.updateMessageStatus(failed.id, MessageStatus.FAILED)

        assertEquals(true, store.fetchMessage(failed.id)?.failureSeen)
    }

    @Test
    fun fetchMessageWindowWidensToTheAnchor() = runBlocking {
        db.contactDao().upsert(ChatFixtures.contact(name = "Alice", id = contactID).toEntity())
        for (index in 1..5) {
            store.saveMessage(dm(sortDate = index.toDouble(), text = "m$index"))
        }
        val conversation = ChatConversationID.dm(radioID, contactID)
        val first = store.fetchMessageWindow(conversation, anchorSortDate = null, floorLimit = 2)
        assertEquals(2, first.messages.size)
        assertTrue(first.hasMore)
        assertEquals(4.0, first.messages.first().sortDate)
        assertEquals(5.0, first.messages.last().sortDate)

        val refreshed = store.fetchMessageWindow(
            conversation,
            anchorSortDate = 3.0,
            floorLimit = 2,
        )
        assertEquals(3, refreshed.messages.size)
        assertTrue(refreshed.hasMore)
        assertEquals(3.0, refreshed.messages.first().sortDate)
        assertEquals(5.0, refreshed.messages.last().sortDate)
    }

    @Test
    fun ingestCountersAndDedupTrackPerContact() = runBlocking {
        db.contactDao().upsert(ChatFixtures.contact(name = "Alice", id = contactID).toEntity())

        store.incrementContactUnread(contactID)
        store.incrementContactUnread(contactID)
        assertEquals(2, db.contactDao().getById(contactID)?.unreadCount)

        store.clearUnread(ChatConversationID.dm(radioID, contactID))
        assertEquals(0, db.contactDao().getById(contactID)?.unreadCount)
    }

    @Test
    fun shadeMarkReadZerosTheUnreadCountAndKeepsMentions() = runBlocking {
        db.contactDao().upsert(
            ChatFixtures.contact(name = "Alice", id = contactID, unreadCount = 3).toEntity()
                .copy(unreadMentionCount = 2),
        )

        store.clearUnreadCount(ChatConversationID.dm(radioID, contactID))

        val row = db.contactDao().getById(contactID)
        assertEquals(0, row?.unreadCount)
        assertEquals(2, row?.unreadMentionCount)

        store.saveMessage(dm(sortDate = 5.0, text = "dup").copy(deduplicationKey = "KEY"))
        assertTrue(store.isDuplicateMessage("KEY", radioID))
        assertFalse(store.isDuplicateMessage("OTHER", radioID))
    }

    @Test
    fun recomputeLastMessageFallsBackToOlderRowAfterDeletingNewest() = runBlocking {
        db.contactDao().upsert(ChatFixtures.contact(name = "Alice", id = contactID, lastMessageDate = 30.0).toEntity())
        val older = dm(sortDate = 10.0, text = "older")
        val newer = dm(sortDate = 30.0, text = "newer")
        store.saveMessage(older)
        store.saveMessage(newer)

        store.deleteMessage(newer.id)
        store.recomputeContactLastMessageDate(contactID)

        assertEquals(10.0, db.contactDao().getById(contactID)?.lastMessageDate)
    }

    @Test
    fun deleteMessageCascadesItsPendingSendReactionsAndRepeats() = runBlocking {
        val message = dm(sortDate = 10.0, text = "deleted")
        store.saveMessage(message)
        db.messageRepeatDao().upsert(
            MessageRepeatEntity(
                id = ChatFixtures.freshUuid(),
                messageID = message.id,
                receivedAt = 0.0,
                pathNodes = ByteArray(0),
            ),
        )
        db.reactionDao().upsert(
            ReactionEntity(
                id = ChatFixtures.freshUuid(),
                messageID = message.id,
                emoji = "👍",
                senderName = "Bob",
                messageHash = "h",
                rawText = "r",
                receivedAt = 0.0,
                radioID = radioID,
                contactID = contactID,
            ),
        )
        db.pendingSendDao().upsert(
            PendingSendEntity(
                id = ChatFixtures.freshUuid(),
                radioID = radioID,
                messageID = message.id,
                kindRawValue = 0,
                isResend = false,
                messageText = "deleted",
                messageTimestamp = 0,
                sequence = 1,
                enqueuedAt = 0.0,
            ),
        )

        store.deleteMessage(message.id)

        assertNull(store.fetchMessage(message.id))
        assertFalse(db.pendingSendDao().exists(message.id), "pending send cascaded")
        assertTrue(db.reactionDao().listByMessage(message.id).isEmpty(), "reactions cascaded")
        // SwiftData cascades this one through Message -> MessageRepeat; Room declares no foreign key.
        assertTrue(db.messageRepeatDao().listByMessage(message.id).isEmpty(), "message repeats cascaded")
    }

    @Test
    fun deleteMessageReapsAnOrphanPendingSendWithNoMessageRow() = runBlocking {
        // iOS reaps the pending send outside the message guard: a queue upsert racing a delete can leave a
        // row whose message is already gone, and there is nothing left for it to stay consistent with.
        val orphanMessageID = ChatFixtures.freshUuid()
        db.pendingSendDao().upsert(
            PendingSendEntity(
                id = ChatFixtures.freshUuid(),
                radioID = radioID,
                messageID = orphanMessageID,
                kindRawValue = 0,
                isResend = false,
                messageText = "orphan",
                messageTimestamp = 0,
                sequence = 1,
                enqueuedAt = 0.0,
            ),
        )

        store.deleteMessage(orphanMessageID)

        assertFalse(db.pendingSendDao().exists(orphanMessageID), "orphan pending send reaped")
    }

    @Test
    fun recomputeLastMessageNullsWhenNoMessagesRemain() = runBlocking {
        db.contactDao().upsert(ChatFixtures.contact(name = "Alice", id = contactID, lastMessageDate = 10.0).toEntity())
        store.recomputeContactLastMessageDate(contactID)
        assertNull(db.contactDao().getById(contactID)?.lastMessageDate)
    }

    private fun dm(sortDate: Double, text: String, id: Uuid = ChatFixtures.freshUuid()) =
        ChatFixtures.message(id = id, text = text, sortDate = sortDate, contactID = contactID, channelIndex = null)

    /**
     * The routing path, route type and flood region are not in the message frame; they only exist on the
     * packet the RX log recorded. Without this correlation every received message shows no path, no route
     * type and no region, which is what the production store did while only the in-memory reference
     * implemented the lookup.
     */
    @Test
    fun aChannelMessageTakesItsPathAndRegionFromTheLoggedPacket() = runBlocking {
        db.rxLogEntryDao().upsert(
            rxEntry(channelIndex = CHANNEL_INDEX, senderTimestamp = SENDER_TIMESTAMP).toEntity(),
        )

        val correlation = store.lookupRxLog(
            radioID = radioID,
            channelIndex = CHANNEL_INDEX,
            senderTimestamp = SENDER_TIMESTAMP,
            senderKeyPrefix = null,
            defaultPathLength = REPORTED_PATH_LENGTH,
        )

        assertEquals(LOGGED_PATH_LENGTH, correlation.pathLength)
        assertEquals(RouteType.FLOOD, correlation.routeType)
        assertEquals(REGION, correlation.regionScope)
        assertEquals(listOf(REGION), correlation.regionScopeMatches)
        assertContentEquals(PATH_NODES, correlation.pathNodes)
    }

    @Test
    fun lookupRxLogReResolvesAmbiguousMatchesFromTheTransportCode() = runBlocking {
        val code = 0x7C2B
        db.rxLogEntryDao().upsert(
            rxEntry(channelIndex = CHANNEL_INDEX, senderTimestamp = SENDER_TIMESTAMP).copy(
                regionScope = null,
                packetPayload = byteArrayOf(0x11, 0x22),
                transportCode = byteArrayOf((code and 0xFF).toByte(), ((code shr 8) and 0xFF).toByte()),
                payloadTypeBits = 5,
            ).toEntity(),
        )
        val correlator = RxLogCorrelator(
            db.rxLogEntryDao(),
            { FIXED_NOW_MS },
            regionNames = { listOf("de-by", "de-hh") },
            regionResolver = one.meshcore.shared.meshcore.FakeRegionResolver(
                matchesByCode = mapOf(code to listOf("de-by", "de-hh")),
            ),
        )
        val correlation = correlator.correlate(
            radioID = radioID,
            channelIndex = CHANNEL_INDEX,
            senderTimestamp = SENDER_TIMESTAMP,
            senderKeyPrefix = null,
            defaultPathLength = REPORTED_PATH_LENGTH,
        )
        assertNull(correlation.regionScope)
        assertEquals(listOf("de-by", "de-hh"), correlation.regionScopeMatches)
    }

    /**
     * A direct message whose logged packet has no sender timestamp yet — decryption may not have reached
     * it — is matched on the sender prefix byte carried in the payload, the iOS fallback.
     */
    @Test
    fun aDirectMessageFallsBackToTheSenderPrefixByteWhenTheRowHasNoTimestamp() = runBlocking {
        db.rxLogEntryDao().upsert(
            rxEntry(
                channelIndex = null,
                senderTimestamp = null,
                packetPayload = byteArrayOf(0x00, SENDER_PREFIX_BYTE, 0x77),
            ).toEntity(),
        )

        val correlation = store.lookupRxLog(
            radioID = radioID,
            channelIndex = null,
            senderTimestamp = SENDER_TIMESTAMP,
            senderKeyPrefix = byteArrayOf(SENDER_PREFIX_BYTE, 0x12),
            defaultPathLength = REPORTED_PATH_LENGTH,
        )

        assertEquals(LOGGED_PATH_LENGTH, correlation.pathLength)
        assertEquals(REGION, correlation.regionScope)
    }

    /** A packet older than the fallback window must not claim the message. */
    @Test
    fun aStalePacketOutsideTheWindowDoesNotClaimADirectMessage() = runBlocking {
        db.rxLogEntryDao().upsert(
            rxEntry(
                channelIndex = null,
                senderTimestamp = null,
                packetPayload = byteArrayOf(0x00, SENDER_PREFIX_BYTE, 0x77),
                receivedAtEpochMs = FIXED_NOW_MS - STALE_AGE_MS,
            ).toEntity(),
        )

        val correlation = store.lookupRxLog(
            radioID = radioID,
            channelIndex = null,
            senderTimestamp = SENDER_TIMESTAMP,
            senderKeyPrefix = byteArrayOf(SENDER_PREFIX_BYTE, 0x12),
            defaultPathLength = REPORTED_PATH_LENGTH,
        )

        assertEquals(REPORTED_PATH_LENGTH, correlation.pathLength, "a miss keeps the reported length")
        assertNull(correlation.regionScope)
    }

    /**
     * A channel secret or region list that lands after a message was stored is that message's only
     * chance to learn its flood region; without the back-fill the chat's region line stays blank forever.
     */
    @Test
    fun aRegionResolvedLateReachesAMessageAlreadyStored() = runBlocking {
        // Uncorrected ingest: wire time lives on `timestamp`, `senderTimestamp` stays null.
        store.saveMessage(
            ChatFixtures.message(
                channelIndex = CHANNEL_INDEX,
                timestamp = SENDER_TIMESTAMP,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                contactID = null,
            ),
        )

        val touched = MessageRegionBackfill(db.messageDao())
            .apply(listOf(rxEntry(channelIndex = CHANNEL_INDEX, senderTimestamp = SENDER_TIMESTAMP)))

        val saved = db.messageDao().listByRadio(radioID).single()
        assertEquals(REGION, saved.regionScope)
        assertEquals(listOf(REGION), saved.regionScopeMatches)
        assertEquals(listOf(saved.id), touched)
    }

    @Test
    fun anAmbiguousLateRegionStampsMatchesWithoutAUniqueScope() = runBlocking {
        store.saveMessage(
            ChatFixtures.message(
                channelIndex = CHANNEL_INDEX,
                timestamp = SENDER_TIMESTAMP,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                contactID = null,
            ),
        )

        MessageRegionBackfill(db.messageDao()).apply(
            listOf(
                rxEntry(channelIndex = CHANNEL_INDEX, senderTimestamp = SENDER_TIMESTAMP).copy(
                    regionScope = null,
                    regionScopeMatches = listOf("de-by", "de-hh"),
                ),
            ),
        )

        val saved = db.messageDao().listByRadio(radioID).single()
        assertNull(saved.regionScope)
        assertEquals(listOf("de-by", "de-hh"), saved.regionScopeMatches)
    }

    @Test
    fun aCollidingRegionRewritesAStickyUniqueOnAStoredMessage() = runBlocking {
        store.saveMessage(
            ChatFixtures.message(
                channelIndex = CHANNEL_INDEX,
                timestamp = SENDER_TIMESTAMP,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                contactID = null,
            ).copy(regionScope = REGION, regionScopeMatches = listOf(REGION)),
        )

        val touched = MessageRegionBackfill(db.messageDao()).apply(
            listOf(
                rxEntry(channelIndex = CHANNEL_INDEX, senderTimestamp = SENDER_TIMESTAMP).copy(
                    regionScope = null,
                    regionScopeMatches = listOf("de-by", "de-hh"),
                ),
            ),
        )

        val saved = db.messageDao().listByRadio(radioID).single()
        assertNull(saved.regionScope)
        assertEquals(listOf("de-by", "de-hh"), saved.regionScopeMatches)
        assertEquals(listOf(saved.id), touched)
    }

    @Test
    fun anEmptyCacheClearsAStickyUniqueOnAStoredMessage() = runBlocking {
        store.saveMessage(
            ChatFixtures.message(
                channelIndex = CHANNEL_INDEX,
                timestamp = SENDER_TIMESTAMP,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                contactID = null,
            ).copy(regionScope = REGION, regionScopeMatches = listOf(REGION)),
        )

        val touched = MessageRegionBackfill(db.messageDao()).apply(
            listOf(
                rxEntry(channelIndex = CHANNEL_INDEX, senderTimestamp = SENDER_TIMESTAMP).copy(
                    regionScope = null,
                    regionScopeMatches = emptyList(),
                    transportCode = byteArrayOf(0x2B, 0x7C),
                ),
            ),
        )

        val saved = db.messageDao().listByRadio(radioID).single()
        assertNull(saved.regionScope)
        assertTrue(saved.regionScopeMatches.isEmpty())
        assertEquals(listOf(saved.id), touched)
    }

    @Test
    fun regionBackfillSkipsOutgoingMessages() = runBlocking {
        store.saveMessage(
            ChatFixtures.message(
                channelIndex = CHANNEL_INDEX,
                timestamp = SENDER_TIMESTAMP,
                direction = MessageDirection.OUTGOING,
                senderTimestamp = SENDER_TIMESTAMP,
            ),
        )

        MessageRegionBackfill(db.messageDao())
            .apply(listOf(rxEntry(channelIndex = CHANNEL_INDEX, senderTimestamp = SENDER_TIMESTAMP)))

        assertNull(db.messageDao().listByRadio(radioID).single().regionScope)
    }

    /**
     * Hop hashes live on the RX-log packet, not the get_msg frame. When the packet is attributed after
     * the message was stored, the back-fill is the only way the footer can show hop hex and Path Map
     * can appear.
     */
    @Test
    fun aPathResolvedLateReachesAMessageAlreadyStored() = runBlocking {
        store.saveMessage(
            ChatFixtures.message(
                channelIndex = CHANNEL_INDEX,
                timestamp = SENDER_TIMESTAMP,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                contactID = null,
            ),
        )

        val touched = MessageRegionBackfill(db.messageDao())
            .apply(listOf(rxEntry(channelIndex = CHANNEL_INDEX, senderTimestamp = SENDER_TIMESTAMP)))

        val saved = db.messageDao().listByRadio(radioID).single()
        assertContentEquals(PATH_NODES, saved.pathNodes)
        assertEquals(LOGGED_PATH_LENGTH, saved.pathLength)
        assertEquals(RouteType.FLOOD.raw, saved.routeTypeRawValue)
        assertEquals(listOf(saved.id), touched)
        val dto = saved.toDto()
        assertEquals("11,22", MessagePathFormatter.format(dto, "Direct", "Flood"))
        assertTrue(MessageActionAvailability.of(dto).canViewPath)
    }

    @Test
    fun aPathWithoutARegionStillReachesAStoredMessage() = runBlocking {
        store.saveMessage(
            ChatFixtures.message(
                channelIndex = CHANNEL_INDEX,
                timestamp = SENDER_TIMESTAMP,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                contactID = null,
            ),
        )

        MessageRegionBackfill(db.messageDao()).apply(
            listOf(
                rxEntry(channelIndex = CHANNEL_INDEX, senderTimestamp = SENDER_TIMESTAMP).copy(
                    regionScope = null,
                    regionScopeMatches = emptyList(),
                ),
            ),
        )

        val saved = db.messageDao().listByRadio(radioID).single()
        assertContentEquals(PATH_NODES, saved.pathNodes)
        assertNull(saved.regionScope)
    }

    @Test
    fun aLatePathReachesADirectMessageMatchedByPrefix() = runBlocking {
        val prefix = byteArrayOf(SENDER_PREFIX_BYTE, 0x12)
        store.saveMessage(
            ChatFixtures.message(
                contactID = contactID,
                timestamp = SENDER_TIMESTAMP,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                channelIndex = null,
            ).copy(senderKeyPrefix = prefix),
        )

        MessageRegionBackfill(db.messageDao()).apply(
            listOf(
                rxEntry(
                    channelIndex = null,
                    senderTimestamp = SENDER_TIMESTAMP,
                    packetPayload = byteArrayOf(0x00, SENDER_PREFIX_BYTE, 0x77),
                ),
            ),
        )

        assertContentEquals(PATH_NODES, db.messageDao().listByRadio(radioID).single().pathNodes)
    }

    /**
     * Flood DMs are not decrypted, so the RX-log row never gains a sender timestamp. get_msg still
     * stored the bubble; hop hashes only land if the back-fill can match the prefix byte in a short
     * receive window, the inverse of ingest's DM fallback.
     */
    @Test
    fun aFloodDirectPacketWithoutTimestampStampsARecentIncomingDmByPrefix() = runBlocking {
        val prefix = byteArrayOf(SENDER_PREFIX_BYTE, 0x12)
        store.saveMessage(
            ChatFixtures.message(
                contactID = contactID,
                timestamp = SENDER_TIMESTAMP,
                createdAt = FIXED_NOW_MS / MILLIS_PER_SECOND,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                channelIndex = null,
            ).copy(senderKeyPrefix = prefix),
        )

        val touched = MessageRegionBackfill(db.messageDao()).apply(
            listOf(
                rxEntry(
                    channelIndex = null,
                    senderTimestamp = null,
                    packetPayload = byteArrayOf(0x00, SENDER_PREFIX_BYTE, 0x77),
                    payloadType = RxLogPayloadType.TEXT_MESSAGE,
                ),
            ),
        )

        val saved = db.messageDao().listByRadio(radioID).single()
        assertContentEquals(PATH_NODES, saved.pathNodes)
        assertEquals(listOf(saved.id), touched)
        val dto = saved.toDto()
        assertEquals("11,22", MessagePathFormatter.format(dto, "Direct", "Flood"))
        assertTrue(MessageActionAvailability.of(dto).canViewPath)
    }

    @Test
    fun aGroupPacketWithoutTimestampDoesNotStampADirectMessageByPrefix() = runBlocking {
        val prefix = byteArrayOf(SENDER_PREFIX_BYTE, 0x12)
        store.saveMessage(
            ChatFixtures.message(
                contactID = contactID,
                timestamp = SENDER_TIMESTAMP,
                createdAt = FIXED_NOW_MS / MILLIS_PER_SECOND,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                channelIndex = null,
            ).copy(senderKeyPrefix = prefix),
        )

        MessageRegionBackfill(db.messageDao()).apply(
            listOf(
                rxEntry(
                    channelIndex = null,
                    senderTimestamp = null,
                    packetPayload = byteArrayOf(0x00, SENDER_PREFIX_BYTE, 0x77),
                    payloadType = RxLogPayloadType.GROUP_TEXT,
                ),
            ),
        )

        assertNull(db.messageDao().listByRadio(radioID).single().pathNodes)
    }

    @Test
    fun aStaleDirectMessageOutsideTheReceiveWindowDoesNotTakeAFloodPacketPath() = runBlocking {
        val prefix = byteArrayOf(SENDER_PREFIX_BYTE, 0x12)
        store.saveMessage(
            ChatFixtures.message(
                contactID = contactID,
                timestamp = SENDER_TIMESTAMP,
                createdAt = (FIXED_NOW_MS - STALE_AGE_MS) / MILLIS_PER_SECOND,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                channelIndex = null,
            ).copy(senderKeyPrefix = prefix),
        )

        MessageRegionBackfill(db.messageDao()).apply(
            listOf(
                rxEntry(
                    channelIndex = null,
                    senderTimestamp = null,
                    packetPayload = byteArrayOf(0x00, SENDER_PREFIX_BYTE, 0x77),
                    payloadType = RxLogPayloadType.TEXT_MESSAGE,
                ),
            ),
        )

        assertNull(db.messageDao().listByRadio(radioID).single().pathNodes)
    }

    /** Live ingest wins: a late pass must never rewrite hops the message already correlated. */
    @Test
    fun theBackfillDoesNotOverwriteAPathTheMessageAlreadyHas() = runBlocking {
        val liveHops = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        store.saveMessage(
            ChatFixtures.message(
                channelIndex = CHANNEL_INDEX,
                timestamp = SENDER_TIMESTAMP,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                contactID = null,
            ).copy(pathNodes = liveHops, pathLength = 2),
        )

        MessageRegionBackfill(db.messageDao())
            .apply(listOf(rxEntry(channelIndex = CHANNEL_INDEX, senderTimestamp = SENDER_TIMESTAMP)))

        assertContentEquals(liveHops, db.messageDao().listByRadio(radioID).single().pathNodes)
    }

    /** iOS `reprocessRegionEntries` writes the current projection even when a unique is already stored. */
    @Test
    fun aLaterRxLogUniqueReplacesARegionTheMessageAlreadyHas() = runBlocking {
        store.saveMessage(
            ChatFixtures.message(
                channelIndex = CHANNEL_INDEX,
                timestamp = SENDER_TIMESTAMP,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                contactID = null,
            ).copy(regionScope = LIVE_REGION, regionScopeMatches = listOf(LIVE_REGION)),
        )

        val touched = MessageRegionBackfill(db.messageDao())
            .apply(listOf(rxEntry(channelIndex = CHANNEL_INDEX, senderTimestamp = SENDER_TIMESTAMP)))

        val saved = db.messageDao().listByRadio(radioID).single()
        assertEquals(REGION, saved.regionScope)
        assertEquals(listOf(REGION), saved.regionScopeMatches)
        assertEquals(listOf(saved.id), touched)
    }

    @Test
    fun incrementSendCountDoesNotRewriteStatus() = runBlocking {
        val message = ChatFixtures.message(status = MessageStatus.PENDING)
        store.saveMessage(message)
        db.messageDao().upsert(
            db.messageDao().getById(message.id)!!.copy(statusRawValue = MessageStatus.SENT.raw),
        )

        assertEquals(2, store.incrementMessageSendCount(message.id))
        val after = db.messageDao().getById(message.id)!!
        assertEquals(MessageStatus.SENT.raw, after.statusRawValue)
        assertEquals(2, after.sendCount)
    }

    private fun rxEntry(
        channelIndex: Int?,
        senderTimestamp: Long?,
        packetPayload: ByteArray = ByteArray(0),
        receivedAtEpochMs: Long = FIXED_NOW_MS,
        payloadType: Int = 0,
    ) = RxLogEntry(
        id = Uuid.normalized("B0000000-0000-0000-0000-000000000001"),
        radioID = radioID,
        receivedAtEpochMs = receivedAtEpochMs,
        snr = null,
        rssi = null,
        routeType = RouteType.FLOOD.raw,
        payloadType = payloadType,
        pathLength = LOGGED_PATH_LENGTH,
        packetHash = "AABB",
        packetPayloadSize = packetPayload.size,
        regionScope = REGION,
        channelIndex = channelIndex,
        senderTimestamp = senderTimestamp,
        packetPayload = packetPayload,
        pathNodes = PATH_NODES,
    )

    @Test
    fun fetchDeviceNodeNameReadsTheDeviceRowForThatRadio() = runBlocking {
        assertNull(store.fetchDeviceNodeName(radioID))
        db.deviceDao().upsert(deviceEntity(nodeName = "OpsRadio"))
        assertEquals("OpsRadio", store.fetchDeviceNodeName(radioID))
        assertNull(store.fetchDeviceNodeName(ChatFixtures.freshUuid()))
    }

    private fun deviceEntity(nodeName: String) = DeviceEntity(
        id = radioID,
        radioID = radioID,
        publicKey = ByteArray(PUBLIC_KEY_BYTES),
        nodeName = nodeName,
        firmwareVersion = 0,
        firmwareVersionString = "",
        manufacturerName = "",
        buildDate = "",
        maxContacts = 0,
        maxChannels = 0,
        frequency = 0,
        bandwidth = 0,
        spreadingFactor = 0,
        codingRate = 0,
        txPower = 0,
        maxTxPower = 0,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0,
        clientRepeat = false,
        pathHashMode = 0,
        manualAddContacts = false,
        autoAddConfig = 0,
        autoAddMaxHops = 0,
        multiAcks = 0,
        telemetryModeBase = 0,
        telemetryModeLoc = 0,
        telemetryModeEnv = 0,
        advertLocationPolicy = 0,
        lastConnected = 0.0,
        lastContactSync = 0,
        isActive = true,
    )

    private companion object {
        const val PUBLIC_KEY_BYTES = 32
        const val CHANNEL_INDEX = 3
        const val SENDER_TIMESTAMP = 1_700_000_000L
        const val LOGGED_PATH_LENGTH = 2
        const val REPORTED_PATH_LENGTH = 99
        const val REGION = "EU"
        const val LIVE_REGION = "US"
        const val SENDER_PREFIX_BYTE: Byte = 0x5A
        const val FIXED_NOW_MS = 1_700_000_500_000L
        const val MILLIS_PER_SECOND = 1_000.0
        const val STALE_AGE_MS = 60_000L
        val PATH_NODES = byteArrayOf(0x11, 0x22)
    }
}
