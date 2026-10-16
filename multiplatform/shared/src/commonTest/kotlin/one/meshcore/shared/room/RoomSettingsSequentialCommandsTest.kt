package one.meshcore.shared.room

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.remotenode.NodeSettingsController
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.ScriptedCliSender
import one.meshcore.shared.remotenode.SettingsApplyResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RoomSettingsSequentialCommandsTest {
    @Test
    fun roomAccessFetchContinuesAfterThePasswordTimesOut() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { command ->
                when (command) {
                    "get guest.password" -> throw RemoteNodeError.Timeout
                    "get allow.read.only" -> "on"
                    else -> error("Unexpected command: $command")
                }
            }
        }
        val controller = controller(sender)

        controller.fetchRoomAccess()

        assertContentEquals(listOf("get guest.password", "get allow.read.only"), sender.sent)
        assertEquals(null, controller.guestPassword)
        assertEquals(true, controller.allowReadOnly)
        assertTrue(controller.roomAccessError)
        assertTrue(controller.roomAccessLoaded)
        assertFalse(controller.roomAccessModified)
    }

    @Test
    fun behaviorFetchContinuesAfterTheAdvertIntervalTimesOut() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { command ->
                when (command) {
                    "get advert.interval" -> throw RemoteNodeError.Timeout
                    "get flood.advert.interval" -> "2"
                    "get flood.max" -> "3"
                    else -> error("Unexpected command: $command")
                }
            }
        }
        val controller = controller(sender)

        controller.fetchBehaviorSettings()

        assertContentEquals(listOf("get advert.interval", "get flood.advert.interval", "get flood.max"), sender.sent)
        assertEquals(null, controller.advertIntervalMinutes)
        assertEquals(2, controller.floodAdvertIntervalHours)
        assertEquals(3, controller.floodMaxHops)
        assertTrue(controller.behaviorError)
        assertTrue(controller.behaviorLoaded)
        assertFalse(controller.behaviorSettingsModified)
        assertTrue(controller.handleLateResponse("45"))
        assertEquals(45, controller.advertIntervalMinutes)
        assertFalse(controller.behaviorError)
        assertFalse(controller.behaviorSettingsModified)
    }

    @Test
    fun roomAccessPartialApplyRetriesOnlyTheRejectedPassword() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { command -> if (command == "set guest.password secret") "ERROR" else "OK" }
        }
        val controller = controller(sender).apply {
            guestPassword = "secret"
            allowReadOnly = true
        }

        assertIs<SettingsApplyResult.PartialFailure>(controller.applyRoomAccess())

        assertContentEquals(listOf("set guest.password secret", "set allow.read.only on"), sender.sent)
        assertTrue(controller.roomAccessModified)
        sender.sent.clear()
        sender.respond = { "OK" }

        assertIs<SettingsApplyResult.Applied>(controller.applyRoomAccess())

        assertContentEquals(listOf("set guest.password secret"), sender.sent)
        assertFalse(controller.roomAccessModified)
    }

    @Test
    fun behaviorPartialApplyRetriesOnlyTheRejectedAdvertInterval() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { command -> if (command == "set advert.interval 60") "ERROR" else "OK" }
        }
        val controller = controller(sender).apply {
            advertIntervalMinutes = 60
            floodAdvertIntervalHours = 24
            floodMaxHops = 3
        }

        assertIs<SettingsApplyResult.PartialFailure>(controller.applyBehaviorSettings())

        assertContentEquals(
            listOf("set advert.interval 60", "set flood.advert.interval 24", "set flood.max 3"),
            sender.sent,
        )
        assertTrue(controller.behaviorSettingsModified)
        sender.sent.clear()
        sender.respond = { "OK" }

        assertIs<SettingsApplyResult.Applied>(controller.applyBehaviorSettings())

        assertContentEquals(listOf("set advert.interval 60"), sender.sent)
        assertFalse(controller.behaviorSettingsModified)
    }

    @Test
    fun roomAccessTransportFailureStopsBeforeTheReadOnlyWrite() = runTest {
        val failure = RemoteNodeError.SendFailed("radio unavailable")
        val sender = ScriptedCliSender().apply { respond = { throw failure } }
        val controller = controller(sender).apply {
            guestPassword = "secret"
            allowReadOnly = true
        }

        val result = assertIs<SettingsApplyResult.TransportError>(controller.applyRoomAccess())

        assertSame(failure, result.error)
        assertContentEquals(listOf("set guest.password secret"), sender.sent)
        assertTrue(controller.roomAccessModified)
    }

    @Test
    fun behaviorTransportFailureStopsBeforeTheFloodWrites() = runTest {
        val failure = RemoteNodeError.SendFailed("radio unavailable")
        val sender = ScriptedCliSender().apply { respond = { throw failure } }
        val controller = controller(sender).apply {
            advertIntervalMinutes = 60
            floodAdvertIntervalHours = 24
            floodMaxHops = 3
        }

        val result = assertIs<SettingsApplyResult.TransportError>(controller.applyBehaviorSettings())

        assertSame(failure, result.error)
        assertContentEquals(listOf("set advert.interval 60"), sender.sent)
        assertTrue(controller.behaviorSettingsModified)
    }

    private fun controller(sender: ScriptedCliSender): RoomSettingsController =
        RoomSettingsController(NodeSettingsController(sender.send, sender.send), sender.send, sender.send)
}
