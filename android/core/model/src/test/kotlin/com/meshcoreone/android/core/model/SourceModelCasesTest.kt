// PortedFrom: MC1Services/Tests/MC1ServicesTests/ContactDTOPathTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/MessageDTOPathTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Models/DeviceDTOClientRepeatTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Models/FirmwareVersionComparisonTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Models/ProtocolLimitsTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/NodeLocationFixTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.lpp.LPPDataPoint
import com.meshcoreone.android.core.protocol.lpp.LPPSensorType
import com.meshcoreone.android.core.protocol.lpp.LPPValue
import com.meshcoreone.android.core.protocol.model.encodePathLen
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.TestFactory

class SourceModelCasesTest {
    @TestFactory fun contactPaths() = sourceCases("ContactDTOPathTests",
        "Single-byte hops chunk into one (data, hex) pair each" to {
            val c = testContact().copy(outPathLength = encodePathLen(1, 3), outPath = Bytes.of(0x1A, 0x2B, 0x3C))
            assertEquals(listOf(Bytes.of(0x1A), Bytes.of(0x2B), Bytes.of(0x3C)), c.pathHops.map { it.data })
            assertEquals(listOf("1A", "2B", "3C"), c.pathHops.map { it.hex })
            assertEquals(listOf("1A", "2B", "3C"), c.pathNodesHex)
        },
        "Multi-byte hops chunk by the encoded hash size" to {
            val c = testContact().copy(outPathLength = encodePathLen(2, 2), outPath = Bytes.of(0x1A, 0x2B, 0x3C, 0x4D))
            assertEquals(listOf(Bytes.of(0x1A, 0x2B), Bytes.of(0x3C, 0x4D)), c.pathHops.map { it.data })
            assertEquals(listOf("1A2B", "3C4D"), c.pathNodesHex)
        },
        "Hops ignore bytes beyond the encoded path length" to {
            assertEquals(listOf("1A", "2B"), testContact().copy(outPathLength = encodePathLen(1, 2), outPath = Bytes.of(0x1A, 0x2B, 255, 255)).pathNodesHex)
        },
        "A flood-routed contact has no hops" to {
            val c = testContact().copy(outPath = Bytes.of(0x1A, 0x2B))
            assertTrue(c.pathHops.isEmpty()); assertTrue(c.pathNodesHex.isEmpty())
        },
        "Displayed hops use the out-path count when a route is set, ignoring inbound" to {
            assertEquals(3L, testContact().copy(outPathLength = encodePathLen(1, 3), outPath = Bytes.of(0x1A, 0x2B, 0x3C)).displayedHopCount(5))
        },
        "A set out-path of zero hops displays zero, not the inbound fallback" to {
            val c = testContact().copy(outPathLength = encodePathLen(1, 0))
            assertFalse(c.isFloodRouted); assertEquals(0L, c.displayedHopCount(5))
        },
        "A flood-routed contact falls back to the inbound advert hops" to { assertEquals(2L, testContact().displayedHopCount(2)) },
        "An inbound zero is a real value, not nil" to { assertEquals(0L, testContact().displayedHopCount(0)) },
        "Flood-routed with no inbound reception has no displayed hops" to { assertNull(testContact().displayedHopCount(null)) },
    )

    @TestFactory fun messagePaths() = sourceCases("MessageDTOPathTests",
        "Single-byte hops chunk into one (data, hex) pair each" to {
            val m = testMessage().copy(pathLength = encodePathLen(1, 3), pathNodes = Bytes.of(0x1A, 0x2B, 0x3C))
            assertEquals(listOf(Bytes.of(0x1A), Bytes.of(0x2B), Bytes.of(0x3C)), m.pathHops.map { it.data })
            assertEquals(listOf("1A", "2B", "3C"), m.pathHops.map { it.hex }); assertEquals(listOf("1A", "2B", "3C"), m.pathNodesHex)
        },
        "Multi-byte hops chunk by the encoded hash size" to {
            val m = testMessage().copy(pathLength = encodePathLen(2, 2), pathNodes = Bytes.of(0x1A, 0x2B, 0x3C, 0x4D))
            assertEquals(listOf(Bytes.of(0x1A, 0x2B), Bytes.of(0x3C, 0x4D)), m.pathHops.map { it.data })
            assertEquals(listOf("1A2B", "3C4D"), m.pathNodesHex)
        },
        "A trailing partial hop is kept as a short final chunk" to {
            assertEquals(listOf("1A2B", "3C"), testMessage().copy(pathLength = encodePathLen(2, 1), pathNodes = Bytes.of(0x1A, 0x2B, 0x3C)).pathNodesHex)
        },
        "A message with no path nodes has no hops" to { assertTrue(testMessage().pathHops.isEmpty()); assertTrue(testMessage().pathNodesHex.isEmpty()) },
    )

