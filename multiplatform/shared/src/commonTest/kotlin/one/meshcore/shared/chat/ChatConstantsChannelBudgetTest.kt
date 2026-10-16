package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatConstantsChannelBudgetTest {
    @Test
    fun composedCapIsTheLastNineBlockSize() {
        assertEquals(139, ChatConstants.MAX_CHANNEL_MESSAGE_TOTAL_LENGTH_BYTES)
        assertEquals(133, ChatConstants.maxChannelMessageLength(nodeNameByteCount = 4))
        assertEquals(106, ChatConstants.maxChannelMessageLength(nodeNameByteCount = 31))
        assertEquals(0, ChatConstants.maxChannelMessageLength(nodeNameByteCount = 200))
        assertEquals(106, ChatConstants.maxChannelMessageLength(nodeName = null))
        assertEquals(133, ChatConstants.maxChannelMessageLength(nodeName = "Node"))
    }

    @Test
    fun regionOneHopAtTheCapFitsA172ByteCompanionFrame() {
        val frame = ChatConstants.groupTextRxLogFrameByteCount(
            composedUTF8Count = ChatConstants.MAX_CHANNEL_MESSAGE_TOTAL_LENGTH_BYTES,
            includesTransportCodes = true,
            pathByteCount = ChatConstants.MIN_HEARD_REPEAT_PATH_BYTES,
        )
        assertEquals(157, frame)
        assertTrue(frame <= ChatConstants.RX_LOG_HEARD_REPEAT_FRAME_BUDGET)
    }

    @Test
    fun regionOneHopAt147Overflows172AndStillFits176() {
        val frame = ChatConstants.groupTextRxLogFrameByteCount(
            composedUTF8Count = 147,
            includesTransportCodes = true,
            pathByteCount = ChatConstants.MIN_HEARD_REPEAT_PATH_BYTES,
        )
        assertEquals(173, frame)
        assertTrue(frame > ChatConstants.RX_LOG_HEARD_REPEAT_FRAME_BUDGET)
        assertTrue(frame <= ChatConstants.MAX_FRAME_SIZE)
    }
}
