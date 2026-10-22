// PortedFrom: MeshCore/Tests/MeshCoreTests/RxLogParserTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/RxLogTypesTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/TransportCodeRegionResolverTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.parser

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.ParsedRxLogData
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OriginalRxLogCasesTest {
    private fun rx(data: Bytes, snr: Double? = 5.0, rssi: Long? = -80) = assertNotNull(RxLogParser.parse(snr, rssi, data))

    @TestFactory
    fun parsing() = listOf(
        original("RxLogParserTests", "Parse empty payload returns nil") { assertNull(RxLogParser.parse(5.0, -80, Bytes.EMPTY)) },
        original("RxLogParserTests", "Parse FLOOD GROUP_TEXT packet") {
            val data = rx(hex("1500aabbcc"), 8.0, -85)
            assertEquals(RouteType.FLOOD, data.routeType); assertEquals(PayloadType.GROUP_TEXT, data.payloadType)
            assertEquals(0u.toUByte(), data.payloadVersion); assertNull(data.transportCode)
            assertEquals(0u.toUByte(), data.pathLength); assertEquals(emptyList(), data.pathNodes); assertEquals(hex("aabbcc"), data.packetPayload)
        },
        original("RxLogParserTests", "Parse TC_FLOOD with transport code and path") {
            val data = rx(hex("5401020304023a7fdeadbeef"), null, null)
            assertEquals(RouteType.TC_FLOOD, data.routeType); assertEquals(PayloadType.GROUP_TEXT, data.payloadType)
            assertEquals(1u.toUByte(), data.payloadVersion); assertEquals(hex("01020304"), data.transportCode)
            assertEquals(2u.toUByte(), data.pathLength); assertEquals(hex("3a7f").toList(), data.pathNodes); assertEquals(requestTag, data.packetPayload)
        },
        original("RxLogParserTests", "Parse DIRECT packet (no transport code)") {
            val data = rx(hex("0a01ff4869"), 6.5, -70)
            assertEquals(RouteType.DIRECT, data.routeType); assertEquals(PayloadType.TEXT_MESSAGE, data.payloadType)
            assertNull(data.transportCode); assertEquals(1u.toUByte(), data.pathLength)
            assertEquals(hex("ff").toList(), data.pathNodes); assertEquals(hex("4869"), data.packetPayload)
        },
        original("RxLogParserTests", "Parse TC_DIRECT with transport code") {
            val data = rx(hex("0baabbccdd01424869"), 7.0, -75)
            assertEquals(RouteType.TC_DIRECT, data.routeType); assertEquals(PayloadType.TEXT_MESSAGE, data.payloadType)
            assertEquals(hex("aabbccdd"), data.transportCode); assertEquals(1u.toUByte(), data.pathLength)
            assertEquals(hex("42").toList(), data.pathNodes); assertEquals(hex("4869"), data.packetPayload)
        },
        original("RxLogParserTests", "Parse packet with unknown payload type") { assertEquals(PayloadType.UNKNOWN, rx(hex("39000102"), null, null).payloadType) },
        original("RxLogParserTests", "Parse DIRECT TEXT_MSG extracts sender and recipient pubkey hashes") {
            val data = rx(hex("0a00070a4869abcd")); assertEquals(hex("0a"), data.senderPubkeyPrefix); assertEquals(hex("07"), data.recipientPubkeyPrefix)
        },
        original("RxLogParserTests", "Parse TC_DIRECT TEXT_MSG extracts sender and recipient pubkey hashes") {
            val data = rx(hex("0baabbccdd001234deadbeef")); assertEquals(hex("34"), data.senderPubkeyPrefix); assertEquals(hex("12"), data.recipientPubkeyPrefix)
        },
        original("RxLogParserTests", "Parse DIRECT TEXT_MSG with hashSize > 1 path still extracts 1-byte payload hashes") {
            val data = rx(hex("0a41abcd070adeadbeef")); assertEquals(hex("0a"), data.senderPubkeyPrefix); assertEquals(hex("07"), data.recipientPubkeyPrefix)
        },
        original("RxLogParserTests", "Parse FLOOD GROUP_TEXT has nil sender pubkey prefix") { assertNull(rx(hex("1500aabbcc"), 8.0, -85).senderPubkeyPrefix) },
        original("RxLogParserTests", "MeshEvent.rxLogData holds ParsedRxLogData") {
            val data = ParsedRxLogData(5.0, -80, hex("1500aa"), RouteType.FLOOD, PayloadType.GROUP_TEXT, 0u, 5u, null, 0u, emptyList(), hex("aa"))
            val event = MeshEvent.RxLogData(data)
            assertEquals(RouteType.FLOOD, event.data.routeType); assertEquals(16, event.data.packetHash.length)
        },
        original("RxLogParserTests", "Parse rejects reserved (mode 3) path length encoding") { assertNull(RxLogParser.parse(5.0, -80, hex("0ac1070adead"))) },
        original("RxLogParserTests", "Parse FLOOD TEXT_MSG extracts sender and recipient pubkey hashes") {
            val data = rx(hex("0900070a4869abcd")); assertEquals(RouteType.FLOOD, data.routeType)
            assertEquals(PayloadType.TEXT_MESSAGE, data.payloadType); assertEquals(hex("0a"), data.senderPubkeyPrefix); assertEquals(hex("07"), data.recipientPubkeyPrefix)
        },
    )

    @TestFactory
    fun types() = listOf(
        original("RxLogTypesTests", "RouteType raw values match protocol spec") {
            assertEquals(0u.toUByte(), RouteType.TC_FLOOD.rawValue); assertEquals(1u.toUByte(), RouteType.FLOOD.rawValue)
            assertEquals(2u.toUByte(), RouteType.DIRECT.rawValue); assertEquals(3u.toUByte(), RouteType.TC_DIRECT.rawValue)
        },
        original("RxLogTypesTests", "RouteType hasTransportCode") {
            assertEquals(true, RouteType.TC_FLOOD.hasTransportCode); assertEquals(false, RouteType.FLOOD.hasTransportCode)
            assertEquals(false, RouteType.DIRECT.hasTransportCode); assertEquals(true, RouteType.TC_DIRECT.hasTransportCode)
        },
        original("RxLogTypesTests", "PayloadType raw values match protocol spec") {
            assertEquals(0u.toUByte(), PayloadType.REQUEST.rawValue); assertEquals(5u.toUByte(), PayloadType.GROUP_TEXT.rawValue)
            assertEquals(11u.toUByte(), PayloadType.CONTROL.rawValue); assertEquals(255u.toUByte(), PayloadType.UNKNOWN.rawValue)
        },
        original("RxLogTypesTests", "Reserved PayloadType values 12-14 map to unknown via fromBits") { for (value in 12..14) assertEquals(PayloadType.UNKNOWN, PayloadType.fromBits(value.toUByte())) },
        original("RxLogTypesTests", "PayloadType value 15 maps to rawCustom via fromBits") { assertEquals(PayloadType.RAW_CUSTOM, PayloadType.fromBits(15u)) },
        original("RxLogTypesTests", "PayloadType rawValue initializer returns nil for undefined values") {
            assertNull(PayloadType.fromRawValue(12u)); assertEquals(PayloadType.UNKNOWN, PayloadType.fromRawValue(255u))
        },
        original("RxLogTypesTests", "PayloadType fromBits with valid values") {
            assertEquals(PayloadType.REQUEST, PayloadType.fromBits(0u)); assertEquals(PayloadType.GROUP_TEXT, PayloadType.fromBits(5u)); assertEquals(PayloadType.CONTROL, PayloadType.fromBits(11u))
        },
        original("RxLogTypesTests", "RouteType displayName") {
            assertEquals("TC_FLOOD", RouteType.TC_FLOOD.displayName); assertEquals("FLOOD", RouteType.FLOOD.displayName)
            assertEquals("DIRECT", RouteType.DIRECT.displayName); assertEquals("TC_DIRECT", RouteType.TC_DIRECT.displayName)
        },
        original("RxLogTypesTests", "PayloadType displayName") {
            assertEquals("REQUEST", PayloadType.REQUEST.displayName); assertEquals("GROUP_TEXT", PayloadType.GROUP_TEXT.displayName); assertEquals("UNKNOWN", PayloadType.UNKNOWN.displayName)
        },
        original("RxLogTypesTests", "ParsedRxLogData initializes with all fields") {
            val data = ParsedRxLogData(8.5, -85, hex("010203"), RouteType.FLOOD, PayloadType.GROUP_TEXT, 1u, 5u, null, 2u, hex("3a7f").toList(), hex("aabb"))
            assertEquals(8.5, data.snr); assertEquals(-85L, data.rssi); assertEquals(RouteType.FLOOD, data.routeType)
            assertEquals(PayloadType.GROUP_TEXT, data.payloadType); assertEquals(1u.toUByte(), data.payloadVersion)
            assertNull(data.transportCode); assertEquals(2u.toUByte(), data.pathLength); assertEquals(hex("3a7f").toList(), data.pathNodes); assertEquals(16, data.packetHash.length)
        },
        original("RxLogTypesTests", "ParsedRxLogData packetHash is stable") {
            val first = ParsedRxLogData(null, null, Bytes.EMPTY, RouteType.FLOOD, PayloadType.GROUP_TEXT, 0u, 5u, null, 0u, emptyList(), hex("aabbcc"))
            val second = ParsedRxLogData(5.0, -90, hex("ff"), RouteType.DIRECT, PayloadType.ACK, 2u, 3u, hex("01020304"), 3u, hex("112233").toList(), hex("aabbcc"))
            assertEquals(first.packetHash, second.packetHash); assertEquals("fa22dfe1da9013b3", first.packetHash)
        },
    )

    private val sample = hex("42deadbeef010203")
    private val germany = hex("a5f3117485052a62a83bcd690748091f")
    private fun code(key: Bytes = germany, bits: UByte = 5u) = TransportCodeRegionResolver.calcTransportCode(key, bits, sample)
    private fun match(keys: List<RegionScopeKey>, expected: UShort = 31_787u) =
        TransportCodeRegionResolver.matchRegions(keys, expected, 5u, sample, java.util.Locale.ROOT)

    @TestFactory
    fun transportCodes() = listOf(
        original("TransportCodeRegionResolverTests", "Scope key is SHA-256 of #-prefixed name truncated to 16 bytes") {
            val key = assertNotNull(TransportCodeRegionResolver.deriveScopeKey("Germany")); assertEquals(germany, key); assertEquals(16, key.size)
        },
        original("TransportCodeRegionResolverTests", "Names with and without # produce identical scope keys") {
            assertEquals(assertNotNull(TransportCodeRegionResolver.deriveScopeKey("Germany")), assertNotNull(TransportCodeRegionResolver.deriveScopeKey("#Germany")))
        },
        original("TransportCodeRegionResolverTests", "Whitespace around region name is trimmed before normalization") {
            assertEquals(assertNotNull(TransportCodeRegionResolver.deriveScopeKey("Germany")), assertNotNull(TransportCodeRegionResolver.deriveScopeKey("  Germany  ")))
        },
        original("TransportCodeRegionResolverTests", "Dollar-prefixed (private) region returns nil scope key") {
            assertNull(TransportCodeRegionResolver.deriveScopeKey("\$secret")); assertNull(TransportCodeRegionResolver.deriveScopeKey("\$"))
        },
        original("TransportCodeRegionResolverTests", "Empty or whitespace-only region name returns nil") {
            listOf("", "   ", "\t\n").forEach { assertNull(TransportCodeRegionResolver.deriveScopeKey(it)) }
        },
        original("TransportCodeRegionResolverTests", "Repeated derivation of the same name yields identical keys") {
            assertEquals(assertNotNull(TransportCodeRegionResolver.deriveScopeKey("Bavaria")), assertNotNull(TransportCodeRegionResolver.deriveScopeKey("Bavaria")))
        },
        original("TransportCodeRegionResolverTests", "rewriteReservedCode maps 0 to 1") { assertEquals(1u.toUShort(), TransportCodeRegionResolver.rewriteReservedCode(0u)) },
        original("TransportCodeRegionResolverTests", "rewriteReservedCode maps 0xFFFF to 0xFFFE") { assertEquals(0xfffeu.toUShort(), TransportCodeRegionResolver.rewriteReservedCode(0xffffu)) },
        original("TransportCodeRegionResolverTests", "rewriteReservedCode passes through non-reserved values") {
            listOf(1, 0xfffe, 0x1234, 0x8000).forEach { assertEquals(it.toUShort(), TransportCodeRegionResolver.rewriteReservedCode(it.toUShort())) }
        },
        original("TransportCodeRegionResolverTests", "calcTransportCode reads first two HMAC bytes as little-endian UInt16") { assertEquals(31_787u.toUShort(), code()) },
        original("TransportCodeRegionResolverTests", "calcTransportCode masks payload type bits to low nibble") { assertEquals(code(bits = 5u), code(bits = 0xf5u)) },
        original("TransportCodeRegionResolverTests", "calcTransportCode is deterministic for the same inputs") {
            val key = assertNotNull(TransportCodeRegionResolver.deriveScopeKey("Bavaria")); assertEquals(code(key), code(key))
        },
        original("TransportCodeRegionResolverTests", "Round-trip: compute code then resolve back to unique region name") {
            assertEquals(RegionMatchResult.Unique("Germany"), match(listOf(RegionScopeKey("Germany", germany)), code()))
        },
        original("TransportCodeRegionResolverTests", "Empty scopeKeys returns none") { assertEquals(RegionMatchResult.None, match(emptyList(), 0x1234u)) },
        original("TransportCodeRegionResolverTests", "No matching region returns none") {
            assertEquals(RegionMatchResult.None, match(listOf(RegionScopeKey("Germany", germany), RegionScopeKey("USA", hex("5eaf24a29d2936601e22ac56065cb314"))), 31_788u))
        },
        original("TransportCodeRegionResolverTests", "Ambiguous match returns all names sorted independent of input order") {
            val forward = listOf(RegionScopeKey("de-by", germany), RegionScopeKey("de-hh", germany))
            val a = match(forward); val b = match(forward.reversed())
            assertEquals(RegionMatchResult.Ambiguous(listOf("de-by", "de-hh")), a); assertEquals(a, b)
        },
        original("TransportCodeRegionResolverTests", "Dollar-prefixed private regions never match via empty scope key") {
            assertNull(TransportCodeRegionResolver.deriveScopeKey("\$secret")); assertEquals(RegionMatchResult.None, match(emptyList(), 0x1234u))
        },
    )
}
