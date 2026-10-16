package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.MessageDirection
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MessagePathArrivalsTest {
    @Test
    fun incomingFirstAndExtraPathsAreOrderedByArrivalTime() {
        val message = ChatFixtures.message(text = "hello", channelIndex = 2).copy(
            direction = MessageDirection.INCOMING,
            pathNodes = byteArrayOf(0x11),
            createdAt = 100.0,
            heardRepeats = 2,
        )
        val early = repeat(message.id, 120.0, 0x22)
        val late = repeat(message.id, 140.0, 0x33)

        val arrivals = MessagePathArrivals.assemble(message, listOf(late, early))

        assertEquals(listOf(message.id, early.id, late.id), arrivals.map { it.id })
        assertEquals(listOf(true, false, false), arrivals.map { it.isFirst })
        assertContentEquals(byteArrayOf(0x11), arrivals.first().pathNodes)
        assertEquals(3, MessagePathArrivals.arrivalCount(message))
        assertEquals(early.id, MessagePathArrivals.resolvedSelection(early.id, arrivals))
        assertEquals(message.id, MessagePathArrivals.resolvedSelection(ChatFixtures.freshUuid(), arrivals))
    }

    @Test
    fun outgoingArrivalsContainOnlyEchoes() {
        val message = ChatFixtures.message(text = "hello", channelIndex = 2).copy(heardRepeats = 1)
        val heard = repeat(message.id, 120.0, 0x22)

        val arrivals = MessagePathArrivals.assemble(message, listOf(heard))

        assertEquals(listOf(heard.id), arrivals.map { it.id })
        assertEquals(listOf(false), arrivals.map { it.isFirst })
        assertEquals(1, MessagePathArrivals.arrivalCount(message))
    }

    private fun repeat(messageID: one.meshcore.shared.model.Uuid, at: Double, path: Int) = MessageRepeatDto(
        id = ChatFixtures.freshUuid(),
        messageID = messageID,
        receivedAt = at,
        pathNodes = byteArrayOf(path.toByte()),
        pathLength = 1,
    )
}
