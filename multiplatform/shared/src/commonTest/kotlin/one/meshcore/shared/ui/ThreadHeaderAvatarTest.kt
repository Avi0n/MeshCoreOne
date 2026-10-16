package one.meshcore.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS `navigationHeader(titleIcon:)` uses a 30-pt identity avatar in the conversation/room bar. */
class ThreadHeaderAvatarTest {
    @Test
    fun threadHeaderAvatarMatchesIosSize() {
        assertEquals(30, THREAD_HEADER_AVATAR_DP)
    }
}
