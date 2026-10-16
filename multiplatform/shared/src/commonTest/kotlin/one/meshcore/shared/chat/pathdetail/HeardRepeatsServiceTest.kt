package one.meshcore.shared.chat.pathdetail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.DeduplicationKey
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import one.meshcore.shared.tools.DecryptStatus
import one.meshcore.shared.tools.RxLogEntry
import one.meshcore.shared.tools.RxLogPayloadType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Ports the Swift `HeardRepeatsService` matching tests: a repeat far outside the removed 10s window still
 * counts, the same RX-log entry counts once, an unknown timestamp is no match, sender name is not a join
 * key, exact text disambiguates messages sharing a channel and timestamp, and a match broadcasts the new count.
 */
class HeardRepeatsServiceTest {
    private class ProcessRepeatProbeError : Error("probe")
    private class IncomingPathProbeError : Error("probe")

    private val nodeName = "TestNode"
    private val radioID = ChatFixtures.RADIO

    private suspend fun store(vararg messages: MessageDto): InMemoryHeardRepeatStore {
        val store = InMemoryHeardRepeatStore()
        messages.forEach { store.seedMessage(it) }
        return store
    }

    private fun sentChannelMessage(
        text: String,
        timestamp: Long,
        channelIndex: Int,
        id: Uuid = ChatFixtures.freshUuid(),
    ): MessageDto = ChatFixtures.message(
        id = id,
        text = text,
        timestamp = timestamp,
        channelIndex = channelIndex,
    )

    private fun echo(
        body: String,
        timestamp: Long,
        channelIndex: Int,
        senderName: String = nodeName,
        id: Uuid = ChatFixtures.freshUuid(),
    ): HeardRepeatEcho = HeardRepeatEcho(
        id = id,
        isGroupText = true,
        isDecryptSuccess = true,
        decodedText = "$senderName: $body",
        channelIndex = channelIndex,
        senderTimestamp = timestamp,
        receivedAt = 1_700_000_100.0,
        pathNodes = byteArrayOf(0x42),
        pathLength = 0x01,
        snr = 8.0,
        rssi = -70,
    )

    private fun service(store: InMemoryHeardRepeatStore, events: MutableList<HeardRepeatEvent>) = HeardRepeatsService(
        store = store,
        newRepeatId = { ChatFixtures.freshUuid() },
        onRepeat = { events += it },
    )

    /** A configured service whose logger appends to [recorded], so a fault test can read the line. */
    private fun TestScope.loggedService(
        store: HeardRepeatStore,
        recorded: MutableList<DebugLogEntryDto>,
    ): HeardRepeatsService {
        val service = HeardRepeatsService(
            store = store,
            newRepeatId = { ChatFixtures.freshUuid() },
            logger = PersistentLogger(
                scope = this,
                category = "HeardRepeatsService",
                record = { recorded += it },
            ),
        )
        service.configure(radioID = radioID)
        return service
    }

    @Test
    fun fromRxLogEntryMapsGroupTextDecryptAndEpochMs() {
        val id = ChatFixtures.freshUuid()
        val radio = ChatFixtures.RADIO
        val entry = RxLogEntry(
            id = id,
            radioID = radio,
            receivedAtEpochMs = 1_700_000_000_500L,
            snr = 3.0,
            rssi = -75,
            routeType = 1,
            payloadType = RxLogPayloadType.GROUP_TEXT,
            pathLength = 2,
            packetHash = "AA",
            packetPayloadSize = 0,
            decryptStatus = DecryptStatus.SUCCESS,
            channelIndex = 4,
            decodedText = "Node: hi",
            senderTimestamp = 99L,
            pathNodes = byteArrayOf(0x0A, 0x0B),
        )
        val echo = HeardRepeatEcho.fromRxLogEntry(entry)
        assertEquals(id, echo.id)
        assertTrue(echo.isGroupText)
        assertTrue(echo.isDecryptSuccess)
        assertEquals("Node: hi", echo.decodedText)
        assertEquals(4, echo.channelIndex)
        assertEquals(99L, echo.senderTimestamp)
        assertEquals(1_700_000_000.5, echo.receivedAt)
        assertEquals(2, echo.pathLength)
        assertEquals(3.0, echo.snr)
        assertEquals(-75, echo.rssi)

        val plain = HeardRepeatEcho.fromRxLogEntry(
            entry.copy(
                payloadType = RxLogPayloadType.TEXT_MESSAGE,
                decryptStatus = DecryptStatus.NO_MATCHING_KEY,
            ),
        )
        assertFalse(plain.isGroupText)
        assertFalse(plain.isDecryptSuccess)
    }

