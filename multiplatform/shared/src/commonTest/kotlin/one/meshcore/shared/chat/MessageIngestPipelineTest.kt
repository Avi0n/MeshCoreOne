package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ReactionHook
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.RouteType
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `SyncCoordinatorMessageHandlerTests` ingest behavior plus `MessageDeduplicationTests`:
 * the shared pipeline persists received messages, corrects timestamps, dedups by content, resolves the
 * channel by slot, drops blocked senders, updates unreads honoring the active conversation, and
 * broadcasts a real-time event.
 */
class MessageIngestPipelineTest {
    private val future = INGEST_NOW_SECONDS.toLong()

    @Suppress("LongParameterList")
    private fun buildPipeline(
        store: IngestStore,
        blocked: BlockedSenders = BlockedSendersCache(),
        broadcaster: IncomingMessageBroadcaster = IncomingMessageBroadcaster(),
        notifier: IngestNotifier = IngestNotifier.NoOp,
        active: ActiveConversation = ActiveConversation.None,
        selfName: String = "",
        ids: SequentialIds = SequentialIds(),
        logger: PersistentLogger? = null,
        reactionHook: ReactionHook = ReactionHook.NoOp,
        onDuplicateChannelPath: suspend (one.meshcore.shared.backup.MessageDto) -> Unit = {},
        onIncomingChannelSaved: suspend (one.meshcore.shared.backup.MessageDto) -> Unit = {},
    ): MessageIngestPipeline = MessageIngestPipeline(
        collaborators = MessageIngestCollaborators(
            radioID = ChatFixtures.RADIO,
            store = store,
            blockedSenders = blocked,
            broadcaster = broadcaster,
            selfNodeName = { selfName },
            now = { INGEST_NOW_SECONDS },
            newMessageId = ids::next,
        ),
        edges = MessageIngestEdges(
            reactionHook = reactionHook,
            notifier = notifier,
            activeConversation = active,
            logger = logger,
            onDuplicateChannelPath = onDuplicateChannelPath,
            onIncomingChannelSaved = onIncomingChannelSaved,
        ),
    )

    @Test
    fun directMessagePersistsAsDeliveredIncoming() = runTest {
        val store = InMemoryIngestStore()
        val pipeline = buildPipeline(store)
        val contact = resolvedContact()

        pipeline.ingest(IncomingMessage.Direct(contactMessage("hi there", future), contact), DeliveryContext.Live)

        val saved = store.savedMessages().single()
        assertEquals("hi there", saved.text)
        assertEquals(MessageDirection.INCOMING, saved.direction)
        assertEquals(MessageStatus.DELIVERED, saved.status)
        assertEquals(contact.id, saved.contactID)
        assertNull(saved.channelIndex)
        assertEquals(INGEST_NOW_SECONDS, saved.createdAt)
        assertEquals(INGEST_NOW_SECONDS, saved.sortDate)
        assertEquals(INGEST_NOW_SECONDS.toLong(), store.contactHeardAt(contact.id))
    }

