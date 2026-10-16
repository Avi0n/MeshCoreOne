package one.meshcore.shared.ui

import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.StatusPillState
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_accessibility_connection_sync_failed_disconnecting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gates behind the P0 connection announcements. Each rule decides whether a screen reader speaks at
 * all, so a wrong answer here is silence for a responder who cannot see the pill.
 */
class AccessibilityAnnouncementsTest {
    // MARK: Connection lost

    @Test
    fun losingAnEstablishedLinkAnnounces() {
        assertTrue(
            isConnectionLost(DeviceConnectionState.READY, DeviceConnectionState.DISCONNECTED),
            "A ready radio dropping must announce the loss",
        )
        assertTrue(
            isConnectionLost(DeviceConnectionState.CONNECTED, DeviceConnectionState.DISCONNECTED),
            "A connected radio dropping before sync must announce the loss",
        )
    }

    @Test
    fun anAutoReconnectAnnouncesTheLossItIsRecoveringFrom() {
        assertTrue(
            isConnectionLost(DeviceConnectionState.SYNCING, DeviceConnectionState.CONNECTING),
            "Reconnecting reads as CONNECTING and the services are gone for its whole duration",
        )
    }

    @Test
    fun aLinkComingUpAnnouncesItself() {
        assertTrue(
            isDeviceReconnected(DeviceConnectionState.CONNECTING, DeviceConnectionState.CONNECTED),
            "Services wired is what iOS speaks on, and a first pairing announces the same as a recovery",
        )
        assertTrue(
            isDeviceReconnected(DeviceConnectionState.DISCONNECTED, DeviceConnectionState.CONNECTED),
            "Coming back from a drop must announce the recovery",
        )
    }

    @Test
    fun stayingConnectedAnnouncesNothing() {
        assertFalse(
            isDeviceReconnected(DeviceConnectionState.CONNECTED, DeviceConnectionState.SYNCING),
            "Moving between connected rungs is not a new link",
        )
        assertFalse(
            isDeviceReconnected(DeviceConnectionState.SYNCING, DeviceConnectionState.READY),
            "Sync finishing is not a new link",
        )
        assertFalse(
            isDeviceReconnected(DeviceConnectionState.DISCONNECTED, DeviceConnectionState.CONNECTING),
            "An attempt in flight has no services yet",
        )
    }

    @Test
    fun aConnectAttemptThatNeverLandedIsSilent() {
        assertFalse(
            isConnectionLost(DeviceConnectionState.CONNECTING, DeviceConnectionState.DISCONNECTED),
            "A failed connect never had services, so iOS handleDisconnect never runs for it",
        )
        assertFalse(
            isConnectionLost(DeviceConnectionState.DISCONNECTED, DeviceConnectionState.DISCONNECTED),
            "Staying disconnected is not a loss",
        )
    }

    @Test
    fun progressingThroughTheConnectedRungsIsSilent() {
        assertFalse(
            isConnectionLost(DeviceConnectionState.CONNECTED, DeviceConnectionState.SYNCING),
            "Sync starting is not a loss",
        )
        assertFalse(
            isConnectionLost(DeviceConnectionState.SYNCING, DeviceConnectionState.READY),
            "Sync completing is not a loss",
        )
    }

    // MARK: Cached-data announcement

    @Test
    fun aListOpeningOverCachedRowsAnnouncesOnlyWhenARadioIsKnown() {
        assertTrue(
            shouldAnnounceCachedData(DeviceConnectionState.DISCONNECTED, hasKnownRadio = true),
            "Disconnected with a paired radio means the rows on screen are frozen",
        )
        assertFalse(
            shouldAnnounceCachedData(DeviceConnectionState.DISCONNECTED, hasKnownRadio = false),
            "A first run with nothing paired has no cached data to warn about",
        )
    }

    @Test
    fun aListOpeningOnALiveRadioIsSilent() {
        assertFalse(
            shouldAnnounceCachedData(DeviceConnectionState.READY, hasKnownRadio = true),
            "A ready radio is feeding the list, so nothing is cached",
        )
        assertFalse(
            shouldAnnounceCachedData(DeviceConnectionState.CONNECTING, hasKnownRadio = true),
            "A connect in flight is not the disconnected state iOS guards on",
        )
    }

    // MARK: Room reconnect

    @Test
    fun aRoomComingBackAnnouncesOnceOnTheRisingEdge() {
        assertTrue(
            isRoomReconnected(previouslyConnected = false, isConnected = true),
            "The false-to-true edge is the moment the composer replaces the banner",
        )
        assertFalse(
            isRoomReconnected(previouslyConnected = true, isConnected = true),
            "A room that stayed connected must not repeat the announcement",
        )
    }

    @Test
    fun openingAnAlreadyConnectedRoomIsSilent() {
        assertFalse(
            isRoomReconnected(previouslyConnected = true, isConnected = false),
            "Losing the room is not a reconnect",
        )
        assertFalse(
            isRoomReconnected(previouslyConnected = false, isConnected = false),
            "A room that never connected has nothing to announce",
        )
    }

    // MARK: Status-pill announcement text

    @Test
    fun theFailedPillSpeaksTheFullAnnouncementNotTheShortLabel() {
        assertEquals(
            Res.string.localizable_accessibility_connection_sync_failed_disconnecting,
            StatusPillState.Failed.announcementResource(),
            "The pill's visible label is terse; VoiceOver says 'Sync failed. Disconnecting.'",
        )
    }

    @Test
    fun theProgressRungsDoNotInterrupt() {
        // iOS marks the pill updatesFrequently, so VoiceOver reads these only under focus. Pushing each
        // one would make a reconnect say "Connecting", "Syncing", "Ready" over whatever the user is doing,
        // on top of the single sentence the connection announcer already speaks.
        listOf(
            StatusPillState.Connecting,
            StatusPillState.Syncing,
            StatusPillState.Ready,
            StatusPillState.Disconnected,
        ).forEach {
            assertNull(it.announcementResource(), "$it must not interrupt the screen reader")
        }
    }

    @Test
    fun theHiddenPillSaysNothing() {
        assertNull(
            StatusPillState.Hidden.announcementResource(),
            "The pill leaving the screen must not speak an empty announcement",
        )
    }
}