    @Test
    fun countsRepeatFarOutsideTheOldWindow() = runTest {
        val sendTimestamp = 1_699_999_880L // two minutes before the echo receive time
        val messageID = ChatFixtures.freshUuid()
        val backing = store(sentChannelMessage("north repeater check", sendTimestamp, channelIndex = 2, id = messageID))
        val events = mutableListOf<HeardRepeatEvent>()
        val service = service(backing, events)
        service.configure(radioID = radioID)

        val count = service.processForRepeats(echo("north repeater check", sendTimestamp, channelIndex = 2))

        assertEquals(1, count)
        assertEquals(1, backing.fetchMessageRepeats(messageID).size)
        assertEquals(listOf(messageID), events.map { it.messageID })
        assertEquals(listOf(1), events.map { it.count })
    }

    @Test
    fun sameRxEntryCountedOnce() = runTest {
        val ts = 1_700_000_000L
        val messageID = ChatFixtures.freshUuid()
        val backing = store(sentChannelMessage("hello", ts, channelIndex = 0, id = messageID))
        val service = service(backing, mutableListOf())
        service.configure(radioID = radioID)

        val echo = echo("hello", ts, channelIndex = 0)
        val first = service.processForRepeats(echo)
        val second = service.processForRepeats(echo)

        assertEquals(1, first)
        assertNull(second)
        assertEquals(1, backing.fetchMessageRepeats(messageID).size)
    }

    @Test
    fun incomingDistinctPathCountsOnceAndCanonicalPathDoesNot() = runTest {
        val timestamp = 1_700_000_000L
        val messageID = ChatFixtures.freshUuid()
        val incoming = sentChannelMessage("hello", timestamp, channelIndex = 2, id = messageID).copy(
            direction = MessageDirection.INCOMING,
            senderNodeName = "Alice",
            pathNodes = byteArrayOf(0x11),
            deduplicationKey = DeduplicationKey.contentBased(null, 2, "Alice", timestamp, "hello"),
        )
        val backing = store(incoming)
        val events = mutableListOf<HeardRepeatEvent>()
        val service = service(backing, events)
        service.configure(radioID)

        assertNull(service.processForRepeats(echo("hello", timestamp, 2, "Alice").copyPath(0x11)))
        assertEquals(1, service.processForRepeats(echo("hello", timestamp, 2, "Alice").copyPath(0x22)))
        assertNull(service.processForRepeats(echo("hello", timestamp, 2, "Alice").copyPath(0x22)))
        assertEquals(1, backing.fetchMessageRepeats(messageID).size)
        assertEquals(listOf(HeardRepeatEvent(messageID, 1)), events)
    }

    private fun HeardRepeatEcho.copyPath(path: Int): HeardRepeatEcho = HeardRepeatEcho(
        id = ChatFixtures.freshUuid(),
        isGroupText = isGroupText,
        isDecryptSuccess = isDecryptSuccess,
        decodedText = decodedText,
        channelIndex = channelIndex,
        senderTimestamp = senderTimestamp,
        receivedAt = receivedAt,
        pathNodes = byteArrayOf(path.toByte()),
        pathLength = pathLength,
        snr = snr,
        rssi = rssi,
    )

