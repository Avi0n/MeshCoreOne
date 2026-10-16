package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** iOS `MessageBubbleView` paints the unavailable caption when `resolver.message(item)` is nil. */
class UnavailableMessageCaptionTest {
    @Test
    fun aMissingDtoUsesTheUnavailableCaption() {
        assertTrue(threadRowShowsUnavailableCaption(hasDto = false))
        assertFalse(threadRowShowsUnavailableCaption(hasDto = true))
    }
}
