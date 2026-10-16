package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.ParsedRxLogData
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The radio-drop dump must carry RxLogService persist lines. iOS writes these through
 * `PersistentLogger(category: "RxLogService")`; these tests pin the same strings on Kotlin.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RxLogEventCollectorPersistentLoggerTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    @Test
    fun reprocessSuccessLineMatchesIos() {
        assertEquals("Successfully re-processed 3 entries", successfullyReprocessedEntriesLine(3))
        assertEquals("Loaded 4 channel secrets from database", loadedChannelSecretsLine(4))
        assertEquals(
            "Loaded 12 contact public key prefixes from database",
            loadedContactPublicKeyPrefixesLine(12),
        )
        assertEquals("Failed to clear RX log entries", FAILED_CLEAR_RX_LOG_ENTRIES)
        assertEquals("Failed to load RX log entries", FAILED_LOAD_RX_LOG_ENTRIES)
    }

    @Test
    fun aClearPersistFaultRecordsTheIosErrorAndDoesNotThrow() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = object : RxLogStore by InMemoryRxLogStore() {
            override suspend fun clear(radioID: Uuid) = error("db locked")
        }
        clearRxLogEntriesGuarded(
            store = store,
            radioID = radio,
            logger = logger(this, recorded),
        )
        val line = recorded.single { it.message.contains(FAILED_CLEAR_RX_LOG_ENTRIES) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("RxLogService", line.category)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun aLoadPersistFaultRecordsTheIosErrorAndReturnsEmpty() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val rows = loadExistingRxLogEntriesGuarded(
            logger = logger(this, recorded),
            load = { error("db locked") },
            empty = { emptyList<String>() },
        )
        assertTrue(rows.isEmpty())
        val line = recorded.single { it.message.contains(FAILED_LOAD_RX_LOG_ENTRIES) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("RxLogService", line.category)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun `an append persist fault records the iOS error and keeps the collector`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<DebugLogEntryDto>()
            val store = FailOnAppendStore(failTimes = 1)
            val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
            val collector = RxLogEventCollector(
                RxLogCollectorInputs(
                    store = store,
                    radioId = { radio },
                    nowEpochMs = { 1L },
                    logger = logger(this, recorded),
                ),
            )
            val job = launch { collector.collect(events) }

            events.emit(rxLogEvent("FIRST"))
            events.emit(rxLogEvent("SECOND"))
            job.cancel()

            val line = recorded.single { it.message.contains("Failed to save RX log entry") }
            assertEquals(DebugLogLevel.ERROR, line.level)
            assertEquals("RxLogService", line.category)
            assertTrue(line.message.contains("db locked"))
            assertEquals(listOf("SECOND"), store.inner.list(radio).map { it.packetHash })
        }

    @Test
    fun `a contact-key persist fault records the iOS error and returns null`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val loaded: List<String>? = loadContactsForRxLogDecrypt(
            fetch = {
                error("db locked")
                emptyList()
            },
            logger = logger(this, recorded),
        )
        assertNull(loaded)
        val line = recorded.single { it.message.contains("Failed to load contact public keys") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("RxLogService", line.category)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun `an empty contact-key load remains a successful result`() = runTest(UnconfinedTestDispatcher()) {
        val loaded: List<String>? = loadContactsForRxLogDecrypt(
            fetch = { emptyList() },
            logger = null,
        )

        assertEquals(emptyList(), loaded)
    }

    @Test
    fun `a private-key export fault records the iOS warning and returns null`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val key = exportPrivateKeyForRxLog(
            export = { error("bridge down") },
            logger = logger(this, recorded),
        )
        assertEquals(null, key)
        val line = recorded.single { it.message.contains(FAILED_EXPORT_PRIVATE_KEY) }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("RxLogService", line.category)
        assertTrue(line.message.contains("bridge down"))
    }

    @Test
    fun `a channel-secret persist fault records the iOS error and returns null`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<DebugLogEntryDto>()
            val loaded: List<String>? = loadChannelsForRxLogDecrypt(
                fetch = {
                    error("db locked")
                    emptyList()
                },
                logger = logger(this, recorded),
            )
            assertEquals(null, loaded)
            val line = recorded.single { it.message.contains(FAILED_LOAD_CHANNEL_SECRETS) }
            assertEquals(DebugLogLevel.ERROR, line.level)
            assertEquals("RxLogService", line.category)
            assertTrue(line.message.contains("db locked"))
        }

    @Test
    fun aSuccessfulChannelSecretLoadRecordsTheIosLoadedLine() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val loaded = loadChannelsForRxLogDecrypt(
            fetch = { listOf("a", "b") },
            logger = logger(this, recorded),
        )
        assertEquals(listOf("a", "b"), loaded)
        val line = recorded.single { it.message == "Loaded 2 channel secrets from database" }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertEquals("RxLogService", line.category)
    }

    @Test
    fun `a redecrypt persist fault records the iOS error and keeps the collector`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<DebugLogEntryDto>()
            val store = FailOnRedecryptStore()
            val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
            val collector = RxLogEventCollector(
                RxLogCollectorInputs(
                    store = store,
                    radioId = { radio },
                    nowEpochMs = { 1L },
                    logger = logger(this, recorded),
                ),
            )
            val job = launch { collector.collect(events) }

            events.emit(rxLogEvent("AFTER"))
            job.cancel()

            val line = recorded.single { it.message.contains("Failed to re-process noMatchingKey entries") }
            assertEquals(DebugLogLevel.ERROR, line.level)
            assertEquals("RxLogService", line.category)
            assertTrue(line.message.contains("db locked"))
            assertEquals(listOf("AFTER"), store.inner.list(radio).map { it.packetHash })
        }

    @Test
    fun `a region back-fill persist fault records the iOS error and does not throw`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<DebugLogEntryDto>()
            val entry = RxLogEntry(
                id = radio,
                radioID = radio,
                receivedAtEpochMs = 1L,
                snr = null,
                rssi = null,
                routeType = 0,
                payloadType = 0,
                pathLength = 0,
                packetHash = "R1",
                packetPayloadSize = 0,
            )
            notifyRowsRederived(
                rows = listOf(entry),
                onRowsRederived = { error("db locked") },
                logger = logger(this, recorded),
            )
            val line = recorded.single { it.message.contains(FAILED_REPROCESS_REGION_ENTRIES) }
            assertEquals(DebugLogLevel.ERROR, line.level)
            assertEquals("RxLogService", line.category)
            assertTrue(line.message.contains("db locked"))
        }

    @Test
    fun aCancelledClearIsRethrown() = runTest {
        val cancelled = CancellationException("stopped")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = object : RxLogStore by InMemoryRxLogStore() {
            override suspend fun clear(radioID: Uuid) = throw cancelled
        }
        val thrown = assertFailsWith<CancellationException> {
            clearRxLogEntriesGuarded(store, radio, logger(this@runTest, recorded))
        }
        assertSame(cancelled, thrown)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun aClearErrorIsLoggedAndDoesNotThrow() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = object : RxLogStore by InMemoryRxLogStore() {
            override suspend fun clear(radioID: Uuid) = throw RxLogPersistProbeError()
        }
        clearRxLogEntriesGuarded(store, radio, logger(this, recorded))
        val line = recorded.single { it.message.contains(FAILED_CLEAR_RX_LOG_ENTRIES) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("probe"))
    }

    @Test
    fun aCancelledRegionBackfillIsRethrown() = runTest {
        val cancelled = CancellationException("stopped")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val thrown = assertFailsWith<CancellationException> {
            notifyRowsRederived(
                rows = listOf(regionEntry()),
                onRowsRederived = { throw cancelled },
                logger = logger(this@runTest, recorded),
            )
        }
        assertSame(cancelled, thrown)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun anAppendSinkErrorIsLoggedAndCollectingContinues() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryRxLogStore()
        var notifications = 0
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val collector = RxLogEventCollector(
            RxLogCollectorInputs(
                store = store,
                radioId = { radio },
                nowEpochMs = { 1L },
                logger = logger(this, recorded),
                onEntryAppended = {
                    notifications += 1
                    if (notifications == 1) throw RxLogPersistProbeError()
                },
            ),
        )
        val job = launch { collector.collect(events) }

        events.emit(rxLogEvent("FIRST"))
        events.emit(rxLogEvent("SECOND"))
        job.cancel()

        val line = recorded.single { it.message.contains(FAILED_NOTIFY_RX_LOG_ENTRY) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("probe"))
        assertEquals(setOf("FIRST", "SECOND"), store.list(radio).map { it.packetHash }.toSet())
        assertEquals(2, notifications)
    }

    private fun regionEntry(): RxLogEntry = RxLogEntry(
        id = radio,
        radioID = radio,
        receivedAtEpochMs = 1L,
        snr = null,
        rssi = null,
        routeType = 0,
        payloadType = 0,
        pathLength = 0,
        packetHash = "R1",
        packetPayloadSize = 0,
    )

    private fun logger(scope: kotlinx.coroutines.CoroutineScope, recorded: MutableList<DebugLogEntryDto>) =
        PersistentLogger(
            scope = scope,
            category = "RxLogService",
            record = { recorded += it },
        )

    private fun rxLogEvent(hash: String): MeshEvent.RxLogData = MeshEvent.RxLogData(
        ParsedRxLogData(
            snr = null,
            rssi = null,
            rawPayload = byteArrayOf(),
            routeType = 0,
            payloadType = 0,
            payloadVersion = 0,
            payloadTypeBits = 0,
            pathLength = 0,
            pathNodes = emptyList(),
            packetPayload = byteArrayOf(),
            packetHash = hash,
        ),
    )

    private class FailOnAppendStore(private val failTimes: Int, val inner: InMemoryRxLogStore = InMemoryRxLogStore()) :
        RxLogStore by inner {
        private var remaining = failTimes

        override suspend fun append(entry: RxLogEntry) {
            if (remaining > 0) {
                remaining -= 1
                error("db locked")
            }
            inner.append(entry)
        }
    }

    private class FailOnRedecryptStore(val inner: InMemoryRxLogStore = InMemoryRxLogStore()) : RxLogStore by inner {
        private var remaining = 1

        override suspend fun redecrypt(decode: RxLogDecodeCache.DecodeSnapshot): List<RxLogEntry> {
            if (remaining > 0) {
                remaining -= 1
                error("db locked")
            }
            return inner.redecrypt(decode)
        }
    }
}

private class RxLogPersistProbeError : Error("probe")
