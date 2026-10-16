package one.meshcore.shared.tools

import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.map.SnrQuality
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.timestampTestFormatter
import one.meshcore.shared.tools.ui.rxLogIsLive
import one.meshcore.shared.tools.ui.rxLogLiveStatusAnnouncement
import one.meshcore.shared.tools.ui.rxLogPayloadTypePreview
import one.meshcore.shared.tools.ui.rxLogRssiVisibleText
import one.meshcore.shared.ui.filledBarCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("LongParameterList") // Test fixture builder mirrors RxLogEntry construction sites.
class RxLogDisplayTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    @Test
    fun groupCountsAndDedupeKeepNewestFirst() {
        val a1 = entry(hash = "AAAA", idSuffix = "1")
        val a2 = entry(hash = "AAAA", idSuffix = "2")
        val b1 = entry(hash = "BBBB", idSuffix = "3")
        val all = listOf(a1, a2, b1)
        assertEquals(mapOf("AAAA" to 2, "BBBB" to 1), rxLogGroupCounts(all))
        val deduped = displayRxLogEntries(all, groupDuplicates = true)
        assertEquals(listOf(a1, b1), deduped)
        assertEquals(all, displayRxLogEntries(all, groupDuplicates = false))
    }

    @Test
    fun pathHashSizeAndHopCountFromEncodedPathLength() {
        val encoded = PathEncoding.encodePathLen(hashSize = 2, hopCount = 3)
        val row = entry(hash = "X", pathLength = encoded)
        assertEquals(2, row.pathHashSize)
        assertEquals(3, row.hopCount)
    }

    @Test
    fun rawPayloadHexIsUppercaseAndByteSeparated() {
        val row = entry(hash = "H", raw = byteArrayOf(0x0A, 0xFF.toByte(), 0x10))
        // The separator is what the row shows and what Copy puts on the clipboard, so it has to match
        // the other platform byte for byte.
        assertEquals("0A FF 10", row.rawPayloadHex())
    }

    @Test
    fun payloadTypeRendersItsWireName() {
        assertEquals("GROUP_TEXT", entry(hash = "T", payloadType = RxLogPayloadType.GROUP_TEXT).payloadTypeName)
        assertEquals("TEXT_MSG", entry(hash = "T", payloadType = RxLogPayloadType.TEXT_MESSAGE).payloadTypeName)
        assertEquals("TRACE", entry(hash = "T", payloadType = RxLogPayloadType.TRACE).payloadTypeName)
        assertEquals("UNKNOWN", entry(hash = "T", payloadType = RESERVED_PAYLOAD_TYPE).payloadTypeName)
    }

    @Test
    fun dmPrefixesAndYouLabel() {
        // packetPayload: [dest][src]...
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0x00, 0x00)
        val row = entry(
            hash = "DM",
            routeType = 2, // DIRECT
            payloadType = RxLogPayloadType.TEXT_MESSAGE,
            packetPayload = payload,
        )
        assertTrue(row.isDirectTextMessage)
        assertEquals(byteArrayOf(0xBB.toByte()).toList(), row.senderPrefix!!.toList())
        assertEquals(byteArrayOf(0xAA.toByte()).toList(), row.recipientPrefix!!.toList())

        val local = byteArrayOf(0xBB.toByte(), 0x11, 0x22)
        assertEquals(
            "You",
            resolveHashLabel(row.senderPrefix!!, local, emptyMap(), youLabel = "You"),
        )
        assertEquals(
            "AA",
            resolveHashLabel(row.recipientPrefix!!, local, emptyMap(), youLabel = "You"),
        )
        assertEquals(
            "Alice",
            resolveHashLabel(
                row.recipientPrefix!!,
                localPublicKeyPrefix = null,
                nodeNames = mapOf("AA" to "Alice"),
                youLabel = "You",
            ),
        )
    }

    @Test
    fun floodIsNotDirectText() {
        val row = entry(
            hash = "F",
            routeType = 1,
            payloadType = RxLogPayloadType.TEXT_MESSAGE,
            packetPayload = byteArrayOf(1, 2),
        )
        assertFalse(row.isDirectTextMessage)
        assertNull(row.senderPrefix)
    }

    @Test
    fun signalBarsMatchQualityBuckets() {
        assertEquals(4, SnrQuality.EXCELLENT.filledBarCount)
        assertEquals(0, SnrQuality.UNKNOWN.filledBarCount)
    }

    @Test
    fun pathHopLabelsReplaceLocalPrefixWithYou() {
        val pathNodes = byteArrayOf(0x11, 0x22)
        val row = entry(
            hash = "P",
            pathLength = PathEncoding.encodePathLen(1, 2),
            pathNodes = pathNodes,
        )
        val labels = row.pathHopLabels(
            localPublicKeyPrefix = byteArrayOf(0x11, 0x99.toByte()),
            youLabel = "You",
        )
        assertEquals(listOf("You", "22"), labels)
    }

    private fun entry(
        hash: String,
        idSuffix: String = "0",
        pathLength: Int = 0,
        routeType: Int = 1,
        payloadType: Int = 0,
        packetPayload: ByteArray = ByteArray(0),
        pathNodes: ByteArray = ByteArray(0),
        raw: ByteArray = ByteArray(0),
    ): RxLogEntry = RxLogEntry(
        id = Uuid.normalized("B0000000-0000-0000-0000-00000000000$idSuffix"),
        radioID = radio,
        receivedAtEpochMs = 0L,
        snr = null,
        rssi = null,
        routeType = routeType,
        payloadType = payloadType,
        pathLength = pathLength,
        packetHash = hash,
        packetPayloadSize = packetPayload.size,
        rawPayload = raw,
        packetPayload = packetPayload,
        pathNodes = pathNodes,
    )

    @Test
    fun regionLabelUsesUniqueNameAmbiguousJoinAndUnresolved() {
        assertEquals("Germany", rxLogRegionLabel("Germany", listOf("Germany"), unresolved = "Unknown"))
        assertEquals("de-by, de-hh", rxLogRegionLabel(null, listOf("de-hh", "de-by"), unresolved = "Unknown"))
        assertEquals("Unknown", rxLogRegionLabel(null, emptyList(), unresolved = "Unknown"))
    }

    @Test
    fun receivedLabelReflectsThePacketArrivalTime() {
        val base = 1_753_600_000_000L
        val label = rxLogReceivedLabel(base, formatter = timestampTestFormatter)
        assertTrue(label.isNotBlank(), "the collapsed row shows when the packet arrived")
        assertNotEquals(
            label,
            rxLogReceivedLabel(base + 90 * 60 * 1000L, formatter = timestampTestFormatter),
            "a packet 90 minutes later reads as a different time",
        )
        assertEquals(
            label,
            rxLogReceivedLabel(base, formatter = timestampTestFormatter),
            "the label is derived from the entry, not from now",
        )
    }

    @Test
    fun aLastRadioStoreIsNotLive() {
        assertFalse(rxLogIsLive(storePresent = true, isRadioReady = false))
        assertFalse(rxLogIsLive(storePresent = false, isRadioReady = true))
        assertTrue(rxLogIsLive(storePresent = true, isRadioReady = true))
    }

    @Test
    fun liveStatusTalkBackJoinsThePillAndPacketCount() {
        assertEquals("Live, 42 packets", rxLogLiveStatusAnnouncement("Live", "42 packets"))
    }

    @Test
    fun receivedLabelSeparatesPacketsWithinTheSameMinute() {
        val base = 1_753_600_000_000L
        assertNotEquals(
            rxLogReceivedLabel(base, formatter = timestampTestFormatter),
            rxLogReceivedLabel(base + 1_000L, formatter = timestampTestFormatter),
            "repeated packets arrive seconds apart, and ordering them is what the log is for",
        )
    }

    @Test
    fun rssiAndPayloadPreviewUseInjectedUnitsAndDropTheMiddot() {
        assertEquals("-70 дБм", rxLogRssiVisibleText(-70, unit = "дБм"))
        assertEquals(
            "GROUP_TEXT v2, 12 bytes",
            rxLogPayloadTypePreview(
                typeName = "GROUP_TEXT",
                version = 2,
                size = 12,
                bytesLabel = "bytes",
            ),
        )
        assertEquals(
            "TRACE, 4 bytes",
            rxLogPayloadTypePreview(
                typeName = "TRACE",
                version = 0,
                size = 4,
                bytesLabel = "bytes",
            ),
        )
    }

    private companion object {
        /** A header nibble the wire defines but `RxLogPayloadType` does not name. */
        const val RESERVED_PAYLOAD_TYPE = 13
    }
}
