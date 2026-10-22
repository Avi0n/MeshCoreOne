// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/ProtocolBugFixTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.parser

import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.Neighbour
import com.meshcoreone.android.core.protocol.event.NeighboursResponse
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.core.protocol.model.BinaryRequestType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OriginalBugFixCasesTest {
    private fun source(name: String, test: () -> Unit) = original("ProtocolBugFixTests", name, test)
    private fun push(battery: UShort = 1000u, noise: Short = -110, rssi: Short = -85, tail: Bytes = filled(size = 4)): Bytes =
        Bytes.of(0) + nodePrefix + ByteWriter().appendUInt16LE(battery).appendUInt16LE(5u)
            .appendInt16LE(noise).appendInt16LE(rssi).append(filled(size = 40)).append(tail).toBytes()
    private fun binary(
        data: Bytes, layout: StatusResponse.Layout = StatusResponse.Layout.REPEATER, prefix: Bytes = nodePrefix,
    ) = assertNotNull(Parsers.StatusResponse.parseFromBinaryResponse(data, prefix, layout))
    private fun status(data: Bytes, layout: StatusResponse.Layout = StatusResponse.Layout.REPEATER) =
        assertIs<MeshEvent.StatusResponse>(Parsers.StatusResponse.parse(data, layout)).response

    @TestFactory
    fun alignmentAndStatus() = listOf(
        source("appStart client ID starts at byte 8") {
            val packet = PacketBuilder.appStart("Test")
            assertEquals(hex("0103202020202020"), packet.prefix(8)); assertEquals(Bytes.utf8("Test"), packet.slice(8, packet.size))
        },
        source("appStart truncates long client ID") {
            val packet = PacketBuilder.appStart("LongClientName"); assertEquals(Bytes.utf8("LongC"), packet.slice(8, packet.size)); assertEquals(13, packet.size)
        },
        source("appStart default client ID") {
            val packet = PacketBuilder.appStart()
            assertEquals(Bytes.utf8("MCore"), packet.slice(8, packet.size))
        },
        source("statusResponse skips reserved byte") {
            val body = push(); assertEquals(59, body.size); val response = status(body)
            assertEquals(nodePrefix, response.publicKeyPrefix); assertEquals(1000L, response.battery)
            assertEquals(5L, response.txQueueLength); assertEquals(-110L, response.noiseFloor); assertEquals(-85L, response.lastRSSI)
        },
        source("statusResponse rejects short payload") { expectFailure(Parsers.StatusResponse.parse(filled(size = 50))) },
        source("statusResponse handles max values") {
            val body = Bytes.of(0) + filled(0xff, 6) + ByteWriter().appendUInt16LE(1500u).appendUInt16LE(0u)
                .appendInt16LE(-120).appendInt16LE(-42).append(filled(size = 44)).toBytes()
            val response = status(body); assertEquals(1500L, response.battery); assertEquals(-120L, response.noiseFloor); assertEquals(-42L, response.lastRSSI)
        },
        source("statusResponse parseFromBinaryResponse valid payload") {
            assertEquals(StatusResponse(
                nodePrefix, 1000, 5, -110, -85, 100u, 200u, 10_000u, 600u,
                10u, 20u, 30u, 40u, 3, 10.0, 2, 1, 20_000u,
            ), binary(statusBody()))
        },
        source("statusResponse parseFromBinaryResponse rejects short payload") { assertNull(Parsers.StatusResponse.parseFromBinaryResponse(filled(size = 47), nodePrefix)) },
        source("statusResponse parseFromBinaryResponse handles minimal payload") {
            val response = binary(hex("e803") + filled(size = 46), prefix = hex("112233445566"))
            assertEquals(1000L, response.battery); assertEquals(0u, response.rxAirtime); assertEquals(0u, response.receiveErrors)
        },
        source("battery response rejects partial extended payload") { expectFailure(PacketParser.parse(hex("0ce803010203")), reason = "partial extended payload") },
        source("statusResponse parseFromBinaryResponse 52 bytes parses rxAirtime") {
            val response = binary(hex("e803") + filled(size = 46) + le(5000u), prefix = hex("112233445566"))
            assertEquals(1000L, response.battery); assertEquals(5000u, response.rxAirtime); assertEquals(0u, response.receiveErrors)
        },
        source("statusResponse parseFromBinaryResponse room server 52 bytes parses room counters") {
            val body = statusBody(
                3900u, 2u, -115, -87, listOf(120u, 45u, 3600u, 7200u, 12u, 8u, 14u, 10u),
                3u, 24, 1u, 2u, hex("11000900"),
            )
            val response = binary(body, StatusResponse.Layout.ROOM_SERVER, hex("112233445566"))
            assertEquals(StatusResponse.Layout.ROOM_SERVER, response.layout)
            assertEquals(17u.toUShort(), response.roomServerPostedCount); assertEquals(9u.toUShort(), response.roomServerPostPushCount)
            assertEquals(0u, response.rxAirtime); assertEquals(0u, response.receiveErrors)
        },
        source("statusResponse parseFromBinaryResponse 56 bytes parses receiveErrors") {
            val response = binary(hex("e803") + filled(size = 46) + le(5000u) + le(42u), prefix = hex("112233445566"))
            assertEquals(1000L, response.battery); assertEquals(5000u, response.rxAirtime); assertEquals(42u, response.receiveErrors)
        },
        source("statusResponse parseFromBinaryResponse accepts mid-length trailers") {
            for (size in listOf(49, 50, 51, 53, 54, 55)) assertEquals(1000L, binary(hex("e803") + filled(size = size - 2)).battery)
        },
        source("statusResponse parseFromBinaryResponse rejects below base size") {
            for (size in listOf(0, 12, 47)) assertNull(Parsers.StatusResponse.parseFromBinaryResponse(filled(size = size), nodePrefix))
        },
        source("statusResponse parseFromBinaryResponse handles extra data") {
            val response = binary(hex("e803") + filled(size = 50) + le(7u) + filled(size = 4), prefix = hex("112233445566"))
            assertEquals(1000L, response.battery); assertEquals(7u, response.receiveErrors)
        },
        source("statusResponse parse legacy size defaults receiveErrors") {
            val body = push()
            assertEquals(59, body.size)
            val response = status(body); assertEquals(1000L, response.battery); assertEquals(0u, response.receiveErrors)
        },
        source("statusResponse parse extended size parses receiveErrors") {
            val body = push() + le(99u); assertEquals(63, body.size)
            val response = status(body); assertEquals(1000L, response.battery); assertEquals(99u, response.receiveErrors)
        },
        source("statusResponse parse room server layout decodes post counters") {
            val body = push(tail = hex("11000900"))
            assertEquals(59, body.size)
            val response = status(body, StatusResponse.Layout.ROOM_SERVER)
            assertEquals(StatusResponse.Layout.ROOM_SERVER, response.layout)
            assertEquals(17u.toUShort(), response.roomServerPostedCount); assertEquals(9u.toUShort(), response.roomServerPostPushCount)
            assertEquals(0u, response.rxAirtime); assertEquals(0u, response.receiveErrors); assertEquals(1000L, response.battery)
        },
        source("statusResponse parse defaults to repeater layout") {
            val body = push(tail = hex("11000900"))
            assertEquals(59, body.size)
            val response = status(body)
            assertEquals(StatusResponse.Layout.REPEATER, response.layout); assertEquals(0x00090011u, response.rxAirtime)
            assertNull(response.roomServerPostedCount); assertNull(response.roomServerPostPushCount)
        },
        source("binaryRequest telemetry includes permission mask payload") {
            val packet = PacketBuilder.binaryRequest(filled(0xab, 32), BinaryRequestType.TELEMETRY, filled(size = 4))
            assertEquals(38, packet.size); assertEquals(3u.toUByte(), packet[33]); assertEquals(filled(size = 4), packet.slice(34, 38))
        },
    )

    @TestFactory
    fun binaryLists() = listOf(
        source("neighboursParser parses valid response") {
            val body = hex("03000200112233443c00000028aabbccdd78000000f0")
            val response = NeighboursParser.parse(body, hex("010203040506"), requestTag)
            assertEquals(3L, response.totalCount); assertEquals(2, response.neighbours.size)
            assertEquals(Neighbour(hex("11223344"), 60, 10.0), response.neighbours[0])
            assertEquals(Neighbour(hex("aabbccdd"), 120, -4.0), response.neighbours[1])
        },
        source("neighboursParser handles empty response") {
            val response = NeighboursParser.parse(hex("00000000"), hex("010203040506"), requestTag)
            assertEquals(0L, response.totalCount); assertEquals(0, response.neighbours.size)
        },
        source("neighboursParser handles short payload") {
            val response = NeighboursParser.parse(hex("0100"), hex("010203040506"), requestTag)
            assertEquals(0L, response.totalCount); assertEquals(0, response.neighbours.size)
        },
        source("neighboursParser handles 6-byte prefix length") {
            val response = NeighboursParser.parse(hex("010001001122334455661e00000014"), hex("010203040506"), requestTag, 6)
            assertEquals(1, response.neighbours.size); assertEquals(Neighbour(hex("112233445566"), 30, 5.0), response.neighbours[0])
        },
        source("collectingAllPages aggregates every page until the total is reached") {
            runTest {
                val response = NeighboursResponse.collectingAllPages { page(34, it.toInt(), 11) }
                assertEquals(34L, response.totalCount); assertEquals(34, response.neighbours.size)
                assertEquals((0L until 34L).toList(), response.neighbours.map { it.secondsAgo })
            }
        },
        source("collectingAllPages stops when a stalled page returns no rows") {
            runTest {
                val response = NeighboursResponse.collectingAllPages {
                    if (it == 0.toUShort()) page(34, 0, 11) else NeighboursResponse(Bytes.of(1), Bytes.of(2), 34, emptyList())
                }
                assertEquals(11, response.neighbours.size); assertEquals(34L, response.totalCount)
            }
        },
        source("collectingAllPages returns a single complete page without over-fetching") {
            runTest {
                var calls = 0
                val response = NeighboursResponse.collectingAllPages { calls++; page(5, it.toInt(), 20) }
                assertEquals(5, response.neighbours.size); assertEquals(5L, response.totalCount); assertEquals(1, calls)
            }
        },
        source("ACL parser parses valid response") {
            val entries = ACLParser.parse(hex("11223344556601aabbccddeeff03"))
            assertEquals(2, entries.size); assertEquals(hex("112233445566"), entries[0].keyPrefix)
            assertEquals(1u.toUByte(), entries[0].permissions); assertEquals(nodePrefix, entries[1].keyPrefix); assertEquals(3u.toUByte(), entries[1].permissions)
        },
        source("ACL parser skips null entries") {
            val entries = ACLParser.parse(hex("1122334455660100000000000000aabbccddeeff02"))
            assertEquals(2, entries.size); assertEquals(hex("112233445566"), entries[0].keyPrefix); assertEquals(nodePrefix, entries[1].keyPrefix)
        },
        source("MMA parser parses temperature entry") {
            val entries = MMAParser.parse(hex("016700c8012c00fa")); assertEquals(1, entries.size)
            val entry = entries.single(); assertEquals(1u.toUByte(), entry.channel); assertEquals("Temperature", entry.type)
            assertEquals(20.0, entry.min, 0.001); assertEquals(30.0, entry.max, 0.001); assertEquals(25.0, entry.avg, 0.001)
        },
        source("MMA parser parses humidity entry") {
            val entry = MMAParser.parse(hex("0268649682")).single(); assertEquals("Humidity", entry.type)
            assertEquals(50.0, entry.min, 0.001); assertEquals(75.0, entry.max, 0.001); assertEquals(65.0, entry.avg, 0.001)
        },
        source("telemetryResponse parseFromBinaryResponse valid payload") {
            val response = Parsers.TelemetryResponse.parseFromBinaryResponse(hex("016700fa"), nodePrefix)
            assertEquals(nodePrefix, response.publicKeyPrefix); assertEquals(hex("016700fa"), response.rawData)
            assertEquals(1, response.dataPoints.size); assertEquals(1u.toUByte(), response.dataPoints.single().channel)
        },
        source("telemetryResponse parseFromBinaryResponse empty payload") {
            assertEquals(0, Parsers.TelemetryResponse.parseFromBinaryResponse(Bytes.EMPTY, nodePrefix).dataPoints.size)
        },
    )

    private fun page(total: Int, start: Int, size: Int) = NeighboursResponse(
        Bytes.of(1), Bytes.of(2), total.toLong(),
        (start until minOf(start + size, total)).map { Neighbour(Bytes.of(it and 255), it.toLong(), it.toDouble()) },
    )
}
