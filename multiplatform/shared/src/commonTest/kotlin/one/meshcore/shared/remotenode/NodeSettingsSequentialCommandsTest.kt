package one.meshcore.shared.remotenode

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NodeSettingsSequentialCommandsTest {
    @Test
    fun identityPrefetchPopulatesTheNameBeforeTheOptionalQuery() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { command ->
                when (command) {
                    "get lat" -> "1.0"
                    "get lon" -> "2.0"
                    else -> error("Unexpected command: $command")
                }
            }
        }
        val controller = node(sender)
        var prefetches = 0
        controller.onPreFetchNodeInfo = {
            prefetches++
            controller.setNodeInfo(firmwareVersion = null, name = "Cached", ownerInfo = null)
        }

        controller.fetchIdentity()
        controller.fetchIdentity()

        assertEquals(1, prefetches)
        assertContentEquals(listOf("get lat", "get lon", "get lat", "get lon"), sender.sent)
        assertEquals("Cached", controller.name)
        assertEquals("Cached", controller.originalName)
        assertEquals(1.0, controller.latitude)
        assertEquals(2.0, controller.longitude)
        assertFalse(controller.identitySettingsModified)
        assertFalse(controller.identityError)
    }

    @Test
    fun devicePrefetchPopulatesTheVersionBeforeTheOptionalQuery() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { command ->
                check(command == "clock") { "Unexpected command: $command" }
                CLOCK_REPLY
            }
        }
        val controller = node(sender)
        var prefetches = 0
        controller.onPreFetchNodeInfo = {
            prefetches++
            controller.setNodeInfo(firmwareVersion = "1.2.3", name = null, ownerInfo = null)
        }

        controller.fetchDeviceInfo()
        controller.fetchDeviceInfo()

        assertEquals(1, prefetches)
        assertContentEquals(listOf("clock", "clock"), sender.sent)
        assertEquals("1.2.3", controller.firmwareVersion)
        assertEquals(CLOCK_REPLY, controller.deviceTimeUtcString)
        assertTrue(controller.deviceInfoLoaded)
        assertFalse(controller.deviceInfoError)
    }

    @Test
    fun identityFetchContinuesAfterTheNameTimesOut() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { command ->
                when (command) {
                    "get name" -> throw RemoteNodeError.Timeout
                    "get lat" -> "1.0"
                    "get lon" -> "2.0"
                    else -> error("Unexpected command: $command")
                }
            }
        }
        val controller = node(sender)

        controller.fetchIdentity()

        assertContentEquals(listOf("get name", "get lat", "get lon"), sender.sent)
        assertEquals(null, controller.name)
        assertEquals(1.0, controller.originalLatitude)
        assertEquals(2.0, controller.originalLongitude)
        assertTrue(controller.identityError)
        assertFalse(controller.identitySettingsModified)
    }

    @Test
    fun deviceFetchContinuesAfterTheVersionTimesOut() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { command ->
                when (command) {
                    "ver" -> throw RemoteNodeError.Timeout
                    "clock" -> CLOCK_REPLY
                    else -> error("Unexpected command: $command")
                }
            }
        }
        val controller = node(sender)

        controller.fetchDeviceInfo()

        assertContentEquals(listOf("ver", "clock"), sender.sent)
        assertEquals(null, controller.firmwareVersion)
        assertEquals(CLOCK_REPLY, controller.deviceTimeUtcString)
        assertTrue(controller.deviceInfoLoaded)
        assertTrue(controller.deviceInfoError)
    }

    @Test
    fun repeaterFetchContinuesAfterRepeatModeTimesOut() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { command ->
                when (command) {
                    "get repeat" -> throw RemoteNodeError.Timeout
                    "get advert.interval" -> "120"
                    "get flood.advert.interval" -> "24"
                    "get flood.max" -> "3"
                    else -> error("Unexpected command: $command")
                }
            }
        }
        val controller = repeater(sender)

        controller.fetchBehaviorSettings()

        assertContentEquals(
            listOf("get repeat", "get advert.interval", "get flood.advert.interval", "get flood.max"),
            sender.sent,
        )
        assertEquals(null, controller.repeaterEnabled)
        assertEquals(120, controller.advertIntervalMinutes)
        assertEquals(24, controller.floodAdvertIntervalHours)
        assertEquals(3, controller.floodMaxHops)
        assertTrue(controller.behaviorError)
        assertFalse(controller.behaviorSettingsModified)
        assertTrue(controller.handleLateResponse("on"))
        assertEquals(true, controller.repeaterEnabled)
        assertFalse(controller.behaviorError)
        assertFalse(controller.behaviorSettingsModified)
    }

    @Test
    fun repeaterPartialApplyRetriesOnlyTheRejectedRepeatMode() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { command -> if (command == "set repeat off") "ERROR" else "OK" }
        }
        val controller = editedRepeater(sender)

        assertIs<SettingsApplyResult.PartialFailure>(controller.applyBehaviorSettings())

        assertContentEquals(
            listOf("set repeat off", "set advert.interval 60", "set flood.advert.interval 24", "set flood.max 3"),
            sender.sent,
        )
        assertTrue(controller.behaviorSettingsModified)
        sender.sent.clear()
        sender.respond = { "OK" }

        assertIs<SettingsApplyResult.Applied>(controller.applyBehaviorSettings())

        assertContentEquals(listOf("set repeat off"), sender.sent)
        assertFalse(controller.behaviorSettingsModified)
    }

    @Test
    fun repeaterTransportFailureStopsBeforeTheIntervalWrites() = runTest {
        val failure = RemoteNodeError.SendFailed("radio unavailable")
        val sender = ScriptedCliSender().apply { respond = { throw failure } }
        val controller = editedRepeater(sender)

        val result = assertIs<SettingsApplyResult.TransportError>(controller.applyBehaviorSettings())

        assertSame(failure, result.error)
        assertContentEquals(listOf("set repeat off"), sender.sent)
        assertTrue(controller.behaviorSettingsModified)
    }

    private fun node(sender: ScriptedCliSender) = NodeSettingsController(sender.send, sender.send)

    private fun repeater(sender: ScriptedCliSender) = RepeaterSettingsController(node(sender), sender.send, sender.send)

    private fun editedRepeater(sender: ScriptedCliSender) = repeater(sender).apply {
        repeaterEnabled = false
        advertIntervalMinutes = 60
        floodAdvertIntervalHours = 24
        floodMaxHops = 3
    }

    private companion object {
        const val CLOCK_REPLY = "06:40 - 18/4/2025 UTC"
    }
}
