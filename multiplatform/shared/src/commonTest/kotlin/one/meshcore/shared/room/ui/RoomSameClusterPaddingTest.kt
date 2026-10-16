package one.meshcore.shared.room.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RoomSameClusterPaddingTest {
    @Test
    fun clusteredFollowUpGetsTwoDp() {
        assertEquals(2, roomSameClusterTopPaddingDp(showTimestamp = false))
        assertEquals(0, roomSameClusterTopPaddingDp(showTimestamp = true))
    }
}
