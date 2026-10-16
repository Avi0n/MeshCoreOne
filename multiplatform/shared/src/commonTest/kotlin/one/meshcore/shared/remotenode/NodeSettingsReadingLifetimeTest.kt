package one.meshcore.shared.remotenode

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.room.RoomSettingsController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeSettingsReadingLifetimeTest {
    @Test
    fun initialAndUneditedOwnerInfoReadsAdoptTogether() {
        val controller = NodeSettingsController({ _, _ -> "OK" }, { _, _ -> "OK" })
        controller.setNodeInfo("1.0", "North", "Owner")
        controller.setNodeInfo("1.1", "Renamed north", "Updated owner")

        assertEquals("Renamed north", controller.name)
        assertEquals(controller.name, controller.originalName)
        assertEquals("Updated owner", controller.ownerInfo)
        assertEquals(controller.ownerInfo, controller.originalOwnerInfo)
    }

    @Test
    fun clearedFieldsRemainDraftsDuringOwnerInfoRefresh() {
        val controller = NodeSettingsController({ _, _ -> "OK" }, { _, _ -> "OK" })
        controller.setNodeInfo(null, "North", "Owner")
        controller.name = null
        controller.ownerInfo = null
        controller.setNodeInfo(null, "Renamed north", "Updated owner")

        assertNull(controller.name)
        assertNull(controller.ownerInfo)
        assertEquals("North", controller.originalName)
        assertEquals("Owner", controller.originalOwnerInfo)
    }

    @Test
    fun identityRefreshKeepsEditedCoordinatesAndRefreshesUneditedPeer() = runTest {
        var reading = 1.0
        val controller = NodeSettingsController(
            { command, _ -> if (command == "get name") "North" else reading.toString() },
            { _, _ -> "OK" },
        )
        controller.fetchIdentity()
        controller.latitude = 5.0
        reading = 2.0
        controller.fetchIdentity()

        assertEquals(5.0, controller.latitude)
        assertEquals(1.0, controller.originalLatitude)
        assertEquals(2.0, controller.longitude)
        assertEquals(2.0, controller.originalLongitude)
        assertTrue(controller.identitySettingsModified)
    }

    @Test
    fun radioRefreshKeepsEditedFrequencyAndRefreshesOtherFields() = runTest {
        var reading = "910.0,250.0,10,5"
        val controller = NodeSettingsController({ _, _ -> reading }, { _, _ -> "OK" })
        controller.fetchRadioSettings()
        controller.frequency = 915.0
        controller.radioSettingsModified = true
        reading = "920.0,125.0,9,6"
        controller.fetchRadioSettings()

        assertEquals(915.0, controller.frequency)
        assertEquals(125.0, controller.bandwidth)
        assertEquals(9, controller.spreadingFactor)
        assertEquals(6, controller.codingRate)
        assertTrue(controller.radioSettingsModified)
    }

    @Test
    fun lateLatitudeReadingCannotOverwriteAnEditedField() = runTest {
        var timeout = false
        val controller = NodeSettingsController(
            { command, _ ->
                if (timeout && command == "get lat") throw RemoteNodeError.Timeout
                if (command == "get name") "North" else "1.0"
            },
            { _, _ -> "OK" },
        )
        controller.fetchIdentity()
        controller.latitude = 5.0
        timeout = true
        controller.fetchIdentity()

        assertTrue(controller.handleCommonLateResponse("2.0"))
        assertEquals(5.0, controller.latitude)
        assertEquals(1.0, controller.originalLatitude)
    }

    @Test
    fun repeaterBehaviorRefreshKeepsEditedPeersDirty() = runTest {
        var reading = "1"
        val send: suspend (String, kotlin.time.Duration) -> String = { command, _ ->
            if (command == "get repeat") "on" else reading
        }
        val controller = RepeaterSettingsController(NodeSettingsController(send, send), send, send)
        controller.fetchBehaviorSettings()
        controller.repeaterEnabled = false
        controller.advertIntervalMinutes = 10
        controller.floodAdvertIntervalHours = 2
        controller.floodMaxHops = 3
        reading = "4"
        controller.fetchBehaviorSettings()

        assertEquals(false, controller.repeaterEnabled)
        assertEquals(10, controller.advertIntervalMinutes)
        assertEquals(2, controller.floodAdvertIntervalHours)
        assertEquals(3, controller.floodMaxHops)
        assertTrue(controller.behaviorSettingsModified)
    }

    @Test
    fun roomAccessAndBehaviorRefreshKeepEditedFields() = runTest {
        var password = "Original guest"
        var readOnly = "off"
        var reading = "1"
        val send: suspend (String, kotlin.time.Duration) -> String = { command, _ ->
            when (command) {
                "get guest.password" -> password
                "get allow.read.only" -> readOnly
                else -> reading
            }
        }
        val controller = RoomSettingsController(NodeSettingsController(send, send), send, send)
        controller.fetchRoomAccess()
        controller.fetchBehaviorSettings()
        controller.guestPassword = "Edited guest"
        controller.allowReadOnly = true
        controller.advertIntervalMinutes = 10
        controller.floodAdvertIntervalHours = 2
        controller.floodMaxHops = 3
        password = "Updated guest"
        readOnly = "off"
        reading = "4"
        controller.fetchRoomAccess()
        controller.fetchBehaviorSettings()

        assertEquals("Edited guest", controller.guestPassword)
        assertEquals(true, controller.allowReadOnly)
        assertEquals(10, controller.advertIntervalMinutes)
        assertEquals(2, controller.floodAdvertIntervalHours)
        assertEquals(3, controller.floodMaxHops)
        assertTrue(controller.roomAccessModified)
        assertTrue(controller.behaviorSettingsModified)
    }
}
