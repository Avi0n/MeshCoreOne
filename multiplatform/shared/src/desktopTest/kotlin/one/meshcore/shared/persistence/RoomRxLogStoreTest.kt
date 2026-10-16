package one.meshcore.shared.persistence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.meshcore.FakeDecryptKey
import one.meshcore.shared.meshcore.FakeRegionResolver
import one.meshcore.shared.meshcore.FakeRxLogDecryptor
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.tools.DecryptStatus
import one.meshcore.shared.tools.MAX_RX_LOG_ENTRIES
import one.meshcore.shared.tools.RX_LOG_PRUNE_THRESHOLD
import one.meshcore.shared.tools.RxLogDecodeCache
import one.meshcore.shared.tools.RxLogEntry
import one.meshcore.shared.tools.newRxLogEntryId
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Room-backed RX log: entries outlive the store, and the retention cap holds on disk. */
class RoomRxLogStoreTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val otherRadio = Uuid.normalized("B0000000-0000-0000-0000-000000000002")
    private val channelSecret = byteArrayOf(
        0x8B.toByte(), 0x33, 0x87.toByte(), 0xE9.toByte(), 0xC5.toByte(), 0xCD.toByte(), 0xEA.toByte(), 0x6A,
        0xC9.toByte(), 0xE5.toByte(), 0xED.toByte(), 0xBA.toByte(), 0xA1.toByte(), 0x15, 0xCD.toByte(), 0x72,
    )
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-rx-log", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    @Test
    fun entriesSurviveAFreshStoreOverTheSameDatabase() = runBlocking {
        val writer = store()
        writer.append(entry("first", receivedAt = 1))
        writer.append(entry("second", receivedAt = 2))

        // A new store over the same file is what a process restart looks like.
        val reopened = store()
        reopened.hydrate()
        assertEquals(listOf("second", "first"), reopened.list(radio).map { it.packetHash })
        assertEquals(listOf("second", "first"), reopened.snapshot.value.map { it.packetHash })
    }

    @Test
    fun theRetentionCapHoldsOnDiskAcrossAReopen() = runBlocking {
        val keep = 4
        val threshold = 2
        val writer = store(keepCount = keep, pruneThreshold = threshold)
        val total = 20
        repeat(total) { index -> writer.append(entry("h$index", receivedAt = index.toLong())) }

        // The prune is amortized like iOS `pruneRxLogEntries`, so the table stays within one threshold
        // of the cap rather than growing with every packet.
        val stored = db.rxLogEntryDao().countByRadio(radio)
        assertTrue(stored <= keep + threshold, "stored $stored rows, cap is ${keep + threshold}")

        val reopened = store(keepCount = keep, pruneThreshold = threshold)
        reopened.hydrate()
        val listed = reopened.list(radio)
        assertEquals(keep, listed.size)
        // Newest kept, oldest dropped.
        assertEquals("h${total - 1}", listed.first().packetHash)
        assertTrue(listed.none { it.packetHash == "h0" })
    }

    @Test
    fun pruneStartsOnlyAfterTheActualPartitionCountCrossesTheThreshold() = runBlocking {
        val keep = 4
        val threshold = 2
        val writer = store(keepCount = keep, pruneThreshold = threshold)
        repeat(keep + threshold) { index -> writer.append(entry("h$index", receivedAt = index.toLong())) }
        assertEquals(keep + threshold, db.rxLogEntryDao().countByRadio(radio))

        writer.append(entry("next", receivedAt = 7))
        assertEquals(keep, db.rxLogEntryDao().countByRadio(radio))
    }

    @Test
    fun clearRemovesOnlyThatRadioFromDiskAndBuffer() = runBlocking {
        val writer = store()
        writer.append(entry("mine", receivedAt = 1))
        writer.append(entry("theirs", receivedAt = 2, radioID = otherRadio))
        writer.clear(radio)

        assertEquals(0, db.rxLogEntryDao().countByRadio(radio))
        assertEquals(1, db.rxLogEntryDao().countByRadio(otherRadio))

        val reopened = store()
        reopened.hydrate()
        assertTrue(reopened.list(radio).isEmpty())
        assertEquals(listOf("theirs"), reopened.list(otherRadio).map { it.packetHash })
    }

    @Test
    fun hydrateLoadsEachRadioCapNotAGlobalNewestPage() = runBlocking {
        val keep = 4
        val writer = store(keepCount = keep, pruneThreshold = 100)
        repeat(keep) { index ->
            writer.append(entry("a$index", receivedAt = 100 + index.toLong(), radioID = radio))
        }
        repeat(keep) { index ->
            writer.append(entry("b$index", receivedAt = index.toLong(), radioID = otherRadio))
        }

        val reopened = store(keepCount = keep)
        reopened.hydrate()
        assertEquals(keep, reopened.list(radio).size)
        assertEquals(
            keep,
            reopened.list(otherRadio).size,
            "an older radio's rows must survive a newer radio filling the hydrate page",
        )
        assertEquals("b${keep - 1}", reopened.list(otherRadio).first().packetHash)
    }

    @Test
    fun decryptedTextNeverReachesTheTableButComesBackOnReload() = runBlocking {
        val message = "meet at the north gate"
        val payload = byteArrayOf(CHANNEL_HASH_BYTE) + ByteArray(CHANNEL_CIPHER_BYTES) { it.toByte() }
        val live = entry("PLAIN", receivedAt = 3).copy(
            payloadType = GROUP_TEXT_PAYLOAD_TYPE,
            packetPayload = payload,
            packetPayloadSize = payload.size,
            decryptStatus = DecryptStatus.SUCCESS,
            channelIndex = CHANNEL_INDEX,
            channelName = "Ops",
            decodedText = message,
        )
        store().append(live)

        val reopened = store()
        reopened.hydrate()
        assertNull(reopened.list(radio).single().decodedText, "plaintext survived the table")

        reopened.redecrypt(
            RxLogDecodeCache.DecodeSnapshot(
                channelSecrets = mapOf(CHANNEL_INDEX to channelSecret),
                channelNames = mapOf(CHANNEL_INDEX to "Ops"),
                knownRegions = emptyList(),
                decryptor = FakeRxLogDecryptor(listOf(FakeDecryptKey(channelSecret, message, SENDER_TIMESTAMP))),
            ),
        )
        assertEquals(message, reopened.list(radio).single().decodedText)
    }

    @Test
    fun hydrateDecryptsWhenTheSnapshotIsAlreadyKnown() = runBlocking {
        val message = "meet at the north gate"
        val payload = byteArrayOf(CHANNEL_HASH_BYTE) + ByteArray(CHANNEL_CIPHER_BYTES) { it.toByte() }
        store().append(
            entry("PLAIN", receivedAt = 3).copy(
                payloadType = GROUP_TEXT_PAYLOAD_TYPE,
                packetPayload = payload,
                packetPayloadSize = payload.size,
                decryptStatus = DecryptStatus.SUCCESS,
                channelIndex = CHANNEL_INDEX,
                channelName = "Ops",
                decodedText = message,
            ),
        )
        val snapshot = RxLogDecodeCache.DecodeSnapshot(
            channelSecrets = mapOf(CHANNEL_INDEX to channelSecret),
            channelNames = mapOf(CHANNEL_INDEX to "Ops"),
            knownRegions = emptyList(),
            decryptor = FakeRxLogDecryptor(listOf(FakeDecryptKey(channelSecret, message, SENDER_TIMESTAMP))),
        )
        val reopened = store(decodeSnapshot = { snapshot })
        reopened.hydrate()
        assertEquals(message, reopened.list(radio).single().decodedText)
    }

    @Test
    fun theRegionIsBackFilledAndPersistedOnceTheRegionIsKnown() = runBlocking {
        val region = "Germany"
        val payload = byteArrayOf(0x11, 0x22, 0x33)
        store().append(
            entry("REGION", receivedAt = 4).copy(
                payloadType = GROUP_TEXT_PAYLOAD_TYPE,
                payloadTypeBits = GROUP_TEXT_PAYLOAD_TYPE,
                packetPayload = payload,
                transportCode = byteArrayOf((REGION_CODE and 0xFF).toByte(), ((REGION_CODE shr 8) and 0xFF).toByte()),
            ),
        )

        val reopened = store()
        reopened.hydrate()
        assertNull(reopened.list(radio).single().regionScope)
        reopened.redecrypt(
            RxLogDecodeCache.DecodeSnapshot(
                channelSecrets = emptyMap(),
                channelNames = emptyMap(),
                knownRegions = listOf(region),
                decryptor = FakeRxLogDecryptor(),
                regionResolver = FakeRegionResolver(mapOf(REGION_CODE to region)),
            ),
        )

        // The resolved region is durable, unlike the plaintext.
        val afterRestart = store()
        afterRestart.hydrate()
        assertEquals(region, afterRestart.list(radio).single().regionScope)
    }

    @Test
    fun everyDisplayedFieldRoundTripsThroughTheTable() = runBlocking {
        val original = entry("FIELDS", receivedAt = 7).copy(
            snr = 2.5,
            rssi = -90,
            routeType = 1,
            payloadType = 5,
            pathLength = 3,
            packetPayloadSize = 2,
            decryptStatus = DecryptStatus.SUCCESS,
            regionScope = "EU",
            channelIndex = 4,
            channelName = "Rally",
            senderTimestamp = 1_700_000_000,
            rawPayload = byteArrayOf(1, 2, 3),
            packetPayload = byteArrayOf(9, 9),
            pathNodes = byteArrayOf(0x0A, 0x0B),
            payloadVersion = 1,
            transportCode = byteArrayOf(0x2C, 0x01),
            payloadTypeBits = 5,
        )
        store().append(original)

        val reopened = store()
        reopened.hydrate()
        val restored = reopened.list(radio).single()
        assertEquals(original.snr, restored.snr)
        assertEquals(original.rssi, restored.rssi)
        assertEquals(original.routeType, restored.routeType)
        assertEquals(original.payloadType, restored.payloadType)
        assertEquals(original.payloadVersion, restored.payloadVersion)
        assertEquals(original.pathLength, restored.pathLength)
        assertEquals(original.packetPayloadSize, restored.packetPayloadSize)
        assertEquals(original.decryptStatus, restored.decryptStatus)
        assertEquals(original.regionScope, restored.regionScope)
        assertEquals(original.channelIndex, restored.channelIndex)
        assertEquals(original.channelName, restored.channelName)
        assertEquals(original.senderTimestamp, restored.senderTimestamp)
        assertEquals(original.rawPayload.toList(), restored.rawPayload.toList())
        assertEquals(original.packetPayload.toList(), restored.packetPayload.toList())
        assertEquals(original.pathNodes.toList(), restored.pathNodes.toList())
        // The region resolver replays the firmware HMAC input from these two on back-fill.
        assertEquals(original.transportCode?.toList(), restored.transportCode?.toList())
        assertEquals(original.payloadTypeBits, restored.payloadTypeBits)
    }

    private fun store(
        keepCount: Int = MAX_RX_LOG_ENTRIES,
        pruneThreshold: Int = RX_LOG_PRUNE_THRESHOLD,
        decodeSnapshot: suspend () -> RxLogDecodeCache.DecodeSnapshot? = { null },
    ) = RoomRxLogStore.fromDatabase(
        RoomRxLogStore.Args(
            database = db,
            scope = scope,
            keepCount = keepCount,
            pruneThreshold = pruneThreshold,
            decodeSnapshot = decodeSnapshot,
        ),
    )

    private fun entry(hash: String, receivedAt: Long, radioID: Uuid = radio): RxLogEntry = RxLogEntry(
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
    )

    private companion object {
        const val GROUP_TEXT_PAYLOAD_TYPE = 5

        /** An arbitrary `transport_codes[0]`; the region it names is the resolver's answer, not a hash. */
        const val REGION_CODE = 0x7C2B
        const val CHANNEL_INDEX = 2
        const val CHANNEL_HASH_BYTE: Byte = 0xAB.toByte()

        /** Two MAC bytes plus one AES block: the shortest payload a group message can occupy. */
        const val CHANNEL_CIPHER_BYTES = 18
        const val SENDER_TIMESTAMP = 1_700_000_222L
    }
}
