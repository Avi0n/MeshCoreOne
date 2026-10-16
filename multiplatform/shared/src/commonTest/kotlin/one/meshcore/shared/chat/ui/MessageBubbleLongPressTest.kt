package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Press-in delay matches iOS `MessageActionsPresentation`: 100 ms on press with motion, none on
 * release or when Reduce Motion is on.
 */
class MessageBubbleLongPressTest {
    @Test
    fun pressInWaitsUnlessReduceMotion() {
        assertEquals(100L, messageBubblePressInDelayMs(isPressing = true, reduceMotion = false))
        assertEquals(0L, messageBubblePressInDelayMs(isPressing = true, reduceMotion = true))
        assertEquals(0L, messageBubblePressInDelayMs(isPressing = false, reduceMotion = false))
        assertEquals(0L, messageBubblePressInDelayMs(isPressing = false, reduceMotion = true))
    }

    @Test
    fun springStiffnessMatchesIosResponse() {
        assertEquals(80.57f, MESSAGE_BUBBLE_LONG_PRESS_SPRING_STIFFNESS, absoluteTolerance = 0.01f)
        assertEquals(0.95f, MESSAGE_BUBBLE_LONG_PRESS_SCALE)
    }
}
