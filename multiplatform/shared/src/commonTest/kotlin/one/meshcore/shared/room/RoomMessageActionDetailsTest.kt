package one.meshcore.shared.room

import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.remotenode.testUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoomMessageActionDetailsTest {
    @Test
    fun incomingShowsSentReceivedAndStatus() {
        val message =
            roomMessage(
                id = testUuid(1),
                isFromSelf = false,
                statusRawValue = MessageStatus.DELIVERED.raw,
            ).copy(createdAt = 1_720_000_100.0)

        val lines = RoomMessageActionDetails.lines(message)

        assertEquals(3, lines.size)
        assertIs<RoomMessageActionDetails.Line.Sent>(lines[0])
        assertEquals(1_720_000_000.0, (lines[0] as RoomMessageActionDetails.Line.Sent).epochSeconds)
        assertIs<RoomMessageActionDetails.Line.Received>(lines[1])
        assertEquals(1_720_000_100.0, (lines[1] as RoomMessageActionDetails.Line.Received).epochSeconds)
        val status = assertIs<RoomMessageActionDetails.Line.Status>(lines[2])
        assertEquals(MessageStatus.DELIVERED, status.status)
    }

    @Test
    fun outgoingShowsSentStatusAndOptionalRoundTrip() {
        val withRtt =
            roomMessage(
                id = testUuid(2),
                isFromSelf = true,
                statusRawValue = MessageStatus.SENT.raw,
            ).copy(roundTripTime = 842)
        val withoutRtt =
            roomMessage(
                id = testUuid(3),
                isFromSelf = true,
                statusRawValue = MessageStatus.PENDING.raw,
            )

        val withLines = RoomMessageActionDetails.lines(withRtt)
        assertEquals(3, withLines.size)
        assertIs<RoomMessageActionDetails.Line.Sent>(withLines[0])
        assertIs<RoomMessageActionDetails.Line.Status>(withLines[1])
        val rtt = assertIs<RoomMessageActionDetails.Line.RoundTrip>(withLines[2])
        assertEquals(842L, rtt.milliseconds)

        val bare = RoomMessageActionDetails.lines(withoutRtt)
        assertEquals(2, bare.size)
        assertTrue(bare.none { it is RoomMessageActionDetails.Line.RoundTrip })
        assertTrue(bare.none { it is RoomMessageActionDetails.Line.Received })
    }
}
