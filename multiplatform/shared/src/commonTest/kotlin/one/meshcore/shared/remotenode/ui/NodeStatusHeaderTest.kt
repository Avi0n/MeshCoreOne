package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.model.RoomPermissionLevel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeStatusHeaderTest {
    @Test
    fun guestModeCaptionOnlyForGuest() {
        assertTrue(showsStatusGuestMode(RoomPermissionLevel.GUEST))
        assertFalse(showsStatusGuestMode(RoomPermissionLevel.READ_WRITE))
        assertFalse(showsStatusGuestMode(RoomPermissionLevel.ADMIN))
    }
}
