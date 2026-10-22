// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/V112ProtocolTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/RoundTripTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Events/MeshEventErrorCodeTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.parser

import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.core.protocol.lpp.LPPDecoder
import com.meshcoreone.android.core.protocol.lpp.LPPEncoder
import com.meshcoreone.android.core.protocol.lpp.LPPSensorType
import com.meshcoreone.android.core.protocol.lpp.LPPValue
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.ErrorCode
import com.meshcoreone.android.core.protocol.model.ResponseCategory
import com.meshcoreone.android.core.protocol.model.ResponseCode
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CrossComponentParserCasesTest {
    private fun v112(name: String, assertions: () -> Unit) = original("V112ProtocolTests", name, assertions)
    private fun roundTrip(name: String, assertions: () -> Unit) = original("RoundTripTests", name, assertions)

    @TestFactory
    fun v112ParserCases() = listOf(
        v112("contactDeleted response code exists") { assertEquals(ResponseCode.CONTACT_DELETED, ResponseCode.fromRawValue(0x8fu)) },
        v112("contactsFull response code exists") { assertEquals(ResponseCode.CONTACTS_FULL, ResponseCode.fromRawValue(0x90u)) },
        v112("contactDeleted category is push") { assertEquals(ResponseCategory.PUSH, ResponseCode.CONTACT_DELETED.category) },
        v112("contactsFull category is push") { assertEquals(ResponseCategory.PUSH, ResponseCode.CONTACTS_FULL.category) },
        v112("contactDeleted parses valid payload") { assertEquals(filled(0xab, 32), assertIs<MeshEvent.ContactDeleted>(Parsers.ContactDeleted.parse(filled(0xab, 32))).publicKey) },
        v112("contactDeleted parse failure for short payload") { expectFailure(Parsers.ContactDeleted.parse(filled(0xab, 31)), reason = "ContactDeleted too short") },
        v112("contactDeleted ignores extra bytes") { assertEquals(filled(0xcd, 32), assertIs<MeshEvent.ContactDeleted>(Parsers.ContactDeleted.parse(filled(0xcd, 32) + hex("ffffff"))).publicKey) },
        v112("contactsFull parses empty payload") { assertEquals(MeshEvent.ContactsFull, Parsers.ContactsFull.parse(Bytes.EMPTY)) },
        v112("contactsFull parses any payload") { assertEquals(MeshEvent.ContactsFull, Parsers.ContactsFull.parse(hex("010203"))) },
        v112("packetParser routes contactDeleted") { assertEquals(filled(0xef, 32), parsed<MeshEvent.ContactDeleted>(rawFrame(0x8f, filled(0xef, 32))).publicKey) },
        v112("packetParser routes contactsFull") { assertEquals(MeshEvent.ContactsFull, PacketParser.parse(Bytes.of(0x90))) },
        v112("packetParser contactDeleted parse failure for short payload") { expectFailure(PacketParser.parse(rawFrame(0x8f, filled(0xab, 20))), reason = "ContactDeleted too short") },
        v112("autoAddConfig response code exists") { assertEquals(ResponseCode.AUTO_ADD_CONFIG, ResponseCode.fromRawValue(0x19u)) },
        v112("autoAddConfig category is device") { assertEquals(ResponseCategory.DEVICE, ResponseCode.AUTO_ADD_CONFIG.category) },
        v112("autoAddConfig parses single-byte payload with default maxHops") {
            val config = parsed<MeshEvent.AutoAddConfig>(hex("190f")).config; assertEquals(15u.toUByte(), config.bitmask); assertEquals(0u.toUByte(), config.maxHops)
        },
        v112("autoAddConfig parses two-byte payload with maxHops") {
            val config = parsed<MeshEvent.AutoAddConfig>(hex("190f05")).config; assertEquals(15u.toUByte(), config.bitmask); assertEquals(5u.toUByte(), config.maxHops)
        },
        v112("autoAddConfig parse failure for empty payload") { expectFailure(PacketParser.parse(Bytes.of(0x19)), reason = "AutoAddConfig response too short") },
        v112("autoAddConfig ignores extra bytes beyond maxHops") {
            val config = parsed<MeshEvent.AutoAddConfig>(hex("190e03ffff")).config; assertEquals(14u.toUByte(), config.bitmask); assertEquals(3u.toUByte(), config.maxHops)
        },
    )

    @TestFactory
    fun deviceAndContactRoundTrips() = listOf(
        roundTrip("Contact round trip") {
            val contact = assertIs<MeshEvent.Contact>(Parsers.Contact.parse(contactBody())).contact
            assertEquals(filled(0xaa, 32), contact.publicKey); assertEquals(ContactType.CHAT, contact.type)
            assertEquals(ContactFlags(2u), contact.flags); assertEquals(3u.toUByte(), contact.outPathLength)
            assertEquals(hex("112233"), contact.outPath); assertEquals("TestContact", contact.advertisedName)
            assertEquals(37.7749, contact.latitude, 0.0001); assertEquals(-122.4194, contact.longitude, 0.0001)
        },
        roundTrip("Contact rejects reserved path length encoding") { expectFailure(Parsers.Contact.parse(contactBody(pathLength = 0xc1)), reason = "reserved path length encoding") },
        roundTrip("SelfInfo round trip") {
            val info = assertIs<MeshEvent.SelfInfo>(Parsers.SelfInfo.parse(selfBody())).info
            assertEquals(1u.toUByte(), info.advertisementType); assertEquals(20.toByte(), info.txPower); assertEquals(30.toByte(), info.maxTxPower)
            assertEquals(filled(0xbb, 32), info.publicKey); assertEquals(37.7749, info.latitude, 0.0001); assertEquals(-122.4194, info.longitude, 0.0001)
            assertEquals(1u.toUByte(), info.multiAcks); assertEquals(2u.toUByte(), info.advertisementLocationPolicy)
            assertEquals(0u.toUByte(), info.telemetryModeEnvironment); assertEquals(1u.toUByte(), info.telemetryModeLocation); assertEquals(2u.toUByte(), info.telemetryModeBase)
            assertTrue(info.manualAddContacts); assertEquals(906.875, info.radioFrequency, 0.001); assertEquals(250.0, info.radioBandwidth, 0.001)
            assertEquals(11u.toUByte(), info.radioSpreadingFactor); assertEquals(8u.toUByte(), info.radioCodingRate); assertEquals("MyNode", info.name)
        },
        roundTrip("SelfInfo negative TX power round trip") {
            val info = assertIs<MeshEvent.SelfInfo>(Parsers.SelfInfo.parse(selfBody(
                txPower = -5, key = filled(0xcc, 32), latitude = 0, longitude = 0, multiAcks = 0u,
                policy = 0u, telemetry = 0u, manual = 0u, frequency = 915_000u, sf = 10u, cr = 5u, name = Bytes.utf8("NegPwr"),
            ))).info
            assertEquals((-5).toByte(), info.txPower); assertEquals(30.toByte(), info.maxTxPower); assertEquals("NegPwr", info.name)
        },
        roundTrip("SelfInfo parses minimum-length payload without device name") {
            val body = selfBody(key = filled(0xaa, 32), latitude = 0, longitude = 0, multiAcks = 2u, policy = 1u, telemetry = 0u, frequency = 915_000u, sf = 10u, cr = 5u, name = Bytes.EMPTY)
            assertEquals(57, body.size); val info = assertIs<MeshEvent.SelfInfo>(Parsers.SelfInfo.parse(body)).info
            assertEquals(10u.toUByte(), info.radioSpreadingFactor); assertEquals(5u.toUByte(), info.radioCodingRate); assertTrue(info.name.isEmpty())
        },
        roundTrip("SelfInfo rejects truncated fixed-width payload") { expectFailure(Parsers.SelfInfo.parse(selfBody().prefix(55)), reason = "SelfInfo response too short") },
        roundTrip("DeviceInfo v9 client repeat round trip") {
            val body = deviceBody(9u, build = "15 Feb 2026", hardwareVersion = "1.13.0", extensions = Bytes.of(1))
            assertEquals(80, body.size); val info = assertIs<MeshEvent.DeviceInfo>(Parsers.DeviceInfo.parse(body)).info
            assertEquals(9u.toUByte(), info.firmwareVersion); assertEquals(100L, info.maxContacts); assertEquals(8L, info.maxChannels)
            assertEquals(123_456u, info.blePin); assertTrue(info.clientRepeat)
        },
        roundTrip("DeviceInfo v10 pathHashMode round trip") {
            val body = deviceBody(pin = 654_321u, build = "20 Feb 2026", hardwareVersion = "1.14.0", extensions = hex("0102"))
            assertEquals(81, body.size); val info = assertIs<MeshEvent.DeviceInfo>(Parsers.DeviceInfo.parse(body)).info
            assertEquals(10u.toUByte(), info.firmwareVersion); assertEquals(100L, info.maxContacts); assertEquals(8L, info.maxChannels)
            assertEquals(654_321u, info.blePin); assertTrue(info.clientRepeat); assertEquals(2u.toUByte(), info.pathHashMode)
        },
        roundTrip("DeviceInfo v10 tolerates missing pathHashMode byte") {
            val body = deviceBody(pin = 654_321u, build = "", model = "", hardwareVersion = "", extensions = Bytes.of(1))
            assertEquals(80, body.size)
            val info = assertIs<MeshEvent.DeviceInfo>(Parsers.DeviceInfo.parse(body)).info
            assertEquals(10u.toUByte(), info.firmwareVersion); assertEquals(100L, info.maxContacts); assertTrue(info.clientRepeat); assertEquals(0u.toUByte(), info.pathHashMode)
        },
        roundTrip("DeviceInfo v9 defaults pathHashMode to 0") {
            val body = deviceBody(9u, pin = 0u, build = "", model = "", hardwareVersion = "", extensions = Bytes.of(0))
            assertEquals(80, body.size)
            val info = assertIs<MeshEvent.DeviceInfo>(Parsers.DeviceInfo.parse(body)).info
            assertEquals(9u.toUByte(), info.firmwareVersion); assertEquals(0u.toUByte(), info.pathHashMode)
        },
        roundTrip("DeviceInfo v8 no client repeat round trip") {
            val body = deviceBody(8u, 25u, 4u, 0u, "", "", "", Bytes.EMPTY); assertEquals(79, body.size)
            val info = assertIs<MeshEvent.DeviceInfo>(Parsers.DeviceInfo.parse(body)).info
            assertEquals(8u.toUByte(), info.firmwareVersion); assertFalse(info.clientRepeat)
        },
    )

    private fun contactMessage(signature: Bytes = Bytes.EMPTY, type: UByte = 0u, text: String = "Hello World") =
        hex("1800000123456789ab02") + Bytes.of(type.toInt()) + le(1_704_067_200u) + signature + Bytes.utf8(text)

    @TestFactory
    fun messagesAndChannels() = listOf(
        roundTrip("ContactMessage v3 round trip") {
            val message = assertIs<MeshEvent.ContactMessageReceived>(Parsers.ContactMessage.parse(contactMessage(), Parsers.ContactMessage.Version.V3)).message
            assertEquals(6.0, message.snr); assertEquals(hex("0123456789ab"), message.senderPublicKeyPrefix)
            assertEquals(2u.toUByte(), message.pathLength); assertEquals(0u.toUByte(), message.textType); assertEquals("Hello World", message.text)
        },
        roundTrip("ContactMessage rejects truncated signature payload") {
            expectFailure(Parsers.ContactMessage.parse(contactMessage(hex("deadbe"), 2u, ""), Parsers.ContactMessage.Version.V3), reason = "signature truncated")
        },
        roundTrip("ChannelMessage v3 round trip") {
            val message = assertIs<MeshEvent.ChannelMessageReceived>(Parsers.ChannelMessage.parse(hex("ec0000020000") + le(1_704_067_200u) + Bytes.utf8("Broadcast message"), Parsers.ChannelMessage.Version.V3)).message
            assertEquals(-5.0, message.snr); assertEquals(2u.toUByte(), message.channelIndex)
            assertEquals(0u.toUByte(), message.pathLength); assertEquals("Broadcast message", message.text)
        },
        roundTrip("ChannelInfo round trip") {
            val secret = Bytes(ByteArray(16) { it.toByte() })
            val info = assertIs<MeshEvent.ChannelInfo>(Parsers.ChannelInfo.parse(Bytes.of(1) + Bytes.utf8("TestChannel").paddedOrTruncated(32) + secret)).info
            assertEquals(1u.toUByte(), info.index); assertEquals("TestChannel", info.name); assertEquals(secret, info.secret)
        },
        roundTrip("ChannelInfo handles garbage bytes after null") {
            val name = (Bytes.utf8("Primary") + hex("00fffe8081c0c1") + filled(0xab, 32)).prefix(32)
            val info = assertIs<MeshEvent.ChannelInfo>(Parsers.ChannelInfo.parse(Bytes.of(2) + name + filled(0xcc, 16))).info
            assertEquals(2u.toUByte(), info.index); assertEquals("Primary", info.name); assertEquals(filled(0xcc, 16), info.secret)
        },
        roundTrip("ChannelInfo lossy decodes invalid UTF-8 before null") {
            val info = assertIs<MeshEvent.ChannelInfo>(Parsers.ChannelInfo.parse(Bytes.of(3) + hex("507269ff6d617279").paddedOrTruncated(32) + filled(0x55, 16))).info
            assertEquals(3u.toUByte(), info.index); assertEquals("Pri\ufffdmary", info.name)
            assertTrue(info.name.isNotEmpty()); assertEquals(filled(0x55, 16), info.secret)
        },
        roundTrip("ChannelMessage v3 empty-text broadcast parses at firmware-minimum size") {
            val body = hex("ec0000020000") + le(1_704_067_200u); assertEquals(10, body.size)
            val message = assertIs<MeshEvent.ChannelMessageReceived>(Parsers.ChannelMessage.parse(body, Parsers.ChannelMessage.Version.V3)).message
            assertEquals(2u.toUByte(), message.channelIndex); assertEquals(0u.toUByte(), message.pathLength); assertTrue(message.text.isEmpty())
        },
        roundTrip("ChannelMessage v1 empty-text broadcast parses at firmware-minimum size") {
            val body = hex("030000") + le(1_704_067_200u); assertEquals(7, body.size)
            val message = assertIs<MeshEvent.ChannelMessageReceived>(Parsers.ChannelMessage.parse(body, Parsers.ChannelMessage.Version.V1)).message
            assertEquals(3u.toUByte(), message.channelIndex); assertEquals(0u.toUByte(), message.pathLength); assertTrue(message.text.isEmpty())
        },
    )

    @TestFactory
    fun statusStatsAndVariables() = listOf(
        roundTrip("StatusResponse round trip") {
            val prefix = hex("0123456789ab")
            val body = Bytes.of(0) + prefix + statusBody(3800u, 5u, -110, -85, listOf(1000u, 500u, 3600u, 86400u, 100u, 400u, 200u, 800u), 10u, 24, 5u, 15u, le(1800u))
            assertEquals(StatusResponse(prefix, 3800, 5, -110, -85, 1000u, 500u, 3600u, 86400u, 100u, 400u, 200u, 800u, 10, 6.0, 5, 15, 1800u), assertIs<MeshEvent.StatusResponse>(Parsers.StatusResponse.parse(body)).response)
        },
        roundTrip("CoreStats round trip") {
            val stats = assertIs<MeshEvent.StatsCore>(Parsers.CoreStats.parse(ByteWriter().appendUInt16LE(3750u).appendUInt32LE(86400u).appendUInt16LE(3u).appendUInt8(5u).toBytes())).stats
            assertEquals(3750u.toUShort(), stats.batteryMV); assertEquals(86400u, stats.uptimeSeconds); assertEquals(3u.toUShort(), stats.errors); assertEquals(5u.toUByte(), stats.queueLength)
        },
        roundTrip("RadioStats round trip") {
            val stats = assertIs<MeshEvent.StatsRadio>(Parsers.RadioStats.parse(ByteWriter().appendInt16LE(-115).appendInt8(-90).appendInt8(28).appendUInt32LE(1000u).appendUInt32LE(2000u).toBytes())).stats
            assertEquals((-115).toShort(), stats.noiseFloor); assertEquals((-90).toByte(), stats.lastRSSI)
            assertEquals(7.0, stats.lastSNR, 0.01); assertEquals(1000u, stats.txAirtimeSeconds); assertEquals(2000u, stats.rxAirtimeSeconds)
        },
        roundTrip("PacketStats round trip (legacy 24-byte format)") { packetStats(receiveErrors = null) },
        roundTrip("PacketStats round trip (28-byte format with receiveErrors)") { packetStats(receiveErrors = 42u) },
        roundTrip("AllowedRepeatFreq round trip") {
            val body = le(433_000u) + le(433_000u) + le(869_000u) + le(869_000u) + le(918_000u) + le(918_000u)
            val ranges = assertIs<MeshEvent.AllowedRepeatFreq>(Parsers.AllowedRepeatFreq.parse(body)).ranges
            assertEquals(3, ranges.size); assertEquals(433_000u, ranges[0].lowerKHz); assertEquals(433_000u, ranges[0].upperKHz)
            assertEquals(869_000u, ranges[1].lowerKHz); assertEquals(918_000u, ranges[2].lowerKHz)
        },
        roundTrip("CustomVars round trip") {
            val values = assertIs<MeshEvent.CustomVars>(Parsers.CustomVars.parse(Bytes.utf8("key1:value1,key2:value2,mode:auto"))).values
            assertEquals("value1", values["key1"]); assertEquals("value2", values["key2"]); assertEquals("auto", values["mode"])
        },
    )

    private fun packetStats(receiveErrors: UInt?) {
        val numbers = listOf(1000u, 500u, 100u, 400u, 200u, 800u)
        val writer = ByteWriter(); numbers.forEach(writer::appendUInt32LE)
        if (receiveErrors != null) writer.appendUInt32LE(receiveErrors)
        val stats = assertIs<MeshEvent.StatsPackets>(Parsers.PacketStats.parse(writer.toBytes())).stats
        assertEquals(numbers, listOf(stats.received, stats.sent, stats.floodTx, stats.directTx, stats.floodRx, stats.directRx))
        assertEquals(receiveErrors ?: 0u, stats.receiveErrors)
    }

    @TestFactory
    fun originalLppSeams() = listOf(
        roundTrip("LPP encoder/decoder temperature round trip") {
            val encoder = LPPEncoder(); encoder.addTemperature(1u, 22.5)
            val points = LPPDecoder.decode(encoder.encode()).requireComplete()
            assertEquals(1, points.size); assertEquals(1u.toUByte(), points[0].channel); assertEquals(LPPSensorType.TEMPERATURE, points[0].type)
            assertEquals(22.5, assertIs<LPPValue.Float>(points[0].value).value, 0.1)
        },
        roundTrip("LPP encoder/decoder GPS round trip") {
            val encoder = LPPEncoder(); encoder.addGPS(3u, 37.7749, -122.4194, 50.0)
            val points = LPPDecoder.decode(encoder.encode()).requireComplete()
            assertEquals(1, points.size); assertEquals(3u.toUByte(), points[0].channel); assertEquals(LPPSensorType.GPS, points[0].type)
            val gps = assertIs<LPPValue.Gps>(points[0].value); assertEquals(37.7749, gps.latitude, 0.0001)
            assertEquals(-122.4194, gps.longitude, 0.0001); assertEquals(50.0, gps.altitude, 0.01)
        },
        roundTrip("LPP encoder/decoder multi-sensor round trip") {
            val encoder = LPPEncoder(); encoder.addTemperature(1u, 25.0); encoder.addHumidity(2u, 60.0); encoder.addVoltage(3u, 3.7)
            val points = LPPDecoder.decode(encoder.encode()).requireComplete()
            assertEquals(3, points.size); assertEquals(listOf(1, 2, 3), points.map { it.channel.toInt() })
            assertEquals(listOf(LPPSensorType.TEMPERATURE, LPPSensorType.HUMIDITY, LPPSensorType.VOLTAGE), points.map { it.type })
            assertEquals(25.0, assertIs<LPPValue.Float>(points[0].value).value, 0.1)
            assertEquals(60.0, assertIs<LPPValue.Float>(points[1].value).value, 0.5)
            assertEquals(3.7, assertIs<LPPValue.Float>(points[2].value).value, 0.01)
        },
        roundTrip("LPP encoder/decoder negative temperature round trip") {
            val encoder = LPPEncoder(); encoder.addTemperature(1u, -15.5)
            val points = LPPDecoder.decode(encoder.encode()).requireComplete(); assertEquals(1, points.size)
            assertEquals(-15.5, assertIs<LPPValue.Float>(points[0].value).value, 0.1)
        },
        roundTrip("LPP encoder/decoder accelerometer round trip") {
            val encoder = LPPEncoder(); encoder.addAccelerometer(5u, -0.5, 0.25, 1.0)
            val points = LPPDecoder.decode(encoder.encode()).requireComplete()
            assertEquals(1, points.size); assertEquals(LPPSensorType.ACCELEROMETER, points[0].type)
            val vector = assertIs<LPPValue.Vector3>(points[0].value)
            assertEquals(-0.5, vector.x, 0.001); assertEquals(0.25, vector.y, 0.001); assertEquals(1.0, vector.z, 0.001)
        },
    )

    @TestFactory
    fun wp106RouterSeam() = listOf(
        original("MeshEventErrorCodeTests", "PacketParser maps a RESP_CODE_ERR frame to .error with the firmware sub-code") {
            val event = PacketParser.parse(hex("0106")); assertEquals(ErrorCode.ILLEGAL_ARGUMENT, event.errorCode)
            assertEquals(6u.toUByte(), assertIs<MeshEvent.Error>(event).code)
        },
    )
}