    @TestFactory fun clientRepeat() = sourceCases("DeviceDTOClientRepeatTests",
        "helper defaults radioID to id" to { val id = UUID.randomUUID(); assertEquals(id, testDevice(id).id); assertEquals(RadioId(id), testDevice(id).radioId) },
        "supportsClientRepeat returns true for firmware v9" to { assertTrue(testDevice().supportsClientRepeat) },
        "supportsClientRepeat returns false for firmware v8" to { assertFalse(testDevice().copy(firmwareVersion = 8u).supportsClientRepeat) },
        "supportsClientRepeat returns true for firmware v10" to { assertTrue(testDevice().copy(firmwareVersion = 10u).supportsClientRepeat) },
        "sharesLocationPublicly is false for policy none" to {
            val d = testDevice().copy(advertLocationPolicy = 0u); assertFalse(d.sharesLocationPublicly); assertEquals(AdvertLocationPolicy.NONE, d.advertLocationPolicyMode)
        },
        "sharesLocationPublicly is true for policy share" to {
            val d = testDevice().copy(advertLocationPolicy = 1u); assertTrue(d.sharesLocationPublicly); assertEquals(AdvertLocationPolicy.SHARE, d.advertLocationPolicyMode)
        },
        "sharesLocationPublicly is true for policy prefs" to {
            val d = testDevice().copy(advertLocationPolicy = 2u); assertTrue(d.sharesLocationPublicly); assertEquals(AdvertLocationPolicy.PREFS, d.advertLocationPolicyMode)
        },
        "copy mutates only specified fields" to {
            val d = testDevice().copy(preRepeatFrequency = 906_875u); val next = d.copy(clientRepeat = true)
            assertTrue(next.clientRepeat); assertEquals(d.nodeName, next.nodeName); assertEquals(d.frequency, next.frequency); assertEquals(906_875u, next.preRepeatFrequency); assertFalse(d.clientRepeat)
        },
        "copy can set optional fields to nil" to { assertNull(testDevice().copy(preRepeatFrequency = 915_000u).copy(preRepeatFrequency = null).preRepeatFrequency) },
        "copy can update multiple fields at once" to { val d = testDevice(); val n = d.copy(clientRepeat = true, autoAddConfig = 15u); assertTrue(n.clientRepeat); assertEquals(15.toUByte(), n.autoAddConfig); assertEquals(d.nodeName, n.nodeName) },
        "savingPreRepeatSettings copies current radio params" to {
            val n = testDevice().copy(spreadingFactor = 8u).savingPreRepeatSettings()
            assertEquals(915_000u, n.preRepeatFrequency); assertEquals(250_000u, n.preRepeatBandwidth)
            assertEquals(8.toUByte(), n.preRepeatSpreadingFactor); assertEquals(5.toUByte(), n.preRepeatCodingRate)
        },
        "savingPreRepeatSettings preserves other fields" to {
            val d = testDevice().copy(clientRepeat = true); val n = d.savingPreRepeatSettings()
            assertTrue(n.clientRepeat); assertEquals(d.nodeName, n.nodeName); assertEquals(d.firmwareVersion, n.firmwareVersion)
        },
        "clearingPreRepeatSettings sets all preRepeat fields to nil" to {
            val d = testDevice().copy(
                frequency = 915_000u, bandwidth = 250_000u, spreadingFactor = 8u, codingRate = 5u,
                preRepeatFrequency = 915_000u, preRepeatBandwidth = 250_000u,
                preRepeatSpreadingFactor = 8u, preRepeatCodingRate = 5u,
            )
            val n = d.clearingPreRepeatSettings()
            assertNull(n.preRepeatFrequency); assertNull(n.preRepeatBandwidth); assertNull(n.preRepeatSpreadingFactor); assertNull(n.preRepeatCodingRate); assertFalse(n.hasPreRepeatSettings)
            assertEquals(915_000u, n.frequency); assertEquals(250_000u, n.bandwidth)
            assertEquals(8.toUByte(), n.spreadingFactor); assertEquals(5.toUByte(), n.codingRate)
            assertEquals(915_000u, d.preRepeatFrequency); assertEquals(250_000u, d.preRepeatBandwidth)
            assertEquals(8.toUByte(), d.preRepeatSpreadingFactor); assertEquals(5.toUByte(), d.preRepeatCodingRate)
        },
        "hasPreRepeatSettings requires all four fields" to {
            val partial = testDevice().copy(
                frequency = 915_000u, bandwidth = 250_000u, spreadingFactor = 8u, codingRate = 5u,
                preRepeatFrequency = 915_000u,
            )
            assertFalse(partial.hasPreRepeatSettings)
            assertEquals(915_000u, partial.preRepeatFrequency)
            assertNull(partial.preRepeatBandwidth); assertNull(partial.preRepeatSpreadingFactor); assertNull(partial.preRepeatCodingRate)
            val complete = testDevice().copy(
                frequency = 915_000u, bandwidth = 250_000u, spreadingFactor = 8u, codingRate = 5u,
                preRepeatFrequency = 915_000u, preRepeatBandwidth = 250_000u,
                preRepeatSpreadingFactor = 8u, preRepeatCodingRate = 5u,
            )
            assertTrue(complete.hasPreRepeatSettings)
            assertEquals(915_000u, complete.preRepeatFrequency); assertEquals(250_000u, complete.preRepeatBandwidth)
            assertEquals(8.toUByte(), complete.preRepeatSpreadingFactor); assertEquals(5.toUByte(), complete.preRepeatCodingRate)
            assertFalse(complete.copy(preRepeatFrequency = null).hasPreRepeatSettings)
            assertFalse(complete.copy(preRepeatBandwidth = null).hasPreRepeatSettings)
            assertFalse(complete.copy(preRepeatSpreadingFactor = null).hasPreRepeatSettings)
            assertFalse(complete.copy(preRepeatCodingRate = null).hasPreRepeatSettings)
        },
        "hasPreRepeatSettings returns false when all nil" to { assertFalse(testDevice().hasPreRepeatSettings) },
        "updating(from: SelfInfo) carries forward clientRepeat" to { assertTrue(testDevice().copy(clientRepeat = true).updating(testSelfInfo()).clientRepeat) },
        "updating(from: SelfInfo) carries forward preRepeat settings" to {
            val d = testDevice().copy(
                frequency = 915_000u, bandwidth = 250_000u, spreadingFactor = 8u, codingRate = 5u,
                preRepeatFrequency = 915_000u, preRepeatBandwidth = 250_000u,
                preRepeatSpreadingFactor = 8u, preRepeatCodingRate = 5u,
            )
            val n = d.updating(testSelfInfo())
            assertEquals(915_000u, n.preRepeatFrequency); assertEquals(250_000u, n.preRepeatBandwidth)
            assertEquals(8.toUByte(), n.preRepeatSpreadingFactor); assertEquals(5.toUByte(), n.preRepeatCodingRate); assertTrue(n.hasPreRepeatSettings)
            assertEquals(906_875u, n.frequency); assertEquals(250_000u, n.bandwidth)
            assertEquals(11.toUByte(), n.spreadingFactor); assertEquals(8.toUByte(), n.codingRate)
            assertEquals("UpdatedNode", n.nodeName)
            assertEquals(915_000u, d.frequency); assertEquals(250_000u, d.bandwidth)
            assertEquals(8.toUByte(), d.spreadingFactor); assertEquals(5.toUByte(), d.codingRate)
            assertEquals(915_000u, d.preRepeatFrequency); assertEquals(250_000u, d.preRepeatBandwidth)
            assertEquals(8.toUByte(), d.preRepeatSpreadingFactor); assertEquals(5.toUByte(), d.preRepeatCodingRate)
        },
        "updating(from: SelfInfo) updates radio params from SelfInfo" to {
            val n = testDevice().updating(testSelfInfo())
            assertEquals(906_875u, n.frequency); assertEquals(250_000u, n.bandwidth); assertEquals(11.toUByte(), n.spreadingFactor)
            assertEquals(8.toUByte(), n.codingRate); assertEquals("UpdatedNode", n.nodeName)
        },
    )

