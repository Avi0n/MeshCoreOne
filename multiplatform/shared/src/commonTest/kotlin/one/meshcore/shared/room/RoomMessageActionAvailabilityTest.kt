package one.meshcore.shared.room

import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.testUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The room message action-availability rules (W5 spec §10.2), a pure port of Swift
 * `RoomMessageActionAvailability`. Reply needs a postable session and a foreign author; Send-DM needs a
 * named foreign author; Send-again is only for our own messages; Copy is always available.
 */
class RoomMessageActionAvailabilityTest {
    @Test
    fun ownMessageOffersOnlyCopyAndSendAgain() {
        val availability = RoomMessageActionAvailability.of(
            roomMessage(testUuid(10), isFromSelf = true),
            roomSession(),
        )
        assertTrue(availability.canSendAgain)
        assertFalse(availability.canReply)
        assertFalse(availability.canSendDm)
        assertEquals(
            listOf(RoomMessageAction.COPY, RoomMessageAction.TRANSLATE, RoomMessageAction.SEND_AGAIN),
            availability.availableActions,
        )
    }

    @Test
    fun foreignMessageInPostableRoomOffersReplyAndDm() {
        val availability = RoomMessageActionAvailability.of(
            roomMessage(testUuid(10), isFromSelf = false, authorName = "Alice"),
            roomSession(permissionLevel = RoomPermissionLevel.READ_WRITE),
        )
        assertTrue(availability.canReply)
        assertTrue(availability.canSendDm)
        assertFalse(availability.canSendAgain)
        assertEquals(
            listOf(
                RoomMessageAction.REPLY,
                RoomMessageAction.SEND_DM,
                RoomMessageAction.COPY,
                RoomMessageAction.TRANSLATE,
            ),
            availability.availableActions,
        )
    }

    @Test
    fun replyRequiresPostPermission() {
        val availability = RoomMessageActionAvailability.of(
            roomMessage(testUuid(10), isFromSelf = false, authorName = "Alice"),
            roomSession(permissionLevel = RoomPermissionLevel.GUEST),
        )
        assertFalse(availability.canReply)
        assertTrue(availability.canSendDm)
    }

    @Test
    fun sendDmRequiresKnownAuthor() {
        val availability = RoomMessageActionAvailability.of(
            roomMessage(testUuid(10), isFromSelf = false, authorName = null),
            roomSession(),
        )
        assertFalse(availability.canSendDm)
    }
}