    @Test
    fun firstObservedPathAdoptsUnknownCanonicalThenLaterPathCounts() = runTest {
        val timestamp = 1_700_000_000L
        val key = DeduplicationKey.contentBased(null, 2, "Alice", timestamp, "hello")
        val message = sentChannelMessage("hello", timestamp, 2).copy(
            direction = MessageDirection.INCOMING,
            senderNodeName = "Alice",
            pathNodes = null,
            deduplicationKey = key,
        )
        val backing = store(message)
        val service = service(backing, mutableListOf())
        service.configure(radioID)

        assertNull(service.processForRepeats(echo("hello", timestamp, 2, "Alice").copyPath(0x11)))
        assertContentEquals(byteArrayOf(0x11), backing.findIncomingChannelMessage(radioID, key)?.pathNodes)
        assertEquals(
            1,
            service.recordIncomingPath(
                key,
                IncomingPathObservation(byteArrayOf(0x22), 1, 8.0, null, 1_700_000_100.0),
            ),
        )
        assertEquals(1, backing.fetchMessageRepeats(message.id).size)
    }

    @Test
    fun harvestOnlyMatchingPreviouslyLoggedIncomingPaths() = runTest {
        val timestamp = 1_700_000_000L
        val message = sentChannelMessage("hello", timestamp, 2).copy(
            direction = MessageDirection.INCOMING,
            senderNodeName = "Alice",
            pathNodes = byteArrayOf(0x11),
            deduplicationKey = DeduplicationKey.contentBased(null, 2, "Alice", timestamp, "hello"),
        )
        val backing = store(message)
        val service = service(backing, mutableListOf())
        service.configure(radioID)

        service.harvestIncomingPaths(
            message,
            listOf(
                echo("hello", timestamp, 2, "Alice").copyPath(0x22),
                echo("different", timestamp, 2, "Alice").copyPath(0x33),
                echo("hello", timestamp, 2, "Alice").copyPath(0x11),
            ),
        )

        assertEquals(1, backing.fetchMessageRepeats(message.id).size)
        assertContentEquals(byteArrayOf(0x22), backing.fetchMessageRepeats(message.id).single().pathNodes)
    }

    @Test
    fun noMatchForWrongTimestamp() = runTest {
        val ts = 1_700_000_000L
        val backing = store(sentChannelMessage("hello", ts, channelIndex = 1))
        val service = service(backing, mutableListOf())
        service.configure(radioID = radioID)

        assertNull(service.processForRepeats(echo("hello", ts + 5, channelIndex = 1)))
    }

    @Test
    fun senderNameIsNotAJoinKey() = runTest {
        val ts = 1_700_000_000L
        val messageID = ChatFixtures.freshUuid()
        val backing = store(sentChannelMessage("hello", ts, channelIndex = 1, id = messageID))
        val service = service(backing, mutableListOf())
        service.configure(radioID = radioID)

        val count = service.processForRepeats(echo("hello", ts, channelIndex = 1, senderName = "SomeoneElse"))
        assertEquals(1, count)
        assertEquals(1, backing.fetchMessageRepeats(messageID).size)
    }

    @Test
    fun exactTextDisambiguatesSharedChannelAndTimestamp() = runTest {
        val ts = 1_700_000_000L
        val aID = ChatFixtures.freshUuid()
        val bID = ChatFixtures.freshUuid()
        val backing = store(
            sentChannelMessage("message A", ts, channelIndex = 3, id = aID),
            sentChannelMessage("message B", ts, channelIndex = 3, id = bID),
        )
        val service = service(backing, mutableListOf())
        service.configure(radioID = radioID)

        service.processForRepeats(echo("message B", ts, channelIndex = 3))

        assertEquals(1, backing.fetchMessageRepeats(bID).size)
        assertEquals(0, backing.fetchMessageRepeats(aID).size)
    }