    @TestFactory fun firmwareVersions() = sourceCases("FirmwareVersionComparisonTests",
        "bare and v-prefixed versions compare on major minor" to {
            listOf("v1.15.0", "1.15", "v1.15", "v1.15.0-abc").forEach { assertTrue(it.isAtLeast(1, 15)) }
            listOf("v1.14.1", "1.14").forEach { assertFalse(it.isAtLeast(1, 15)) }
        },
        "CLI ver banner compares on the first major minor" to {
            assertTrue("MeshCore v1.15.0 (2025-04-18)".isAtLeast(1, 15)); assertTrue("MeshCore v1.15.0 (2025-04-18)".isAtLeast(1, 14))
            assertFalse("MeshCore v1.14.1 (2025-04-18)".isAtLeast(1, 15))
        },
        "unparseable strings are not at least any version" to { listOf("MeshCore", "", "v1").forEach { assertFalse(it.isAtLeast(1, 0)) } },
    )

    @TestFactory fun protocolLimits() = sourceCases("ProtocolLimitsTests",
        "composed cap is the last 9-block size" to {
            assertEquals(139, ProtocolLimits.MAX_CHANNEL_MESSAGE_TOTAL_LENGTH); assertEquals(133L, ProtocolLimits.maxChannelMessageLength(4))
            assertEquals(106L, ProtocolLimits.maxChannelMessageLength(31)); assertEquals(0L, ProtocolLimits.maxChannelMessageLength(200))
        },
        "Region 1-hop at the cap fits a 172-byte companion frame" to {
            val frame = ProtocolLimits.groupTextRxLogFrameByteCount(139, true, 1)
            assertEquals(157L, frame); assertTrue(frame <= 172)
        },
        "Region 1-hop at 147 overflows 172 and still fits 176" to {
            val frame = ProtocolLimits.groupTextRxLogFrameByteCount(147, true, 1)
            assertEquals(173L, frame); assertTrue(frame > 172); assertTrue(frame <= 176)
        },
    )

    private fun gps(lat: Double, lon: Double) = LPPDataPoint(1u, LPPSensorType.GPS, LPPValue.Gps(lat, lon, 0.0))
    @TestFactory fun nodeLocations() = sourceCases("NodeLocationFixTests",
        "Primary fix is the first valid GPS point" to {
            val fix = NodeLocationFix.primaryFix(listOf(LPPDataPoint(1u, LPPSensorType.TEMPERATURE, LPPValue.Float(21.5)), gps(37.7749, -122.4194)))
            assertEquals(37.7749, fix?.latitude); assertEquals(-122.4194, fix?.longitude)
        },
        "A (0,0) fix is dropped" to { assertNull(NodeLocationFix.primaryFix(listOf(gps(0.0, 0.0)))) },
        "An out-of-range fix is dropped" to { assertNull(NodeLocationFix.primaryFix(listOf(gps(999.0, 999.0)))) },
        "No GPS point yields no fix" to { assertNull(NodeLocationFix.primaryFix(listOf(LPPDataPoint(1u, LPPSensorType.TEMPERATURE, LPPValue.Float(21.5))))) },
    )
}
