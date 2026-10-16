package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatDetailEmptyIconTest {

    @Test
    fun pathMapUnavailableWhenNoLocatedHops() {
        assertTrue(shouldShowPathMapUnavailable(locatedPointCount = 0))
        assertFalse(shouldShowPathMapUnavailable(locatedPointCount = 1))
    }
}
