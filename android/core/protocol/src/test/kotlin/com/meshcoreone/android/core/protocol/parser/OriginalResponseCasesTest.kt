// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/AckParsingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/ContactNameDecodingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/LoginSuccessParserTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/DeviceInfoParsingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/DiscoverResponseParsingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/NewResponseParsingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/PathDiscoveryParsingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/RawDataParsingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/TelemetryParsingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/TraceDataParsingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/V115ParsingTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.parser

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.PathInfo
import com.meshcoreone.android.core.protocol.event.TraceNode
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OriginalResponseCasesTest {
    @TestFactory
    fun ack() = listOf(
        original("AckParsingTests", "4-byte ACK payload produces tripTime nil") {
            val event = parsed<MeshEvent.Acknowledgement>(rawFrame(0x82, requestTag))
            assertEquals(requestTag, event.code)
            assertNull(event.tripTime)
        },
        original("AckParsingTests", "8-byte ACK payload parses trip time as UInt32 LE") {
            val event = parsed<MeshEvent.Acknowledgement>(hex("82deadbeeff4010000"))
            assertEquals(requestTag, event.code)
            assertEquals(500u, event.tripTime)
        },
        original("AckParsingTests", "3-byte ACK payload produces parseFailure") {
            expectFailure(PacketParser.parse(hex("82010203")))
        },
        original("AckParsingTests", "5-7 byte ACK payload produces tripTime nil (no partial read)") {
            for (size in 1..3) {
                val event = parsed<MeshEvent.Acknowledgement>(rawFrame(0x82, requestTag + filled(0xff, size)))
                assertEquals(requestTag, event.code)
                assertNull(event.tripTime)
            }
        },
    )

    @TestFactory
    fun contactNames() = listOf(
        original("ContactNameDecodingTests", "Flag emoji split at the name boundary decodes to a readable prefix") {
            val name = filled(0x78, 28) + hex("f09f8700")
            assertEquals("x".repeat(28), assertNotNull(Parsers.parseContactData(sourceContactNameBody(name))).advertisedName)
        },
        original("ContactNameDecodingTests", "Lone regional indicator is kept when only half a flag survives") {
            val name = filled(0x78, 27) + hex("f09f87ba00")
            assertEquals("x".repeat(27) + "\uD83C\uDDFA", assertNotNull(Parsers.parseContactData(sourceContactNameBody(name))).advertisedName)
        },
        original("ContactNameDecodingTests", "A flag name that fits the buffer decodes unchanged") {
            val name = "Team \uD83C\uDDFA\uD83C\uDDF8"
            assertEquals(name, assertNotNull(Parsers.parseContactData(sourceContactNameBody(Bytes.utf8(name) + Bytes.of(0)))).advertisedName)
        },
        original("ContactNameDecodingTests", "Plain ASCII name still decodes") {
            assertEquals("TestNode", assertNotNull(Parsers.parseContactData(sourceContactNameBody(Bytes.utf8("TestNode") + Bytes.of(0)))).advertisedName)
        },
    )

    private fun sourceContactNameBody(name: Bytes): Bytes {
        val body = contactBody(name = name, flags = 0, pathLength = 255, path = Bytes.EMPTY,
            lastAdvert = 0u, latitude = 0, longitude = 0, lastModified = 0u)
        val expected = ByteArray(147)
        expected.fill(0xAA.toByte(), 0, 32)
        expected[32] = 1
        expected[34] = 0xFF.toByte()
        name.toByteArray().copyInto(expected, destinationOffset = 99, endIndex = minOf(name.size, 32))
        assertEquals(147, body.size)
        assertEquals(Bytes(expected), body)
        return body
    }

    private fun login(admin: Int, acl: Int, time: UInt = 0u, prefix: Bytes = nodePrefix) =
        assertIs<MeshEvent.LoginSuccess>(Parsers.LoginSuccess.parse(loginBody(admin.toUByte(), acl.toUByte(), time, prefix))).info

    @TestFactory
    fun loginSuccess() = listOf(
        original("LoginSuccessParserTests", "C++ admin login (byte0=1, ACL=0x03) parses as admin on both repeater and room-server") {
            val info = login(1, 3); assertTrue(info.isAdmin); assertEquals(2u.toUByte(), info.permissions)
        },
        original("LoginSuccessParserTests", "C++ repeater guest login (byte0=0, ACL=0x00) parses as guest") {
            val info = login(0, 0); assertFalse(info.isAdmin); assertEquals(0u.toUByte(), info.permissions)
        },
        original("LoginSuccessParserTests", "C++ repeater read-write login (byte0=0, ACL=0x02) parses as read-write, not admin") {
            val info = login(0, 2); assertFalse(info.isAdmin); assertEquals(1u.toUByte(), info.permissions)
        },
        original("LoginSuccessParserTests", "C++ room-server guest login (byte0=2, ACL=0x00) parses as non-admin guest") {
            val info = login(2, 0); assertFalse(info.isAdmin); assertEquals(0u.toUByte(), info.permissions)
        },
        original("LoginSuccessParserTests", "C++ room-server read-only login (byte0=0, ACL=0x01) parses as non-posting guest") {
            val info = login(0, 1); assertFalse(info.isAdmin); assertEquals(0u.toUByte(), info.permissions)
        },
        original("LoginSuccessParserTests", "pyMC admin login (ACL=0x02) parses as admin") {
            val info = login(1, 2); assertTrue(info.isAdmin); assertEquals(2u.toUByte(), info.permissions)
        },
        original("LoginSuccessParserTests", "pyMC guest login (ACL=0x01) parses as non-admin guest") {
            val info = login(0, 1); assertFalse(info.isAdmin); assertEquals(0u.toUByte(), info.permissions)
        },
        original("LoginSuccessParserTests", "Legacy 7-byte payload with companion-radio hardcoded 0 parses as non-admin") {
            val info = assertIs<MeshEvent.LoginSuccess>(Parsers.LoginSuccess.parse(hex("00aabbccddeeff"))).info
            assertFalse(info.isAdmin); assertEquals(1u.toUByte(), info.permissions)
        },
        original("LoginSuccessParserTests", "Payload shorter than 7 bytes returns zero LoginInfo fallback") {
            val info = assertIs<MeshEvent.LoginSuccess>(Parsers.LoginSuccess.parse(hex("0102"))).info
            assertFalse(info.isAdmin); assertEquals(0u.toUByte(), info.permissions); assertTrue(info.publicKeyPrefix.isEmpty)
        },
        original("LoginSuccessParserTests", "Extended-format server timestamp parses as serverTime") {
            assertEquals(java.time.Instant.ofEpochSecond(1_784_300_000), login(1, 3, 1_784_300_000u).serverTime)
        },
        original("LoginSuccessParserTests", "Zero server timestamp yields nil serverTime") { assertNull(login(1, 3).serverTime) },
        original("LoginSuccessParserTests", "Legacy 7-byte payload yields nil serverTime") {
            assertNull(assertIs<MeshEvent.LoginSuccess>(Parsers.LoginSuccess.parse(hex("00aabbccddeeff"))).info.serverTime)
        },
        original("LoginSuccessParserTests", "Extended-format pubkey prefix is extracted from bytes 1..<7") {
            assertEquals(hex("112233445566"), login(1, 3, prefix = hex("112233445566")).publicKeyPrefix)
        },
    )

    private fun device(data: Bytes) = assertIs<MeshEvent.DeviceInfo>(Parsers.DeviceInfo.parse(data)).info

    @TestFactory
    fun deviceInfo() = listOf(
        original("DeviceInfoParsingTests", "Full v10 payload parses all fields") {
            val info = device(deviceBody())
            assertEquals(10u.toUByte(), info.firmwareVersion); assertEquals(100L, info.maxContacts)
            assertEquals(8L, info.maxChannels); assertEquals(123_456u, info.blePin)
            assertTrue(info.clientRepeat); assertEquals(0u.toUByte(), info.pathHashMode)
        },
        original("DeviceInfoParsingTests", "v9 payload missing client_repeat byte defaults to false") {
            val body = deviceBody(9u, extensions = Bytes.EMPTY); assertEquals(79, body.size)
            val info = device(body); assertEquals(9u.toUByte(), info.firmwareVersion)
            assertEquals(100L, info.maxContacts); assertFalse(info.clientRepeat)
        },
        original("DeviceInfoParsingTests", "v10 payload missing pathHashMode byte defaults to 0") {
            val body = deviceBody(extensions = hex("01")); assertEquals(80, body.size)
            val info = device(body); assertEquals(10u.toUByte(), info.firmwareVersion)
            assertTrue(info.clientRepeat); assertEquals(0u.toUByte(), info.pathHashMode); assertEquals(1, info.hashSize)
        },
        original("DeviceInfoParsingTests", "v10 payload missing both extension bytes defaults both") {
            val body = deviceBody(extensions = Bytes.EMPTY); assertEquals(79, body.size)
            val info = device(body); assertEquals(10u.toUByte(), info.firmwareVersion)
            assertEquals(100L, info.maxContacts); assertFalse(info.clientRepeat); assertEquals(0u.toUByte(), info.pathHashMode)
        },
        original("DeviceInfoParsingTests", "v3+ payload shorter than 79 bytes is rejected") {
            expectFailure(Parsers.DeviceInfo.parse(Bytes.of(10) + filled(size = 9)))
        },
        original("DeviceInfoParsingTests", "Empty payload still returns parseFailure") { expectFailure(Parsers.DeviceInfo.parse(Bytes.EMPTY)) },
        original("DeviceInfoParsingTests", "Pre-v3 firmware parses without v3 fields") {
            val info = device(Bytes.of(2)); assertEquals(2u.toUByte(), info.firmwareVersion)
            assertEquals(0L, info.maxContacts); assertEquals("", info.model)
        },
    )

    @TestFactory
    fun newResponses() = listOf(
        original("NewResponseParsingTests", "advertPathResponse parse") {
            val response = assertIs<MeshEvent.AdvertPathResponse>(Parsers.AdvertPathResponse.parse(le(1_704_067_200u) + hex("03112233"))).response
            assertEquals(1_704_067_200u, response.recvTimestamp); assertEquals(3u.toUByte(), response.pathLength)
            assertEquals(hex("112233"), response.path)
        },
        original("NewResponseParsingTests", "advertPathResponse empty path") {
            val response = assertIs<MeshEvent.AdvertPathResponse>(Parsers.AdvertPathResponse.parse(le(1000u) + Bytes.of(0))).response
            assertEquals(0u.toUByte(), response.pathLength); assertTrue(response.path.isEmpty)
        },
        original("NewResponseParsingTests", "advertPathResponse too short") { expectFailure(Parsers.AdvertPathResponse.parse(hex("01020304"))) },
        original("NewResponseParsingTests", "advertPathResponse rejects reserved path length encoding") {
            expectFailure(Parsers.AdvertPathResponse.parse(le(1_704_067_200u) + hex("c111")), reason = "reserved path length encoding")
        },
        original("NewResponseParsingTests", "tuningParamsResponse parse") {
            val response = assertIs<MeshEvent.TuningParamsResponse>(Parsers.TuningParamsResponse.parse(le(1500u) + le(2500u))).response
            assertEquals(1.5, response.rxDelayBase, 0.001); assertEquals(2.5, response.airtimeFactor, 0.001)
        },
        original("NewResponseParsingTests", "tuningParamsResponse too short") { expectFailure(Parsers.TuningParamsResponse.parse(hex("01020304050607"))) },
        original("NewResponseParsingTests", "tuningParamsResponse zero values") {
            val response = assertIs<MeshEvent.TuningParamsResponse>(Parsers.TuningParamsResponse.parse(filled(size = 8))).response
            assertEquals(0.0, response.rxDelayBase, 0.001); assertEquals(0.0, response.airtimeFactor, 0.001)
        },
    )

    private fun path(data: Bytes) = assertIs<MeshEvent.PathResponse>(Parsers.PathDiscoveryResponse.parse(data)).path
    private fun pathInfo(prefix: Bytes) = PathInfo(prefix, 0u, Bytes.EMPTY, 0u, Bytes.EMPTY)

    @TestFactory
    fun pathDiscovery() = listOf(
        original("PathDiscoveryParsingTests", "pathDiscoveryResponse skips reserved byte") {
            val info = path(hex("00aabbccddeeff02112203334455"))
            assertEquals(nodePrefix, info.publicKeyPrefix); assertEquals(hex("1122"), info.outPath); assertEquals(hex("334455"), info.inPath)
        },
        original("PathDiscoveryParsingTests", "pathDiscoveryResponse handles empty paths") {
            val info = path(hex("001122334455660000")); assertTrue(info.outPath.isEmpty); assertTrue(info.inPath.isEmpty)
        },
        original("PathDiscoveryParsingTests", "pathDiscoveryResponse rejects short payload") { expectFailure(Parsers.PathDiscoveryResponse.parse(hex("00aabbccddee"))) },
        original("PathDiscoveryParsingTests", "pathDiscoveryResponse preserves mode-1 out_path_len byte") {
            val info = path(hex("0011223344556642a1b2c3d40000"))
            assertEquals(0x42u.toUByte(), info.outPathLength); assertEquals(2, info.outHopCount); assertEquals(hex("a1b2c3d4"), info.outPath)
        },
        original("PathDiscoveryParsingTests", "pathDiscoveryResponse preserves mode-2 out_path_len byte") {
            val info = path(hex("00778899aabbcc8301020304050607080900"))
            assertEquals(0x83u.toUByte(), info.outPathLength); assertEquals(3, info.outHopCount); assertEquals(9, info.outPath.size)
        },
        original("PathDiscoveryParsingTests", "pathDiscoveryResponse preserves both out and in length bytes") {
            val info = path(hex("00deadbeef000102a0a181b0b1b2"))
            assertEquals(2u.toUByte(), info.outPathLength); assertEquals(2, info.outHopCount)
            assertEquals(0x81u.toUByte(), info.inPathLength); assertEquals(1, info.inHopCount)
        },
        original("PathDiscoveryParsingTests", "pathDiscoveryResponse outHopCount is nil for reserved mode") {
            val info = path(hex("00112233445566c000")); assertEquals(0xc0u.toUByte(), info.outPathLength); assertNull(info.outHopCount)
        },
        original("PathDiscoveryParsingTests", "matches accepts a key starting with the response prefix") {
            assertTrue(pathInfo(hex("112233445566")).matches(hex("112233445566") + filled(0xab, 26)))
        },
        original("PathDiscoveryParsingTests", "matches rejects a key for a different node") { assertFalse(pathInfo(hex("112233445566")).matches(filled(0xcd, 32))) },
        original("PathDiscoveryParsingTests", "matches rejects an empty prefix instead of matching every key") { assertFalse(pathInfo(Bytes.EMPTY).matches(filled(0xcd, 32))) },
    )

    @TestFactory
    fun discover() = listOf(
        original("DiscoverResponseParsingTests", "controlData parses discover response") {
            val response = assertIs<MeshEvent.DiscoverResponse>(Parsers.ControlData.parse(hex("28ab029514393000001122334455667788"))).response
            assertEquals(5u.toUByte(), response.nodeType); assertEquals(5.0, response.snrIn, 0.001)
            assertEquals(10.0, response.snr, 0.001); assertEquals(-85L, response.rssi); assertEquals(2u.toUByte(), response.pathLength)
            assertEquals(hex("39300000"), response.tag); assertEquals(hex("1122334455667788"), response.publicKey)
        },
        original("DiscoverResponseParsingTests", "controlData parses full pubkey") {
            val response = assertIs<MeshEvent.DiscoverResponse>(Parsers.ControlData.parse(hex("28ab019128e7030000") + filled(0xaa, 32))).response
            assertEquals(32, response.publicKey.size); assertEquals(filled(0xaa, 32), response.publicKey)
        },
        original("DiscoverResponseParsingTests", "controlData non-discover returns raw") {
            val info = assertIs<MeshEvent.ControlData>(Parsers.ControlData.parse(hex("28ab0180010203"))).info
            assertEquals(0x80u.toUByte(), info.payloadType); assertEquals(hex("010203"), info.payload)
        },
        original("DiscoverResponseParsingTests", "controlData discover resp too short falls back to controlData") {
            val info = assertIs<MeshEvent.ControlData>(Parsers.ControlData.parse(hex("28ab019101020304"))).info
            assertEquals(0x91u.toUByte(), info.payloadType)
        },
    )

    @TestFactory
    fun rawData() = listOf(
        original("RawDataParsingTests", "rawData skips reserved byte") {
            val info = assertIs<MeshEvent.RawData>(Parsers.RawData.parse(hex("28abff01020304"))).info
            assertEquals(10.0, info.snr, 0.001); assertEquals(-85L, info.rssi); assertEquals(hex("01020304"), info.payload)
        },
        original("RawDataParsingTests", "rawData rejects short payload") { expectFailure(Parsers.RawData.parse(hex("28ab"))) },
        original("RawDataParsingTests", "rawData handles empty payload") { assertTrue(assertIs<MeshEvent.RawData>(Parsers.RawData.parse(hex("28abff"))).info.payload.isEmpty) },
    )

    @TestFactory
    fun telemetry() = listOf(
        original("TelemetryParsingTests", "telemetryResponse skips reserved byte") {
            val response = assertIs<MeshEvent.TelemetryResponse>(Parsers.TelemetryResponse.parse(hex("00aabbccddeeff016700fa"))).response
            assertEquals(nodePrefix, response.publicKeyPrefix); assertNull(response.tag); assertEquals(hex("016700fa"), response.rawData)
        },
        original("TelemetryParsingTests", "telemetryResponse rejects short payload") { expectFailure(Parsers.TelemetryResponse.parse(hex("00aabbccddee"))) },
        original("TelemetryParsingTests", "telemetryResponse handles empty LPP data") {
            assertTrue(assertIs<MeshEvent.TelemetryResponse>(Parsers.TelemetryResponse.parse(hex("00112233445566"))).response.rawData.isEmpty)
        },
    )

    private fun trace(data: Bytes) = assertIs<MeshEvent.TraceData>(Parsers.TraceData.parse(data)).trace

    @TestFactory
    fun traces() = listOf(
        original("TraceDataParsingTests", "traceData pathSz=0 single byte hashes") {
            val info = trace(traceBody(hex("aabb"), hex("2814")))
            assertEquals(12_345u, info.tag); assertEquals(67_890u, info.authCode); assertEquals(3, info.path.size)
            assertEquals(hex("aa"), info.path[0].hashBytes); assertEquals(hex("bb"), info.path[1].hashBytes); assertNull(info.path[2].hashBytes)
            assertEquals(listOf(10.0, 5.0, 3.0), info.path.map { it.snr })
        },
        original("TraceDataParsingTests", "traceData pathSz=2 four byte hashes") {
            val info = trace(traceBody(hex("1122334455667788"), hex("2814"), flags = 2u, tag = 111u, auth = 222u))
            assertEquals(3, info.path.size); assertEquals(hex("11223344"), info.path[0].hashBytes)
            assertEquals(hex("55667788"), info.path[1].hashBytes); assertNull(info.path[2].hashBytes); assertEquals(0x11u.toUByte(), info.path[0].hash)
        },
        original("TraceDataParsingTests", "traceData pathSz=1 two byte hashes") {
            val info = trace(traceBody(hex("aabbccdd"), hex("2814"), flags = 1u, tag = 100u, auth = 200u))
            assertEquals(3, info.path.size); assertEquals(hex("aabb"), info.path[0].hashBytes)
            assertEquals(hex("ccdd"), info.path[1].hashBytes); assertNull(info.path[2].hashBytes)
        },
        original("TraceDataParsingTests", "traceData destination marker") { assertNull(trace(traceBody(hex("ff"), hex("28"), finalSnr = 20, tag = 1u, auth = 2u)).path[0].hashBytes) },
        original("TraceDataParsingTests", "traceData empty path") {
            val info = trace(traceBody(Bytes.EMPTY, Bytes.EMPTY, finalSnr = 40, tag = 999u, auth = 888u))
            assertEquals(1, info.path.size); assertNull(info.path[0].hashBytes); assertEquals(10.0, info.path[0].snr, 0.001)
        },
        original("TraceDataParsingTests", "traceData legacy hash accessor") {
            val info = trace(traceBody(hex("42"), hex("28"), finalSnr = 20, tag = 1u, auth = 2u))
            assertEquals(0x42u.toUByte(), info.path[0].hash); assertNull(info.path[1].hash)
        },
        original("TraceDataParsingTests", "traceData too short payload") { expectFailure(Parsers.TraceData.parse(hex("00010001020304050607"))) },
        original("TraceDataParsingTests", "TraceNode init with hashBytes") {
            val node = TraceNode(hex("112233"), 5.5); assertEquals(hex("112233"), node.hashBytes)
            assertEquals(5.5, node.snr); assertEquals(0x11u.toUByte(), node.hash)
        },
        original("TraceDataParsingTests", "TraceNode init with nil hashBytes") {
            val node = TraceNode(null, 3.0); assertNull(node.hashBytes); assertNull(node.hash); assertEquals(3.0, node.snr)
        },
        original("TraceDataParsingTests", "TraceNode legacy init with hash") {
            val node = TraceNode.fromHash(0xabu, 7.5); assertEquals(hex("ab"), node.hashBytes); assertEquals(0xabu.toUByte(), node.hash); assertEquals(7.5, node.snr)
        },
        original("TraceDataParsingTests", "TraceNode legacy init with nil hash") {
            val node = TraceNode.fromHash(null, 2.0); assertNull(node.hashBytes); assertNull(node.hash); assertEquals(2.0, node.snr)
        },
    )

    @TestFactory
    fun v115() = listOf(
        original("V115ParsingTests", "channelDatagram parses valid payload") {
            val datagram = assertIs<MeshEvent.ChannelDataReceived>(Parsers.ChannelDatagram.parse(hex("14000003ffffff04deadbeef"))).datagram
            assertEquals(3u.toUByte(), datagram.channelIndex); assertEquals(0xffu.toUByte(), datagram.pathLength)
            assertEquals(0xffffu.toUShort(), datagram.dataType); assertEquals(requestTag, datagram.data); assertEquals(5.0, datagram.snr)
        },
        original("V115ParsingTests", "channelDatagram routes via PacketParser") {
            val datagram = parsed<MeshEvent.ChannelDataReceived>(hex("1b0000000003123402aabb")).datagram
            assertEquals(3u.toUByte(), datagram.pathLength); assertEquals(0x3412u.toUShort(), datagram.dataType); assertEquals(hex("aabb"), datagram.data)
        },
        original("V115ParsingTests", "channelDatagram rejects truncated payload") { expectFailure(Parsers.ChannelDatagram.parse(hex("00000001ff"))) },
        original("V115ParsingTests", "channelDatagram truncates when declared data_len exceeds remaining bytes") {
            assertEquals(hex("aabb"), assertIs<MeshEvent.ChannelDataReceived>(Parsers.ChannelDatagram.parse(hex("00000001ffffff10aabb"))).datagram.data)
        },
        original("V115ParsingTests", "defaultFloodScope parses empty payload as null") { assertNull(assertIs<MeshEvent.DefaultFloodScope>(Parsers.DefaultFloodScope.parse(Bytes.EMPTY)).scope) },
        original("V115ParsingTests", "defaultFloodScope parses populated payload") {
            val scope = assertNotNull(assertIs<MeshEvent.DefaultFloodScope>(Parsers.DefaultFloodScope.parse(Bytes.utf8("Europe").paddedOrTruncated(31) + filled(0x7e, 16))).scope)
            assertEquals("Europe", scope.name); assertEquals(filled(0x7e, 16), scope.scopeKey)
        },
        original("V115ParsingTests", "defaultFloodScope routes via PacketParser") {
            assertEquals("ch", parsed<MeshEvent.DefaultFloodScope>(rawFrame(0x1c, Bytes.utf8("ch").paddedOrTruncated(31) + filled(1, 16))).scope?.name)
        },
        original("V115ParsingTests", "defaultFloodScope rejects partial payload") { expectFailure(Parsers.DefaultFloodScope.parse(filled(size = 20))) },
    )
}
