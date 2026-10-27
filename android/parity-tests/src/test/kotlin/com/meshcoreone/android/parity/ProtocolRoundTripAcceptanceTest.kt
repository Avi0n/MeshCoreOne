// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/RoundTripTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.parser.Parsers
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ProtocolRoundTripAcceptanceTest {
    @OriginalCase("RoundTripTests::Contact round trip()")
    @OriginalCase("RoundTripTests::Contact rejects reserved path length encoding()")
    @OriginalCase("RoundTripTests::SelfInfo rejects truncated fixed-width payload()")
    @OriginalCase("RoundTripTests::ContactMessage rejects truncated signature payload()")
    @Test fun originalBoundaryFramesRetainExactSourceInputs() {
        val contactFrame = Bytes.fromHex(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                "aaaa01020311223300000000000000000000000000000000000000000000" +
                "000000000000000000000000000000000000000000000000000000000000" +
                "00000000000000000054657374436f6e7461637400000000000000000000" +
                "000000000000000000000080009265346640023807b4f880009265"
        )
        assertEquals(147, contactFrame.size)
        val contact = when (val event = Parsers.Contact.parse(contactFrame)) {
            is MeshEvent.Contact -> event.contact
            else -> error("Expected actual contact event, got $event")
        }
        assertEquals(Bytes(ByteArray(32) { 0xAA.toByte() }), contact.publicKey)
        assertEquals(ContactType.CHAT, contact.type)
        assertEquals(ContactFlags(2u), contact.flags)
        assertEquals(3u.toUByte(), contact.outPathLength)
        assertEquals(Bytes.of(0x11, 0x22, 0x33), contact.outPath)
        assertEquals("TestContact", contact.advertisedName)
        assertEquals(37.7749, contact.latitude, 0.0001)
        assertEquals(-122.4194, contact.longitude, 0.0001)
        assertEquals(Instant.ofEpochSecond(1_704_067_200), contact.lastAdvertisement)
        assertEquals(Instant.ofEpochSecond(1_704_067_200), contact.lastModified)

        val reservedFrame = Bytes(ByteBuffer.allocate(147)
            .put(ByteArray(32) { 0xAA.toByte() }).put(1).put(0).put(0xC1.toByte())
            .put(ByteArray(112)).array())
        assertParseFailure(Parsers.Contact.parse(reservedFrame), reservedFrame,
            "reserved path length encoding")

        val truncatedSelf = Bytes.fromHex(
            "01141eaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                "aaaaaaaaaa00000000000000000201000138f60d0090d00300"
        )
        assertEquals(55, truncatedSelf.size)
        assertParseFailure(Parsers.SelfInfo.parse(truncatedSelf), truncatedSelf,
            "SelfInfo response too short")

        val truncatedSignature = Bytes(ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
            .put(0).putShort(0).put(byteArrayOf(1, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte()))
            .put(0).put(2).putInt(1_704_067_200)
            .put(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte())).array())
        assertParseFailure(Parsers.ContactMessage.parse(truncatedSignature,
            Parsers.ContactMessage.Version.V3), truncatedSignature, "signature truncated")
    }

    private fun assertParseFailure(event: MeshEvent, frame: Bytes, reason: String) {
        when (event) {
            is MeshEvent.ParseFailure -> {
                assertEquals(frame, event.data)
                assertTrue(event.reason, event.reason.contains(reason))
            }
            else -> error("Expected actual parse failure, got $event")
        }
    }
}
