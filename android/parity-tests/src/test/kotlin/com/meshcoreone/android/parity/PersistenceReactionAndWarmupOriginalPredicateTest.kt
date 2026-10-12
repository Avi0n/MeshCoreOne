// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.reactions.ReactionParser
import com.meshcoreone.android.core.services.reactions.ReactionService
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PersistenceReactionAndWarmupOriginalPredicateTest : RoomProcessTest() {
    private suspend fun radio(): RadioId {
        val id = UUID.randomUUID()
        val dto = DeviceDTO(
            id = id, radioId = RadioId(id), publicKey = Bytes(ByteArray(32) { 0x44 }),
            nodeName = "TestDevice", firmwareVersion = 8u, firmwareVersionString = "v1.11.0",
            manufacturerName = "TestMfg", buildDate = "01 Jan 2025",
            latitude = 37.7749, longitude = -122.4194, multiAcks = 0u, isActive = false,
        )
        store.saveDevice(dto)
        return dto.radioId
    }

    private fun message(
        radio: RadioId, channel: UByte, text: String, timestamp: UInt,
        direction: MessageDirection, status: MessageStatus, sender: String?,
    ) = MessageDTO(
        id = UUID.randomUUID(), radioId = radio, contactID = null, channelIndex = channel,
        text = text, timestamp = timestamp, createdAt = Instant.ofEpochSecond(timestamp.toLong()),
        direction = direction, status = status, pathLength = 0u, snr = null,
        senderKeyPrefix = null, senderNodeName = sender, isRead = false, replyToID = null,
        roundTripTime = null, heardRepeats = 0, retryAttempt = 0, maxRetryAttempts = 0,
    )

    @OriginalCase("PersistenceStoreTests::Find channel message for reaction within timestamp window()")
    @Test fun original120IncomingRowsRetainRequiredTarget80ActualReactionBuilderParserWindow200AndFoundId() = runTest {
        val radio = radio()
        var target: MessageDTO? = null
        for (index in 0 until 120) {
            val dto = message(
                radio, 1u, "Message $index", 1_700_000_000u + index.toUInt(),
                MessageDirection.INCOMING, MessageStatus.DELIVERED, "RemoteNode",
            )
            store.saveMessage(dto)
            if (index == 80) target = dto
        }
        val requiredTarget = assertNotNull(target)
        val reaction = ReactionService().buildReactionText(
            "\uD83D\uDC4D", "RemoteNode", requiredTarget.text, requiredTarget.timestamp,
        )
        assertEquals("@[RemoteNode]\uD83D\uDC4D\ndxm1cz0e", reaction)
        val parsed = assertNotNull(ReactionParser.parse(reaction))
        assertEquals("dxm1cz0e", parsed.messageHash)
        val now = requiredTarget.timestamp
        val start = if (now > 300u) now - 300u else 0u
        val found = store.findChannelMessageForReaction(radio, 1u, parsed, "LocalNode", start..(now + 300u), 200)

        assertEquals(requiredTarget.id, found?.id)
    }

    @OriginalCase("PersistenceStoreTests::Find outgoing channel message for reaction using local node name()")
    @Test fun originalOutgoingLocalMessageRetainsNilSenderActualBuilderParserWindow200AndFoundId() = runTest {
        val radio = radio()
        val outgoing = message(
            radio, 2u, "Local message", 1_700_000_200u,
            MessageDirection.OUTGOING, MessageStatus.SENT, null,
        )
        store.saveMessage(outgoing)
        val reaction = ReactionService().buildReactionText(
            "\uD83D\uDD25", "LocalNode", outgoing.text, outgoing.timestamp,
        )
        assertEquals("@[LocalNode]\uD83D\uDD25\nz6j5gpmd", reaction)
        val parsed = assertNotNull(ReactionParser.parse(reaction))
        assertEquals("z6j5gpmd", parsed.messageHash)
        val now = outgoing.timestamp
        val start = if (now > 300u) now - 300u else 0u
        val found = store.findChannelMessageForReaction(radio, 2u, parsed, "LocalNode", start..(now + 300u), 200)

        assertEquals(outgoing.id, found?.id)
    }

    @OriginalCase("PersistenceStoreTests::Database warm-up()")
    @Test fun originalEmptyStoreWarmUpRetainsCompletedNonThrowingInvocation() = runTest {
        assertEquals(Unit, store.warmUp())
    }
}
