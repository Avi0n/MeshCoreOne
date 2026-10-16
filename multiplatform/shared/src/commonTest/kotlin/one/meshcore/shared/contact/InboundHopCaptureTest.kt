package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.ParsedRxLogData
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InboundHopCaptureTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val keyA = ByteArray(PUBLIC_KEY_SIZE) { 0x11 }

    // MARK: parseInboundAdvertHop

    @Test
    fun parsesFloodAdvertHopAndTimestamp() {
        val hop = parseInboundAdvertHop(advertRxLog(keyA, pathLengthByte = HOPS_3, advertTimestamp = 1_700L))
        assertTrue(hop != null)
        assertTrue(hop.publicKey.contentEquals(keyA))
        assertEquals(3, hop.hopCount)
        assertEquals(1_700L, hop.advertTimestamp)
    }

    @Test
    fun parsesFloodAdvertWithoutTimestampWhenPayloadShort() {
        // Only the public key, no trailing timestamp.
        val data = advertRxLog(keyA, pathLengthByte = HOPS_3, advertTimestamp = null)
        val hop = parseInboundAdvertHop(data)
        assertTrue(hop != null)
        assertEquals(3, hop.hopCount)
        assertNull(hop.advertTimestamp)
    }

    @Test
    fun ignoresNonAdvertPayload() {
        val data = advertRxLog(keyA, pathLengthByte = HOPS_3, advertTimestamp = 1L).copy(payloadType = TEXT_PAYLOAD)
        assertNull(parseInboundAdvertHop(data))
    }

    @Test
    fun ignoresDirectRoutedAdvert() {
        // A direct-routed advert's path length is the remaining route, not hops traversed.
        val data = advertRxLog(keyA, pathLengthByte = HOPS_3, advertTimestamp = 1L).copy(routeType = ROUTE_DIRECT)
        assertNull(parseInboundAdvertHop(data))
    }

    @Test
    fun ignoresReservedPathEncoding() {
        // Hash mode 3 (top two bits set) is reserved and decodes to null.
        assertNull(parseInboundAdvertHop(advertRxLog(keyA, pathLengthByte = RESERVED_MODE_BYTE, advertTimestamp = 1L)))
    }

    @Test
    fun ignoresTruncatedPublicKey() {
        val data = advertRxLog(keyA, pathLengthByte = HOPS_3, advertTimestamp = null)
            .copy(packetPayload = ByteArray(PUBLIC_KEY_SIZE - 1) { 0x11 })
        assertNull(parseInboundAdvertHop(data))
    }

    // MARK: adoptInboundHop

    @Test
    fun adoptsWhenNoStoredTimestamp() {
        val adopted = adoptInboundHop(
            storedHops = null,
            storedTimestamp = null,
            incomingHops = 4,
            incomingTimestamp = 9L,
        )
        assertEquals(AdoptedInboundHop(4, 9L), adopted)
    }

    @Test
    fun adoptsNewerTimestampRegardlessOfHopChange() {
        val adopted = adoptInboundHop(storedHops = 1, storedTimestamp = 5L, incomingHops = 6, incomingTimestamp = 8L)
        assertEquals(AdoptedInboundHop(6, 8L), adopted)
    }

    @Test
    fun adoptsCloserCopyOfSameBroadcast() {
        val adopted = adoptInboundHop(storedHops = 4, storedTimestamp = 5L, incomingHops = 2, incomingTimestamp = 5L)
        assertEquals(AdoptedInboundHop(2, 5L), adopted)
    }

    @Test
    fun rejectsFartherCopyOfSameBroadcast() {
        assertNull(adoptInboundHop(storedHops = 2, storedTimestamp = 5L, incomingHops = 4, incomingTimestamp = 5L))
    }

    @Test
    fun rejectsUngroupedReadOnceTimestampStored() {
        assertNull(adoptInboundHop(storedHops = 2, storedTimestamp = 5L, incomingHops = 1, incomingTimestamp = null))
    }

    // MARK: store integration

    @Test
    fun stampsInboundHopOnExistingRow() = runTest {
        val store = InMemoryDiscoveredNodeStore()
        store.upsert(row(keyA, name = "Node"))
        store.setInboundHopCount(radio, keyA, hopCount = 3, advertTimestamp = 100L)
        val listed = store.list(radio).single()
        assertEquals(3, listed.inboundHopCount)
        assertEquals(100L, listed.inboundHopAdvertTimestamp)
    }

    @Test
    fun buffersHopBeforeRowExistsThenDrainsOnUpsert() = runTest {
        // Firmware emits the ADVERT RX packet before the advert push that creates the row.
        val store = InMemoryDiscoveredNodeStore()
        store.setInboundHopCount(radio, keyA, hopCount = 2, advertTimestamp = 50L)
        assertTrue(store.list(radio).isEmpty())
        store.upsert(row(keyA, name = "Node"))
        val listed = store.list(radio).single()
        assertEquals(2, listed.inboundHopCount)
        assertEquals(50L, listed.inboundHopAdvertTimestamp)
    }

    @Test
    fun upsertPreservesExistingInboundHop() = runTest {
        val store = InMemoryDiscoveredNodeStore()
        store.upsert(row(keyA, name = "Node"))
        store.setInboundHopCount(radio, keyA, hopCount = 3, advertTimestamp = 100L)
        // A later advert re-upsert (which never carries inbound hops) must not erase the stamp.
        store.upsert(row(keyA, name = "Node-renamed"))
        val listed = store.list(radio).single()
        assertEquals("Node-renamed", listed.name)
        assertEquals(3, listed.inboundHopCount)
        assertEquals(100L, listed.inboundHopAdvertTimestamp)
    }

    @Test
    fun ingestRxLogDataStampsHop() = runTest {
        val store = InMemoryDiscoveredNodeStore()
        store.upsert(row(keyA, name = "Node"))
        val result = ingestDiscoveredNodeEvent(
            event = MeshEvent.RxLogData(advertRxLog(keyA, pathLengthByte = HOPS_3, advertTimestamp = 100L)),
            radioID = radio,
            deps = DiscoveredNodeIngestDeps(store = store, lookupLocalContact = { null }, nowEpochSeconds = { 1L }),
        )
        assertIs<DiscoveredNodeIngestResult.InboundHopStamped>(result)
        assertEquals(3, store.list(radio).single().inboundHopCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun ingestRxLogDataHopPersistFaultRecordsIosErrorAndContinues() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
            override suspend fun setInboundHopCount(
                radioID: Uuid,
                publicKey: ByteArray,
                hopCount: Int,
                advertTimestamp: Long?,
            ) {
                error("db locked")
            }
        }
        val result = ingestDiscoveredNodeEvent(
            event = MeshEvent.RxLogData(advertRxLog(keyA, pathLengthByte = HOPS_3, advertTimestamp = 100L)),
            radioID = radio,
            deps = DiscoveredNodeIngestDeps(
                store = store,
                lookupLocalContact = { null },
                nowEpochSeconds = { 1L },
                logger = PersistentLogger(
                    scope = this,
                    category = "RxLogService",
                    record = { recorded += it },
                ),
            ),
        )
        assertIs<DiscoveredNodeIngestResult.Ignored>(result)
        val line = recorded.single { it.message.contains("Failed to stamp inbound hop count") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("RxLogService", line.category)
        assertTrue(line.message.contains("db locked"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCancelledHopStampPropagatesWithoutAnErrorLog() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val cancelled = CancellationException("superseded")
        val store = object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
            override suspend fun setInboundHopCount(
                radioID: Uuid,
                publicKey: ByteArray,
                hopCount: Int,
                advertTimestamp: Long?,
            ) = throw cancelled
        }
        val thrown = assertFailsWith<CancellationException> {
            ingestDiscoveredNodeEvent(
                event = MeshEvent.RxLogData(advertRxLog(keyA, pathLengthByte = HOPS_3, advertTimestamp = 100L)),
                radioID = radio,
                deps = DiscoveredNodeIngestDeps(
                    store = store,
                    lookupLocalContact = { null },
                    nowEpochSeconds = { 1L },
                    logger = PersistentLogger(this, category = "RxLogService", record = { recorded += it }),
                ),
            )
        }
        assertSame(cancelled, thrown)
        assertTrue(recorded.none { it.message.contains("Failed to stamp inbound hop count") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aHopStampErrorPropagatesWithoutAnErrorLog() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val fault = HopStampProbeError()
        val store = object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
            override suspend fun setInboundHopCount(
                radioID: Uuid,
                publicKey: ByteArray,
                hopCount: Int,
                advertTimestamp: Long?,
            ) = throw fault
        }
        val thrown = assertFailsWith<HopStampProbeError> {
            ingestDiscoveredNodeEvent(
                event = MeshEvent.RxLogData(advertRxLog(keyA, pathLengthByte = HOPS_3, advertTimestamp = 100L)),
                radioID = radio,
                deps = DiscoveredNodeIngestDeps(
                    store = store,
                    lookupLocalContact = { null },
                    nowEpochSeconds = { 1L },
                    logger = PersistentLogger(this, category = "RxLogService", record = { recorded += it }),
                ),
            )
        }
        assertSame(fault, thrown)
        assertTrue(recorded.none { it.message.contains("Failed to stamp inbound hop count") })
    }

    private fun row(publicKey: ByteArray, name: String): DiscoveredNodeRecord = DiscoveredNodeRecord(
        id = newDiscoveredNodeId(),
        radioID = radio,
        publicKey = publicKey,
        name = name,
        typeRawValue = ContactType.CHAT.raw,
        lastHeardEpochSeconds = 1_700_000_000L,
    )

    private fun advertRxLog(publicKey: ByteArray, pathLengthByte: Int, advertTimestamp: Long?): ParsedRxLogData {
        val payload = if (advertTimestamp == null) {
            publicKey.copyOf()
        } else {
            publicKey.copyOf() + uInt32LE(advertTimestamp)
        }
        return ParsedRxLogData(
            snr = 1.0,
            rssi = -80,
            rawPayload = byteArrayOf(0),
            routeType = ROUTE_FLOOD,
            payloadType = ADVERT_PAYLOAD,
            payloadVersion = 0,
            payloadTypeBits = ADVERT_PAYLOAD,
            pathLength = pathLengthByte,
            pathNodes = emptyList(),
            packetPayload = payload,
            packetHash = "AABBCCDD",
        )
    }

    private fun uInt32LE(value: Long): ByteArray = ByteArray(TIMESTAMP_SIZE) { i ->
        ((value shr (i * BITS_PER_BYTE)) and BYTE_MASK).toByte()
    }

    companion object {
        private const val PUBLIC_KEY_SIZE = 32
        private const val TIMESTAMP_SIZE = 4
        private const val ADVERT_PAYLOAD = 4
        private const val TEXT_PAYLOAD = 2
        private const val ROUTE_FLOOD = 1
        private const val ROUTE_DIRECT = 2
        private const val HOPS_3 = 3
        private const val RESERVED_MODE_BYTE = 0xC0
        private const val BITS_PER_BYTE = 8
        private const val BYTE_MASK = 0xFFL
    }
}

private class HopStampProbeError : Error("probe")
