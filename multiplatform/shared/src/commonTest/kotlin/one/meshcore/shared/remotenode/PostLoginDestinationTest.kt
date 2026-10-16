package one.meshcore.shared.remotenode

import one.meshcore.shared.model.RemoteNodeRole
import kotlin.test.Test
import kotlin.test.assertEquals

class PostLoginDestinationTest {
    @Test
    fun joinARoomOpensTheThread() {
        assertEquals(
            PostLoginDestination.ROOM,
            postLoginDestination(RemoteNodeRole.ROOM_SERVER, isAdmin = false, NodeAuthPurpose.JOIN),
        )
    }

    @Test
    fun joinARepeaterOpensStatus() {
        assertEquals(
            PostLoginDestination.STATUS,
            postLoginDestination(RemoteNodeRole.REPEATER, isAdmin = true, NodeAuthPurpose.JOIN),
        )
    }

    @Test
    fun manageAnAdminOpensSettings() {
        assertEquals(
            PostLoginDestination.SETTINGS,
            postLoginDestination(RemoteNodeRole.REPEATER, isAdmin = true, NodeAuthPurpose.MANAGE),
        )
        assertEquals(
            PostLoginDestination.SETTINGS,
            postLoginDestination(RemoteNodeRole.ROOM_SERVER, isAdmin = true, NodeAuthPurpose.MANAGE),
        )
    }

    @Test
    fun manageAGuestOpensStatus() {
        assertEquals(
            PostLoginDestination.STATUS,
            postLoginDestination(RemoteNodeRole.REPEATER, isAdmin = false, NodeAuthPurpose.MANAGE),
        )
        assertEquals(
            PostLoginDestination.STATUS,
            postLoginDestination(RemoteNodeRole.ROOM_SERVER, isAdmin = false, NodeAuthPurpose.MANAGE),
        )
    }

    @Test
    fun telemetryAlwaysOpensStatus() {
        assertEquals(
            PostLoginDestination.STATUS,
            postLoginDestination(RemoteNodeRole.ROOM_SERVER, isAdmin = false, NodeAuthPurpose.TELEMETRY),
        )
        assertEquals(
            PostLoginDestination.STATUS,
            postLoginDestination(RemoteNodeRole.REPEATER, isAdmin = true, NodeAuthPurpose.TELEMETRY),
        )
    }
}
