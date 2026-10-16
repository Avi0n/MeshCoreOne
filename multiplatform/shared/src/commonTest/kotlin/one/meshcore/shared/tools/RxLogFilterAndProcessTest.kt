package one.meshcore.shared.tools

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.crypto.ed25519PublicToX25519
import one.meshcore.shared.meshcore.FakeDecryptKey
import one.meshcore.shared.meshcore.FakeRegionResolver
import one.meshcore.shared.meshcore.FakeRxLogDecryptor
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.ParsedRxLogData
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.KeyGenerationService
import one.meshcore.shared.settings.PlatformKeypairFromSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RX-log row building. Decryption itself lives below the bridge, so every case here drives a
 * [FakeRxLogDecryptor]: what these tests pin is which rows are submitted, with which keys, and how
 * each outcome lands on the row.
 */
class RxLogFilterAndProcessTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    /** The channel secret of the live-radio vector the bridge fixtures are frozen against. */
    private val secret = byteArrayOf(
        0x8B.toByte(), 0x33, 0x87.toByte(), 0xE9.toByte(), 0xC5.toByte(), 0xCD.toByte(), 0xEA.toByte(), 0x6A,
        0xC9.toByte(), 0xE5.toByte(), 0xED.toByte(), 0xBA.toByte(), 0xA1.toByte(), 0x15, 0xCD.toByte(), 0x72,
    )
    private val plaintext = "Alice: Hello mesh!"
    private val senderTimestamp = 1_703_123_456L

    private fun openingDecryptor() = FakeRxLogDecryptor(
        listOf(FakeDecryptKey(secret, plaintext, senderTimestamp)),
    )

    private fun snapshot(
        channelSecrets: Map<Int, ByteArray> = emptyMap(),
        channelNames: Map<Int, String> = emptyMap(),
        knownRegions: List<String> = emptyList(),
        decryptor: FakeRxLogDecryptor = FakeRxLogDecryptor(),
        regionResolver: FakeRegionResolver = FakeRegionResolver(),
    ) = RxLogDecodeCache.DecodeSnapshot(
        channelSecrets = channelSecrets,
        channelNames = channelNames,
        knownRegions = knownRegions,
        decryptor = decryptor,
        regionResolver = regionResolver,
    )

    /** The header bytes of a transport code, little-endian, as the wire carries them. */
    private fun transportCode(code: Int) = byteArrayOf((code and 0xFF).toByte(), ((code shr 8) and 0xFF).toByte())

    @Test
    fun filterRouteAndDecrypt() {
        val floodOk = entry(route = 1, decrypt = DecryptStatus.SUCCESS)
        val floodFail = entry(route = 0, decrypt = DecryptStatus.NO_MATCHING_KEY)
        val directOk = entry(route = 2, decrypt = DecryptStatus.SUCCESS)
        val directDm = entry(route = 2, decrypt = DecryptStatus.DM_NO_MATCHING_KEY)
        val all = listOf(floodOk, floodFail, directOk, directDm)

        assertEquals(2, filterRxLogEntries(all, RxLogRouteFilter.FLOOD_ONLY, RxLogDecryptFilter.ALL).size)
        assertEquals(2, filterRxLogEntries(all, RxLogRouteFilter.DIRECT_ONLY, RxLogDecryptFilter.ALL).size)
        assertEquals(2, filterRxLogEntries(all, RxLogRouteFilter.ALL, RxLogDecryptFilter.DECRYPTED).size)
        assertEquals(2, filterRxLogEntries(all, RxLogRouteFilter.ALL, RxLogDecryptFilter.FAILED).size)
        assertEquals(
            1,
            filterRxLogEntries(all, RxLogRouteFilter.FLOOD_ONLY, RxLogDecryptFilter.FAILED).size,
        )
    }

    @Test
    fun processTakesGroupTextPlaintextFromTheDecryptor() {
        val decryptor = openingDecryptor()
        val decode = snapshot(
            channelSecrets = mapOf(3 to secret),
            channelNames = mapOf(3 to "Public"),
            decryptor = decryptor,
        )
        val entry = processRxLogEntry(radio, groupTextFrame(), receivedAtEpochMs = 10L, decode = decode)

        assertEquals(1, decryptor.batchCount, "one appended packet must cost one boundary crossing")
        assertEquals(DecryptStatus.SUCCESS, entry.decryptStatus)
        assertEquals(3, entry.channelIndex)
        assertEquals("Public", entry.channelName)
        assertEquals(plaintext, entry.decodedText)
        assertEquals(senderTimestamp, entry.senderTimestamp)
    }

    @Test
    fun processTakesTheRegionFromTheResolver() {
        val region = "Germany"
        val resolver = FakeRegionResolver(mapOf(REGION_CODE to region))
        val entry = processRxLogEntry(
            radio,
            regionFrame(transportCode(REGION_CODE), "REG1"),
            0L,
            snapshot(knownRegions = listOf(region), regionResolver = resolver),
        )

        assertEquals(region, entry.regionScope)
        assertEquals(listOf(region), entry.regionScopeMatches)
        assertEquals(1, resolver.batchCount, "one appended packet must cost one boundary crossing")
        assertEquals(listOf(region), resolver.lastRegionNames, "region names cross, never scope keys")
    }

    @Test
    fun processStaysOffTheBoundaryWithoutATransportCodeOrKnownRegion() {
        val withoutCode = FakeRegionResolver(mapOf(REGION_CODE to "Germany"))
        val noCodeEntry = processRxLogEntry(
            radio,
            regionFrame(transportCode = null, hash = "NOCODE"),
            0L,
            snapshot(knownRegions = listOf("Germany"), regionResolver = withoutCode),
        )
        assertNull(noCodeEntry.regionScope)
        assertEquals(0, withoutCode.batchCount, "a packet with no transport code has nothing to ask")

        val withoutRegions = FakeRegionResolver(mapOf(REGION_CODE to "Germany"))
        val noRegionEntry = processRxLogEntry(
            radio,
            regionFrame(transportCode(REGION_CODE), "NOREGION"),
            0L,
            snapshot(regionResolver = withoutRegions),
        )
        assertNull(noRegionEntry.regionScope)
        assertEquals(0, withoutRegions.batchCount, "with no known regions there is nothing to match")
    }

    @Test
    fun processKeepsAmbiguousMatchesWithoutAUniqueName() {
        val resolver = FakeRegionResolver(
            matchesByCode = mapOf(REGION_CODE to listOf("de-by", "de-hh")),
        )
        val entry = processRxLogEntry(
            radio,
            regionFrame(transportCode(REGION_CODE), "AMBIG"),
            0L,
            snapshot(knownRegions = listOf("de-by", "de-hh"), regionResolver = resolver),
        )
        assertNull(entry.regionScope)
        assertEquals(listOf("de-by", "de-hh"), entry.regionScopeMatches)
    }

    @Test
    fun processNoKeyWhenSecretsMissing() {
        val entry = processRxLogEntry(radio, groupTextFrame(), 0L, snapshot())
        assertEquals(DecryptStatus.NO_MATCHING_KEY, entry.decryptStatus)
        assertNull(entry.decodedText)
    }

    @Test
    fun processDirectMessageStaysOffTheBoundaryWithoutKeyMaterial() {
        val decryptor = FakeRxLogDecryptor()
        val data = directFrame(ByteArray(DM_PAYLOAD_BYTES) { 3 })
        val entry = processRxLogEntry(radio, data, 0L, snapshot(decryptor = decryptor))

        assertEquals(DecryptStatus.DM_NO_MATCHING_KEY, entry.decryptStatus)
        assertEquals(0, decryptor.batchCount, "a DM with no key material has nothing to submit")
    }

    @Test
    fun processTakesDirectMessagePlaintextFromTheDecryptor() = runTest {
        val sender = mintIdentity(base = 11)
        val recipient = mintIdentity(base = 13)
        val senderX = assertNotNull(ed25519PublicToX25519(sender.publicKey))
        val dmText = "DM over mesh"
        val decryptor = FakeRxLogDecryptor(listOf(FakeDecryptKey(senderX, dmText, senderTimestamp)))

        val cache = RxLogDecodeCache(decryptor = decryptor)
        cache.updatePrivateKey(recipient.expandedPrivateKey.copyOf(CURVE25519_SCALAR_BYTES))
        cache.updateContactPublicKeys(
            mapOf((sender.publicKey[0].toInt() and 0xFF) to listOf(sender.publicKey)),
        )

        val payload = byteArrayOf(recipient.publicKey[0], sender.publicKey[0]) + ByteArray(DM_PAYLOAD_BYTES - 2)
        val entry = processRxLogEntry(radio, directFrame(payload), 99L, cache.snapshot())

        assertEquals(DecryptStatus.SUCCESS, entry.decryptStatus)
        assertEquals(dmText, entry.decodedText)
        assertEquals(senderTimestamp, entry.senderTimestamp)
    }

    @Test
    fun redecryptDecryptsAStoredRowOnceTheSecretIsKnown() = runTest {
        val store = InMemoryRxLogStore()
        store.append(processRxLogEntry(radio, groupTextFrame(), 0L, snapshot()))
        assertEquals(DecryptStatus.NO_MATCHING_KEY, store.list(radio).single().decryptStatus)

        val decryptor = openingDecryptor()
        val changed = store.redecrypt(
            snapshot(
                channelSecrets = mapOf(2 to secret),
                channelNames = mapOf(2 to "Ops"),
                decryptor = decryptor,
            ),
        )

        assertEquals(1, changed.size)
        val row = store.list(radio).single()
        assertEquals(DecryptStatus.SUCCESS, row.decryptStatus)
        assertEquals(plaintext, row.decodedText)
        assertEquals(2, row.channelIndex)
        assertEquals("Ops", row.channelName)
    }

    @Test
    fun redecryptSubmitsTheWholeLogInOneBatch() = runTest {
        val store = InMemoryRxLogStore()
        val rows = 5
        repeat(rows) { store.append(processRxLogEntry(radio, groupTextFrame(), it.toLong(), snapshot())) }

        val decryptor = openingDecryptor()
        store.redecrypt(snapshot(channelSecrets = mapOf(1 to secret), decryptor = decryptor))

        assertEquals(1, decryptor.batchCount, "a re-derivation pass must cross the boundary once")
        assertEquals(rows, decryptor.rowCount)
    }

    @Test
    fun redecryptBackFillsTheRegionOnceItIsKnown() = runTest {
        val region = "Germany"
        val store = InMemoryRxLogStore()
        store.append(processRxLogEntry(radio, regionFrame(transportCode(REGION_CODE), "REGLATE"), 0L, snapshot()))
        assertNull(store.list(radio).single().regionScope)

        val resolver = FakeRegionResolver(mapOf(REGION_CODE to region))
        store.redecrypt(snapshot(knownRegions = listOf(region), regionResolver = resolver))

        assertEquals(region, store.list(radio).single().regionScope)
    }

    @Test
    fun regionBackFillSubmitsTheWholeLogInOneBatch() = runTest {
        val region = "Germany"
        val store = InMemoryRxLogStore()
        val rows = 5
        repeat(rows) {
            val frame = regionFrame(transportCode(REGION_CODE), "REG$it")
            store.append(processRxLogEntry(radio, frame, it.toLong(), snapshot()))
        }

        val resolver = FakeRegionResolver(mapOf(REGION_CODE to region))
        store.redecrypt(snapshot(knownRegions = listOf(region), regionResolver = resolver))

        assertEquals(1, resolver.batchCount, "a region back-fill pass must cross the boundary once")
        assertEquals(rows, resolver.rowCount)

        // Transport-coded rows always re-resolve, matching iOS `reprocessRegionEntries`.
        val second = FakeRegionResolver(mapOf(REGION_CODE to region))
        store.redecrypt(snapshot(knownRegions = listOf(region), regionResolver = second))
        assertEquals(1, second.batchCount)
        assertEquals(rows, second.rowCount)
    }

    @Test
    fun redecryptRewritesStickyUniqueToAmbiguousWhenACollidingNameArrives() = runTest {
        val store = InMemoryRxLogStore()
        val unique = FakeRegionResolver(matchesByCode = mapOf(REGION_CODE to listOf("First")))
        store.append(
            processRxLogEntry(
                radio,
                regionFrame(transportCode(REGION_CODE), "REGCOLLIDE"),
                0L,
                snapshot(knownRegions = listOf("First"), regionResolver = unique),
            ),
        )
        assertEquals("First", store.list(radio).single().regionScope)
        assertEquals(listOf("First"), store.list(radio).single().regionScopeMatches)

        val colliding = FakeRegionResolver(
            matchesByCode = mapOf(REGION_CODE to listOf("First", "Second")),
        )
        store.redecrypt(
            snapshot(
                knownRegions = listOf("First", "Second"),
                regionResolver = colliding,
            ),
        )

        val row = store.list(radio).single()
        assertNull(row.regionScope)
        assertEquals(listOf("First", "Second"), row.regionScopeMatches)
    }

    @Test
    fun redecryptClearsAStickyUniqueWhenKnownRegionsEmpty() = runTest {
        val store = InMemoryRxLogStore()
        store.append(
            processRxLogEntry(
                radio,
                regionFrame(transportCode(REGION_CODE), "REGCLEAR"),
                0L,
                snapshot(
                    knownRegions = listOf("Germany"),
                    regionResolver = FakeRegionResolver(mapOf(REGION_CODE to "Germany")),
                ),
            ),
        )
        assertEquals("Germany", store.list(radio).single().regionScope)

        store.redecrypt(snapshot(knownRegions = emptyList()))

        val row = store.list(radio).single()
        assertNull(row.regionScope)
        assertTrue(row.regionScopeMatches.isEmpty())
    }

    @Test
    fun collectorRedecryptsLoggedRowsWhenChannelSecretsArrive() = runTest {
        val store = InMemoryRxLogStore()
        val cache = RxLogDecodeCache(decryptor = openingDecryptor())
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val collector = RxLogEventCollector(
            RxLogCollectorInputs(
                store = store,
                radioId = { radio },
                nowEpochMs = { 0L },
                decodeCache = cache,
            ),
        )
        val job = launch { collector.collect(events) }
        yield()
        events.emit(MeshEvent.RxLogData(groupTextFrame()))
        yield()
        assertEquals(DecryptStatus.NO_MATCHING_KEY, store.list(radio).single().decryptStatus)

        cache.updateChannels(
            listOf(
                ChannelDto(
                    id = Uuid.normalized("C0000000-0000-0000-0000-000000000009"),
                    radioID = radio,
                    index = 4,
                    name = "Ops",
                    secret = secret,
                    isEnabled = true,
                    unreadCount = 0,
                ),
            ),
        )
        yield()
        job.cancel()

        val row = store.list(radio).single()
        assertEquals(DecryptStatus.SUCCESS, row.decryptStatus)
        assertEquals(plaintext, row.decodedText)
    }

    @Test
    fun decodeCacheAcceptsChannelList() = runTest {
        val cache = RxLogDecodeCache(decryptor = FakeRxLogDecryptor())
        cache.updateChannels(
            listOf(
                ChannelDto(
                    id = Uuid.normalized("C0000000-0000-0000-0000-000000000001"),
                    radioID = radio,
                    index = 1,
                    name = "Ops",
                    secret = secret,
                    isEnabled = true,
                    unreadCount = 0,
                ),
            ),
        )
        cache.updateKnownRegions(listOf("Germany", "\$private"))
        val snap = cache.snapshot()
        assertTrue(snap.channelSecrets.containsKey(1))
        assertEquals("Ops", snap.channelNames[1])
        // Names travel verbatim: which of them derive a usable scope key is decided below the bridge.
        assertEquals(listOf("Germany", "\$private"), snap.knownRegions)
    }

    @Test
    fun staleContactKeyPublicationIsRejected() = runTest {
        val cache = RxLogDecodeCache(decryptor = FakeRxLogDecryptor())

        val published = cache.updateContactPublicKeysIf(
            mapOf(1 to listOf(ByteArray(32) { 1 })),
            isCurrent = { false },
        )

        assertFalse(published)
        assertTrue(cache.snapshot().contactX25519ByPrefix.isEmpty())
    }

    /** A TC_FLOOD frame whose region can only be named by the resolver. */
    private fun regionFrame(transportCode: ByteArray?, hash: String): ParsedRxLogData = ParsedRxLogData(
        snr = null,
        rssi = null,
        rawPayload = byteArrayOf(0),
        routeType = 0, // TC_FLOOD
        payloadType = RxLogPayloadType.GROUP_TEXT,
        payloadVersion = 0,
        payloadTypeBits = 5,
        transportCode = transportCode,
        pathLength = 1,
        pathNodes = listOf(1),
        packetPayload = byteArrayOf(0x11, 0x22, 0x33),
        packetHash = hash,
    )

    /** A GROUP_TEXT frame carrying the live-radio vector, channel-hash byte included. */
    private fun groupTextFrame(): ParsedRxLogData = ParsedRxLogData(
        snr = null,
        rssi = null,
        rawPayload = byteArrayOf(0),
        routeType = 1,
        payloadType = RxLogPayloadType.GROUP_TEXT,
        payloadVersion = 0,
        payloadTypeBits = 5,
        pathLength = 0,
        pathNodes = emptyList(),
        packetPayload = byteArrayOf(0xAB.toByte()) + CHANNEL_VECTOR_PAYLOAD,
        packetHash = "LATEKEY",
    )

    private fun directFrame(payload: ByteArray): ParsedRxLogData = ParsedRxLogData(
        snr = null,
        rssi = null,
        rawPayload = byteArrayOf(0),
        routeType = 2, // DIRECT
        payloadType = RxLogPayloadType.TEXT_MESSAGE,
        payloadVersion = 0,
        payloadTypeBits = 2,
        pathLength = 0,
        pathNodes = emptyList(),
        packetPayload = payload,
        packetHash = "DM",
    )

    private fun entry(route: Int, decrypt: DecryptStatus): RxLogEntry = RxLogEntry(
        id = newRxLogEntryId(),
        radioID = radio,
        receivedAtEpochMs = 0L,
        snr = null,
        rssi = null,
        routeType = route,
        payloadType = 0,
        pathLength = 0,
        packetHash = "h",
        packetPayloadSize = 0,
        decryptStatus = decrypt,
    )

    /** A deterministic identity whose public key starts with a usable prefix byte. */
    private fun mintIdentity(base: Int): KeyGenerationService.GeneratedIdentity {
        var attempt = 0
        while (attempt < MINT_ATTEMPTS) {
            val seed = ByteArray(CURVE25519_SCALAR_BYTES) { (base * 31 + attempt + it * 17).toByte() }
            val pair = PlatformKeypairFromSeed.derive(seed)
            val first = pair.publicKey[0]
            if (first != 0.toByte() && first != 0xFF.toByte()) return pair
            attempt++
        }
        error("mintIdentity failed for base=$base")
    }

    private companion object {
        const val CURVE25519_SCALAR_BYTES = 32
        const val MINT_ATTEMPTS = 64

        /** An arbitrary `transport_codes[0]`; what region it names is the resolver's answer, not a hash. */
        const val REGION_CODE = 0x7C2B

        /** A payload long enough to carry a DM header, MAC and one cipher block. */
        const val DM_PAYLOAD_BYTES = 20

        /** The live-radio channel payload the bridge fixtures decrypt to "Alice: Hello mesh!". */
        val CHANNEL_VECTOR_PAYLOAD = byteArrayOf(
            0xA0.toByte(), 0xE6.toByte(), 0x21, 0x55, 0x65, 0xCF.toByte(), 0x05, 0x55,
            0xD9.toByte(), 0xE4.toByte(), 0xA5.toByte(), 0xE4.toByte(), 0x82.toByte(), 0x4D, 0xC7.toByte(), 0x00,
            0x18, 0xA6.toByte(), 0x02, 0x1E, 0x82.toByte(), 0x32, 0xBB.toByte(), 0x05,
            0x09, 0x50, 0x0B, 0xDF.toByte(), 0x22, 0xA9.toByte(), 0x0E, 0x97.toByte(), 0x97.toByte(), 0x8F.toByte(),
        )
    }
}