    @Test
    fun inboundDirectReactionStampsLastHeardWithoutPersisting() = runTest {
        val store = InMemoryIngestStore()
        val pipeline = buildPipeline(
            store,
            reactionHook = object : ReactionHook by ReactionHook.NoOp {
                override suspend fun handleDirectReaction(text: String, contact: ResolvedContact, radioID: Uuid) = true
            },
        )
        val contact = resolvedContact()
        pipeline.ingest(IncomingMessage.Direct(contactMessage("r:deadbeef:01", future), contact), DeliveryContext.Live)
        assertTrue(store.savedMessages().isEmpty())
        assertEquals(INGEST_NOW_SECONDS.toLong(), store.contactHeardAt(contact.id))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun duplicateMessageIsDropped() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryIngestStore()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val pipeline = buildPipeline(
            store,
            logger = PersistentLogger(scope = this, category = "SyncCoordinator", record = { recorded += it }),
        )
        val contact = resolvedContact()
        val frame = contactMessage("same content", future)

        pipeline.ingest(IncomingMessage.Direct(frame, contact), DeliveryContext.Live)
        pipeline.ingest(IncomingMessage.Direct(frame, contact), DeliveryContext.Live)

        assertEquals(1, store.savedMessages().size)
        val line = recorded.single { it.message == "Skipping duplicate direct message" }
        assertEquals(DebugLogLevel.INFO, line.level)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun unknownTextTypeClampsToPlainAndRecordsTheIosDump() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryIngestStore()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val pipeline = buildPipeline(
            store,
            logger = PersistentLogger(scope = this, category = "SyncCoordinator", record = { recorded += it }),
        )

        pipeline.ingest(
            IncomingMessage.Direct(
                contactMessage("odd type", future, textType = 99),
                resolvedContact(),
            ),
            DeliveryContext.Live,
        )

        assertEquals(TextType.PLAIN, store.savedMessages().single().textType)
        val line = recorded.single { it.message.contains("Unknown direct message textType") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("Unknown direct message textType raw=99; clamping to .plain", line.message)
    }

    @Test
    fun invalidFutureTimestampIsCorrectedAndOriginalPreserved() = runTest {
        val store = InMemoryIngestStore()
        val pipeline = buildPipeline(store)
        val skewed = future + 6 * 60 // 6 minutes ahead exceeds the 5-minute future tolerance

        pipeline.ingest(
            IncomingMessage.Direct(contactMessage("skewed", skewed), resolvedContact()),
            DeliveryContext.Live,
        )

        val saved = store.savedMessages().single()
        assertTrue(saved.timestampCorrected)
        assertEquals(future, saved.timestamp)
        assertEquals(skewed, saved.senderTimestamp)
    }

    @Test
    fun validTimestampIsNotCorrected() = runTest {
        val store = InMemoryIngestStore()
        val pipeline = buildPipeline(store)
        val onTime = future - 60

        pipeline.ingest(
            IncomingMessage.Direct(contactMessage("on time", onTime), resolvedContact()),
            DeliveryContext.Live,
        )

        val saved = store.savedMessages().single()
        assertFalse(saved.timestampCorrected)
        assertEquals(onTime, saved.timestamp)
        assertNull(saved.senderTimestamp)
    }

    @Test
    fun channelResolvesBySlotAndPostsNotification() = runTest {
        val store = InMemoryIngestStore()
        val notifier = RecordingNotifier()
        val broadcaster = IncomingMessageBroadcaster()
        val pipeline = buildPipeline(store, notifier = notifier, broadcaster = broadcaster)
        val channel = resolvedChannel(index = 3)
        val received = broadcaster.subscribe()

        pipeline.ingest(
            IncomingMessage.Channel(channelMessageFrame("Bob: hello all", future, channelIndex = 3), channel),
            DeliveryContext.Live,
        )

        val saved = store.savedMessages().single()
        assertEquals(3, saved.channelIndex)
        assertEquals("Bob", saved.senderNodeName)
        assertEquals("hello all", saved.text)
        assertEquals(1, store.channelUnreadCount(channel.id))
        assertEquals(listOf(3), notifier.channelPosts)
        assertTrue(notifier.unresolvedChannels.isEmpty())
        val event = assertIs<IncomingMessageEvent.ChannelMessageReceived>(received.tryReceive().getOrNull())
        assertEquals(3, event.channelIndex)
    }

    @Test
    fun phantomChannelSuppressesNotificationButPersistsAndBroadcasts() = runTest {
        val store = InMemoryIngestStore()
        val notifier = RecordingNotifier()
        val broadcaster = IncomingMessageBroadcaster()
        val pipeline = buildPipeline(store, notifier = notifier, broadcaster = broadcaster)
        val received = broadcaster.subscribe()

        pipeline.ingest(
            IncomingMessage.Channel(channelMessageFrame("Bob: ghost", future, channelIndex = 7), channel = null),
            DeliveryContext.Live,
        )

        assertEquals(1, store.savedMessages().size)
        assertTrue(notifier.channelPosts.isEmpty())
        assertEquals(listOf(7), notifier.unresolvedChannels)
        assertIs<IncomingMessageEvent.ChannelMessageReceived>(received.tryReceive().getOrNull())
    }

    @Test
    fun blockedChannelSenderIsDroppedBeforeSave() = runTest {
        val store = InMemoryIngestStore()
        val broadcaster = IncomingMessageBroadcaster()
        val blocked = BlockedSendersCache().apply { refresh(listOf("Mallory")) }
        val pipeline = buildPipeline(store, blocked = blocked, broadcaster = broadcaster)
        val received = broadcaster.subscribe()

        pipeline.ingest(
            IncomingMessage.Channel(
                channelMessageFrame("Mallory: spam", future, channelIndex = 3),
                resolvedChannel(index = 3),
            ),
            DeliveryContext.Live,
        )

        assertTrue(store.savedMessages().isEmpty())
        assertNull(received.tryReceive().getOrNull())
    }

    @Test
    fun blockedDirectContactStillPersistsButSkipsUnread() = runTest {
        val store = InMemoryIngestStore()
        val notifier = RecordingNotifier()
        val broadcaster = IncomingMessageBroadcaster()
        val pipeline = buildPipeline(store, notifier = notifier, broadcaster = broadcaster)
        val contact = resolvedContact(isBlocked = true)
        val received = broadcaster.subscribe()

        pipeline.ingest(IncomingMessage.Direct(contactMessage("hi", future), contact), DeliveryContext.Live)

        assertEquals(1, store.savedMessages().size)
        assertEquals(0, store.contactUnreadCount(contact.id))
        assertTrue(notifier.directPosts.isEmpty())
        assertIs<IncomingMessageEvent.DirectMessageReceived>(received.tryReceive().getOrNull())
    }

    @Test
    fun directUnreadIncrementsWhenNotViewing() = runTest {
        val store = InMemoryIngestStore()
        val notifier = RecordingNotifier()
        val pipeline = buildPipeline(store, notifier = notifier)
        val contact = resolvedContact()

        pipeline.ingest(IncomingMessage.Direct(contactMessage("hi", future), contact), DeliveryContext.Live)

        assertEquals(1, store.contactUnreadCount(contact.id))
        assertEquals(listOf(contact.id), notifier.directPosts)
        assertEquals(1, notifier.badgeUpdates)
        assertEquals(INGEST_NOW_SECONDS, store.contactLastMessageDate(contact.id))
    }

    @Test
    fun activeConversationSuppressesUnreadButStillPosts() = runTest {
        val store = InMemoryIngestStore()
        val notifier = RecordingNotifier()
        val contact = resolvedContact()
        val pipeline = buildPipeline(
            store,
            notifier = notifier,
            active = FixedActiveConversation(contactID = contact.id),
        )

        pipeline.ingest(IncomingMessage.Direct(contactMessage("hi", future), contact), DeliveryContext.Live)

        assertEquals(0, store.contactUnreadCount(contact.id))
        assertEquals(listOf(contact.id), notifier.directPosts)
    }

    @Test
    fun selfMentionIncrementsMentionUnread() = runTest {
        val store = InMemoryIngestStore()
        val pipeline = buildPipeline(store, selfName = "Me")
        val contact = resolvedContact()

        pipeline.ingest(
            IncomingMessage.Direct(contactMessage("hey @[Me] look", future), contact),
            DeliveryContext.Live,
        )

        assertTrue(store.savedMessages().single().containsSelfMention)
        assertEquals(1, store.contactUnreadMentionCount(contact.id))
    }

    @Test
    fun channelSelfMentionSkippedWhenSenderIsSelf() = runTest {
        val store = InMemoryIngestStore()
        val pipeline = buildPipeline(store, selfName = "Me")

        pipeline.ingest(
            IncomingMessage.Channel(
                channelMessageFrame("Me: @[Me] echo", future, channelIndex = 3),
                resolvedChannel(index = 3),
            ),
            DeliveryContext.Live,
        )

        assertFalse(store.savedMessages().single().containsSelfMention)
    }

    @Test
    fun directMessageBroadcastCarriesSavedRow() = runTest {
        val store = InMemoryIngestStore()
        val broadcaster = IncomingMessageBroadcaster()
        val pipeline = buildPipeline(store, broadcaster = broadcaster)
        val contact = resolvedContact()
        val received = broadcaster.subscribe()

        pipeline.ingest(IncomingMessage.Direct(contactMessage("ping", future), contact), DeliveryContext.Live)

        val event = assertIs<IncomingMessageEvent.DirectMessageReceived>(received.tryReceive().getOrNull())
        assertEquals(contact.id, event.contactID)
        assertEquals("ping", event.message.text)
    }

    @Test
    fun unknownTextTypeClampsToPlain() = runTest {
        val store = InMemoryIngestStore()
        val pipeline = buildPipeline(store)
        val frame = contactMessage("weird", future, textType = 0x7F)

        pipeline.ingest(IncomingMessage.Direct(frame, resolvedContact()), DeliveryContext.Live)

        assertEquals(TextType.PLAIN, store.savedMessages().single().textType)
    }

    @Test
    fun rxLogCorrelationPopulatesPathDataForChannel() = runTest {
        val store = InMemoryIngestStore()
        val correlation = RxLogCorrelation(
            pathNodes = byteArrayOf(9, 8, 7),
            pathLength = 3,
            routeType = RouteType.DIRECT,
            regionScope = "EU",
            regionScopeMatches = listOf("EU"),
        )
        store.seedRxLog(ChatFixtures.RADIO, channelIndex = 3, senderTimestamp = future, correlation = correlation)
        val pipeline = buildPipeline(store)

        pipeline.ingest(
            IncomingMessage.Channel(
                channelMessageFrame("Bob: routed", future, channelIndex = 3),
                resolvedChannel(index = 3),
            ),
            DeliveryContext.Live,
        )

        val saved = store.savedMessages().single()
        assertEquals(3, saved.pathLength)
        assertContentEquals(byteArrayOf(9, 8, 7), saved.pathNodes)
        assertEquals(RouteType.DIRECT, saved.routeType)
        assertEquals("EU", saved.regionScope)
        assertEquals(listOf("EU"), saved.regionScopeMatches)
    }

    @Test
    fun duplicateChannelDeliveryForwardsCorrelatedPathWithoutSavingAnotherMessage() = runTest {
        val store = InMemoryIngestStore()
        val forwarded = mutableListOf<one.meshcore.shared.backup.MessageDto>()
        val pipeline = buildPipeline(store, onDuplicateChannelPath = { forwarded += it })
        val frame = IncomingMessage.Channel(
            channelMessageFrame("Bob: routed", future, channelIndex = 3),
            resolvedChannel(index = 3),
        )
        store.seedRxLog(ChatFixtures.RADIO, 3, future, RxLogCorrelation(byteArrayOf(1), 1, null, null))
        pipeline.ingest(frame, DeliveryContext.Live)
        store.seedRxLog(ChatFixtures.RADIO, 3, future, RxLogCorrelation(byteArrayOf(2), 1, null, null))

        pipeline.ingest(frame, DeliveryContext.Live)

        assertEquals(1, store.savedMessages().size)
        assertEquals(1, forwarded.size)
        assertContentEquals(byteArrayOf(2), forwarded.single().pathNodes)
    }

    @Test
    fun savedChannelMessageTriggersPathHarvestHook() = runTest {
        val store = InMemoryIngestStore()
        val saved = mutableListOf<one.meshcore.shared.backup.MessageDto>()
        val pipeline = buildPipeline(store, onIncomingChannelSaved = { saved += it })

        pipeline.ingest(
            IncomingMessage.Channel(
                channelMessageFrame("Bob: routed", future, channelIndex = 3),
                resolvedChannel(index = 3),
            ),
            DeliveryContext.Live,
        )

        assertEquals(store.savedMessages().single().id, saved.single().id)
    }

    @Test
    fun rxLogCorrelationCopiesAmbiguousRegionMatchesOntoTheMessage() = runTest {
        val store = InMemoryIngestStore()
        val correlation = RxLogCorrelation(
            pathNodes = byteArrayOf(9, 8, 7),
            pathLength = 3,
            routeType = RouteType.FLOOD,
            regionScope = null,
            regionScopeMatches = listOf("de-by", "de-hh"),
        )
        store.seedRxLog(ChatFixtures.RADIO, channelIndex = 3, senderTimestamp = future, correlation = correlation)
        val pipeline = buildPipeline(store)

        pipeline.ingest(
            IncomingMessage.Channel(
                channelMessageFrame("Bob: routed", future, channelIndex = 3),
                resolvedChannel(index = 3),
            ),
            DeliveryContext.Live,
        )

        val saved = store.savedMessages().single()
        assertNull(saved.regionScope)
        assertEquals(listOf("de-by", "de-hh"), saved.regionScopeMatches)
    }

    @Test
    fun rxLogMissFallsBackToReportedPathLength() = runTest {
        val store = InMemoryIngestStore()
        val pipeline = buildPipeline(store)

        pipeline.ingest(
            IncomingMessage.Direct(contactMessage("no rx", future, pathLength = 5), resolvedContact()),
            DeliveryContext.Live,
        )

        val saved = store.savedMessages().single()
        assertEquals(5, saved.pathLength)
        assertNull(saved.routeType)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun rxLogLookupFaultPersistsTheMessageAndRecordsTheIosDump() = runTest(UnconfinedTestDispatcher()) {
        val backing = InMemoryIngestStore()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = object : IngestStore by backing {
            override suspend fun lookupRxLog(
                radioID: one.meshcore.shared.model.Uuid,
                channelIndex: Int?,
                senderTimestamp: Long,
                senderKeyPrefix: ByteArray?,
                defaultPathLength: Int,
            ): RxLogCorrelation = error("db locked")
        }
        val pipeline = buildPipeline(
            store = store,
            logger = PersistentLogger(
                scope = this,
                category = "SyncCoordinator",
                record = { recorded += it },
            ),
        )

        pipeline.ingest(
            IncomingMessage.Direct(contactMessage("kept", future, pathLength = 4), resolvedContact()),
            DeliveryContext.Live,
        )

        assertEquals(1, backing.savedMessages().size)
        assertEquals(4, backing.savedMessages().single().pathLength)
        val line = recorded.single { it.message.contains("Failed to lookup RxLogEntry for direct message") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("SyncCoordinator", line.category)
        assertTrue(line.message.contains("db locked"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun channelRxLogLookupFaultRecordsTheChannelDump() = runTest(UnconfinedTestDispatcher()) {
        val backing = InMemoryIngestStore()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = object : IngestStore by backing {
            override suspend fun lookupRxLog(
                radioID: one.meshcore.shared.model.Uuid,
                channelIndex: Int?,
                senderTimestamp: Long,
                senderKeyPrefix: ByteArray?,
                defaultPathLength: Int,
            ): RxLogCorrelation = error("db locked")
        }
        val pipeline = buildPipeline(
            store = store,
            logger = PersistentLogger(
                scope = this,
                category = "SyncCoordinator",
                record = { recorded += it },
            ),
        )

        pipeline.ingest(
            IncomingMessage.Channel(
                channelMessageFrame("Bob: kept", future, channelIndex = 2),
                resolvedChannel(index = 2),
            ),
            DeliveryContext.Live,
        )

        assertEquals(1, backing.savedMessages().size)
        val line = recorded.single { it.message.contains("Failed to lookup RxLogEntry for channel message") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("db locked"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun channelRxLogHitRecordsTheIosCorrelateDump() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryIngestStore()
        store.seedChannel(ChatFixtures.RADIO, 2, resolvedChannel(index = 2))
        store.seedRxLog(
            radioID = ChatFixtures.RADIO,
            channelIndex = 2,
            senderTimestamp = future,
            correlation = RxLogCorrelation(
                pathNodes = byteArrayOf(1, 2, 3),
                pathLength = 5,
                routeType = RouteType.DIRECT,
                regionScope = null,
            ),
        )
        val recorded = mutableListOf<DebugLogEntryDto>()
        val pipeline = buildPipeline(
            store,
            logger = PersistentLogger(scope = this, category = "SyncCoordinator", record = { recorded += it }),
        )

        pipeline.ingest(
            IncomingMessage.Channel(
                channelMessageFrame("Bob: kept", future, channelIndex = 2),
                resolvedChannel(index = 2),
            ),
            DeliveryContext.Live,
        )

        val line = recorded.single { it.message == correlatedChannelRxLogLine(pathLength = 5, pathNodeBytes = 3) }
        assertEquals(DebugLogLevel.INFO, line.level)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun channelRxLogMissRecordsTheIosWarningDump() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryIngestStore()
        store.seedChannel(ChatFixtures.RADIO, 2, resolvedChannel(index = 2))
        val recorded = mutableListOf<DebugLogEntryDto>()
        val pipeline = buildPipeline(
            store,
            logger = PersistentLogger(scope = this, category = "SyncCoordinator", record = { recorded += it }),
        )

        pipeline.ingest(
            IncomingMessage.Channel(
                channelMessageFrame("Bob: kept", future, channelIndex = 2),
                resolvedChannel(index = 2),
            ),
            DeliveryContext.Live,
        )

        val line = recorded.single { it.message == noChannelRxLogEntryLine(channelIndex = 2, senderTimestamp = future) }
        assertEquals(DebugLogLevel.WARNING, line.level)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun dedupCheckFaultPersistsTheMessageAndRecordsTheIosDump() = runTest(UnconfinedTestDispatcher()) {
        val backing = InMemoryIngestStore()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = object : IngestStore by backing {
            override suspend fun isDuplicateMessage(deduplicationKey: String, radioID: one.meshcore.shared.model.Uuid) =
                error("db locked")
        }
        val pipeline = buildPipeline(
            store = store,
            logger = PersistentLogger(
                scope = this,
                category = "SyncCoordinator",
                record = { recorded += it },
            ),
        )

        pipeline.ingest(
            IncomingMessage.Direct(contactMessage("kept", future), resolvedContact()),
            DeliveryContext.Live,
        )

        assertEquals(1, backing.savedMessages().size)
        val line = recorded.single { it.message.contains("Dedup check failed, proceeding with save") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("SyncCoordinator", line.category)
        assertTrue(line.message.contains("db locked"))
    }
}
