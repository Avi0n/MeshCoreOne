package one.meshcore.shared.tools

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.FakeRxLogDecryptor
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.ParsedRxLogData
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RxLogStoreTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    @Test
    fun appendIsNewestFirstAndCapEvicts() = runTest {
        val store = InMemoryRxLogStore(maxEntries = 2)
        store.append(entry("h1", receivedAt = 1))
        store.append(entry("h2", receivedAt = 2))
        store.append(entry("h3", receivedAt = 3))
        val listed = store.list(radio)
        assertEquals(2, listed.size)
        assertEquals("h3", listed[0].packetHash)
        assertEquals("h2", listed[1].packetHash)
    }

    @Test
    fun clearRemovesOnlyThatRadio() = runTest {
        val store = InMemoryRxLogStore()
        val other = Uuid.normalized("B0000000-0000-0000-0000-000000000002")
        store.append(entry("a", radioID = radio))
        store.append(entry("b", radioID = other))
        store.clear(radio)
        assertTrue(store.list(radio).isEmpty())
        assertEquals(1, store.list(other).size)
    }

    @Test
    fun collectorIngestsRxLogData() = runTest {
        val store = InMemoryRxLogStore()
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val collector = RxLogEventCollector(
            RxLogCollectorInputs(
                store = store,
                radioId = { radio },
                nowEpochMs = { 99L },
            ),
        )
        val job = launch { collector.collect(events) }
        kotlinx.coroutines.yield()
        events.emit(
            MeshEvent.RxLogData(
                ParsedRxLogData(
                    snr = 2.5,
                    rssi = -90,
                    rawPayload = byteArrayOf(1),
                    routeType = 1,
                    payloadType = 3,
                    payloadVersion = 0,
                    payloadTypeBits = 0,
                    pathLength = 2,
                    pathNodes = listOf(1, 2),
                    packetPayload = byteArrayOf(9, 9),
                    packetHash = "DEADBEEF",
                ),
            ),
        )
        kotlinx.coroutines.yield()
        job.cancel()
        val row = store.list(radio).single()
        assertEquals("DEADBEEF", row.packetHash)
        assertEquals(2.5, row.snr)
        assertEquals(-90, row.rssi)
        assertEquals(2, row.pathLength)
        assertEquals(99L, row.receivedAtEpochMs)
    }

    @Test
    fun collectorInvokesOnEntryAppendedAfterPersist() = runTest {
        val store = InMemoryRxLogStore()
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val seen = mutableListOf<String>()
        val collector = RxLogEventCollector(
            RxLogCollectorInputs(
                store = store,
                radioId = { radio },
                nowEpochMs = { 50L },
                // A group packet is submitted for decryption, so this test must supply the seam rather
                // than reach for the platform bridge, which no unit-test JVM can load.
                decodeCache = RxLogDecodeCache(decryptor = FakeRxLogDecryptor()),
                onEntryAppended = { entry -> seen += entry.packetHash },
            ),
        )
        val job = launch { collector.collect(events) }
        kotlinx.coroutines.yield()
        events.emit(
            MeshEvent.RxLogData(
                ParsedRxLogData(
                    snr = null,
                    rssi = null,
                    rawPayload = byteArrayOf(),
                    routeType = 0,
                    payloadType = RxLogPayloadType.GROUP_TEXT,
                    payloadVersion = 0,
                    payloadTypeBits = 0,
                    pathLength = 0,
                    pathNodes = emptyList(),
                    packetPayload = byteArrayOf(),
                    packetHash = "CAFEBABE",
                ),
            ),
        )
        kotlinx.coroutines.yield()
        job.cancel()
        assertEquals(listOf("CAFEBABE"), seen)
        assertEquals(1, store.list(radio).size)
    }

    private fun entry(hash: String, radioID: Uuid = radio, receivedAt: Long = 0L): RxLogEntry = RxLogEntry(
        id = newRxLogEntryId(),
        radioID = radioID,
        receivedAtEpochMs = receivedAt,
        snr = null,
        rssi = null,
        routeType = 0,
        payloadType = 0,
        pathLength = 0,
        packetHash = hash,
        packetPayloadSize = 0,
        decryptStatus = DecryptStatus.NOT_APPLICABLE,
    )
}