    @Test
    fun unconfiguredServiceIsANoOp() = runTest {
        val ts = 1_700_000_000L
        val backing = store(sentChannelMessage("hello", ts, channelIndex = 0))
        val service = service(backing, mutableListOf())

        assertNull(service.processForRepeats(echo("hello", ts, channelIndex = 0)))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun persistFaultRecordsFailedToProcessRepeatAndReturnsNull() = runTest(UnconfinedTestDispatcher()) {
        val ts = 1_700_000_000L
        val backing = store(sentChannelMessage("hello", ts, channelIndex = 0))
        val recorded = mutableListOf<DebugLogEntryDto>()
        val throwing = object : HeardRepeatStore by backing {
            override suspend fun saveMessageRepeat(repeat: one.meshcore.shared.backup.MessageRepeatDto) {
                error("db locked")
            }
        }
        val service = loggedService(throwing, recorded)

        assertNull(service.processForRepeats(echo("hello", ts, channelIndex = 0)))
        val line = recorded.single { it.message.contains("Failed to process repeat") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("HeardRepeatsService", line.category)
        assertTrue(line.message.contains("db locked"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCancelledRepeatProcessPropagates() = runTest(UnconfinedTestDispatcher()) {
        val ts = 1_700_000_000L
        val backing = store(sentChannelMessage("hello", ts, channelIndex = 0))
        val recorded = mutableListOf<DebugLogEntryDto>()
        val stopped = CancellationException("stopped")
        val throwing = object : HeardRepeatStore by backing {
            override suspend fun saveMessageRepeat(repeat: one.meshcore.shared.backup.MessageRepeatDto): Unit =
                throw stopped
        }
        val service = loggedService(throwing, recorded)

        val cancelled = assertFailsWith<CancellationException> {
            service.processForRepeats(echo("hello", ts, channelIndex = 0))
        }

        assertSame(stopped, cancelled)
        assertTrue(recorded.none { it.message.contains("Failed to process repeat") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aRepeatProcessErrorPropagates() = runTest(UnconfinedTestDispatcher()) {
        val ts = 1_700_000_000L
        val backing = store(sentChannelMessage("hello", ts, channelIndex = 0))
        val recorded = mutableListOf<DebugLogEntryDto>()
        val probe = ProcessRepeatProbeError()
        val throwing = object : HeardRepeatStore by backing {
            override suspend fun saveMessageRepeat(repeat: one.meshcore.shared.backup.MessageRepeatDto): Unit =
                throw probe
        }
        val service = loggedService(throwing, recorded)

        val thrown = assertFailsWith<ProcessRepeatProbeError> {
            service.processForRepeats(echo("hello", ts, channelIndex = 0))
        }
        assertSame(probe, thrown)
        assertTrue(recorded.none { it.message.contains("Failed to process repeat") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun incomingPathFaultRecordsTheIosDumpAndReturnsNull() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val backing = store()
        val throwing = object : HeardRepeatStore by backing {
            override suspend fun findIncomingChannelMessage(radioID: Uuid, deduplicationKey: String): MessageDto? =
                error("db locked")
        }
        val service = loggedService(throwing, recorded)

        assertNull(
            service.recordIncomingPath(
                "key",
                IncomingPathObservation(byteArrayOf(0x22), 1, 8.0, null, 1_700_000_100.0),
            ),
        )
        val line = recorded.single { it.message.contains("Failed to record incoming path") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("db locked"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCancelledIncomingPathPropagates() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val backing = store()
        val stopped = CancellationException("stopped")
        val throwing = object : HeardRepeatStore by backing {
            override suspend fun findIncomingChannelMessage(radioID: Uuid, deduplicationKey: String): MessageDto? =
                throw stopped
        }
        val service = loggedService(throwing, recorded)

        val cancelled = assertFailsWith<CancellationException> {
            service.recordIncomingPath(
                "key",
                IncomingPathObservation(byteArrayOf(0x22), 1, 8.0, null, 1_700_000_100.0),
            )
        }

        assertSame(stopped, cancelled)
        assertTrue(recorded.none { it.message.contains("Failed to record incoming path") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anIncomingPathErrorPropagates() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val backing = store()
        val probe = IncomingPathProbeError()
        val throwing = object : HeardRepeatStore by backing {
            override suspend fun findIncomingChannelMessage(radioID: Uuid, deduplicationKey: String): MessageDto? =
                throw probe
        }
        val service = loggedService(throwing, recorded)

        val thrown = assertFailsWith<IncomingPathProbeError> {
            service.recordIncomingPath(
                "key",
                IncomingPathObservation(byteArrayOf(0x22), 1, 8.0, null, 1_700_000_100.0),
            )
        }
        assertSame(probe, thrown)
        assertTrue(recorded.none { it.message.contains("Failed to record incoming path") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun duplicateCheckFaultRecordsIosDumpAndTreatsAsDuplicate() = runTest(UnconfinedTestDispatcher()) {
        val ts = 1_700_000_000L
        val backing = store(sentChannelMessage("hello", ts, channelIndex = 0))
        val recorded = mutableListOf<DebugLogEntryDto>()
        val throwing = object : HeardRepeatStore by backing {
            override suspend fun messageRepeatExists(rxLogEntryID: Uuid): Boolean = error("db locked")
        }
        val service = loggedService(throwing, recorded)

        assertNull(service.processForRepeats(echo("hello", ts, channelIndex = 0)))
        val line = recorded.single { it.message.contains("Failed to check for existing repeat") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun processDumpLinesMatchIos() {
        val entryID = ChatFixtures.freshUuid()
        val messageID = ChatFixtures.freshUuid()
        assertEquals(
            "Failed to parse channel message text: no colon here",
            failedToParseChannelMessageTextLine("no colon here"),
        )
        assertEquals(repeatAlreadyRecordedLine(entryID), "Repeat already recorded for RX entry: ${entryID.value}")
        assertEquals(recordedRepeatLine(2, messageID), "Recorded repeat #2 for message ${messageID.value}")
        assertEquals(refreshRepeatsCalledLine(messageID), "refreshRepeats called for messageID: ${messageID.value}")
        assertEquals(refreshRepeatsReturningLine(3), "refreshRepeats returning 3 repeats")
        assertEquals(50, CHANNEL_TEXT_PARSE_LOG_PREFIX)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun parseFailDuplicateAndRecordedRepeatsRecordIosDumps() = runTest(UnconfinedTestDispatcher()) {
        val ts = 1_700_000_000L
        val messageID = ChatFixtures.freshUuid()
        val backing = store(sentChannelMessage("hello", ts, channelIndex = 0, id = messageID))
        val recorded = mutableListOf<DebugLogEntryDto>()
        val service = HeardRepeatsService(
            store = backing,
            newRepeatId = { ChatFixtures.freshUuid() },
            logger = PersistentLogger(
                scope = this,
                category = "HeardRepeatsService",
                record = { recorded += it },
            ),
        )
        service.configure(radioID = radioID)

        val unparsed = echo("hello", ts, channelIndex = 0).let {
            HeardRepeatEcho(
                id = it.id,
                isGroupText = it.isGroupText,
                isDecryptSuccess = it.isDecryptSuccess,
                decodedText = "nocolon",
                channelIndex = it.channelIndex,
                senderTimestamp = it.senderTimestamp,
                receivedAt = it.receivedAt,
                pathNodes = it.pathNodes,
                pathLength = it.pathLength,
                snr = it.snr,
                rssi = it.rssi,
            )
        }
        assertNull(service.processForRepeats(unparsed))
        assertEquals(
            failedToParseChannelMessageTextLine("nocolon"),
            recorded.single { it.message.startsWith("Failed to parse") }.message,
        )

        val echo = echo("hello", ts, channelIndex = 0)
        assertEquals(1, service.processForRepeats(echo))
        assertEquals(
            recordedRepeatLine(1, messageID),
            recorded.single { it.message.startsWith("Recorded repeat") }.message,
        )
        assertNull(service.processForRepeats(echo))
        assertEquals(
            repeatAlreadyRecordedLine(echo.id),
            recorded.single { it.message.startsWith("Repeat already recorded") }.message,
        )

        service.refreshRepeats(messageID)
        assertEquals(
            refreshRepeatsCalledLine(messageID),
            recorded.single { it.message.startsWith("refreshRepeats called") }.message,
        )
        assertEquals(
            refreshRepeatsReturningLine(1),
            recorded.single { it.message.startsWith("refreshRepeats returning") }.message,
        )
    }
}
