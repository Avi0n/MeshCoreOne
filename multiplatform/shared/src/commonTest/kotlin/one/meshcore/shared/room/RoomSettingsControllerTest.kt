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
import kotlin.test.assertTrue

/**
 * Room Access + Behavior fetch/apply diffing and late-response routing for [RoomSettingsController]. The
 * Swift `RoomSettingsViewModel` has no unit tests, so these add parse/diff/outcome coverage: the guest
 * password parse (status word → empty, echo strip), allow.read.only on/off, apply-only-changed-fields, the
 * "some settings failed" partial failure, behavior validation gating, and behavior late-response recovery.
 */
class RoomSettingsControllerTest {
    private fun controller(sender: ScriptedCliSender): RoomSettingsController {
        val helper = NodeSettingsController(sender.send, sender.send)
        return RoomSettingsController(helper, sender.send, sender.send)
    }

    // MARK: Room Access

    @Test
    fun fetchRoomAccessStoresGuestPasswordAndReadOnly() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get guest.password" -> "> s3cret"
                    "get allow.read.only" -> "on"
                    else -> "OK"
                }
            }
        }
        val controller = controller(sender)
        controller.fetchRoomAccess()

        assertEquals("s3cret", controller.guestPassword)
        assertEquals(true, controller.allowReadOnly)
        assertTrue(controller.roomAccessLoaded)
        assertFalse(controller.roomAccessModified)
    }

    @Test
    fun fetchRoomAccessStatusWordMeansEmptyPassword() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd -> if (cmd == "get allow.read.only") "off" else "OK" }
        }
        val controller = controller(sender)
        controller.fetchRoomAccess()

        assertEquals("", controller.guestPassword)
        assertEquals(false, controller.allowReadOnly)
    }

    @Test
    fun applyRoomAccessSendsOnlyChangedFields() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get guest.password" -> "> old"
                    "get allow.read.only" -> "off"
                    else -> "OK"
                }
            }
        }
        val controller = controller(sender)
        controller.fetchRoomAccess()
        sender.sent.clear()

        controller.guestPassword = "new"
        val result = controller.applyRoomAccess()

        assertIs<SettingsApplyResult.Applied>(result)
        assertContentEquals(listOf("set guest.password new"), sender.setCommands)
    }

    @Test
    fun applyRoomAccessPartialFailureWhenSetRejected() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when {
                    cmd == "get guest.password" -> "> old"
                    cmd == "get allow.read.only" -> "off"
                    cmd.startsWith("set guest.password") -> "ERROR"
                    else -> "OK"
                }
            }
        }
        val controller = controller(sender)
        controller.fetchRoomAccess()

        controller.guestPassword = "new"
        controller.allowReadOnly = true
        val result = controller.applyRoomAccess()

        assertIs<SettingsApplyResult.PartialFailure>(result)
        assertContentEquals(
            listOf("set guest.password new", "set allow.read.only on"),
            sender.setCommands,
        )
    }

    @Test
    fun roomAccessModifiedTracksEdits() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd -> if (cmd == "get guest.password") "> old" else "off" }
        }
        val controller = controller(sender)
        controller.fetchRoomAccess()
        assertFalse(controller.roomAccessModified)

        controller.allowReadOnly = true
        assertTrue(controller.roomAccessModified)
    }

    // MARK: Behavior

    @Test
    fun fetchBehaviorStoresValues() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get advert.interval" -> "120"
                    "get flood.advert.interval" -> "24"
                    "get flood.max" -> "8"
                    else -> "OK"
                }
            }
        }
        val controller = controller(sender)
        controller.fetchBehaviorSettings()

        assertEquals(120, controller.advertIntervalMinutes)
        assertEquals(24, controller.floodAdvertIntervalHours)
        assertEquals(8, controller.floodMaxHops)
        assertTrue(controller.behaviorLoaded)
        assertFalse(controller.behaviorSettingsModified)
    }

    @Test
    fun applyBehaviorSendsOnlyChangedFields() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get advert.interval" -> "120"
                    "get flood.advert.interval" -> "24"
                    "get flood.max" -> "8"
                    else -> "OK"
                }
            }
        }
        val controller = controller(sender)
        controller.fetchBehaviorSettings()
        sender.sent.clear()

        controller.floodMaxHops = 10
        val result = controller.applyBehaviorSettings()

        assertIs<SettingsApplyResult.Applied>(result)
        assertContentEquals(listOf("set flood.max 10"), sender.setCommands)
    }

    @Test
    fun applyBehaviorValidationFailureSendsNothing() = runTest {
        val sender = ScriptedCliSender()
        val controller = controller(sender)
        controller.advertIntervalMinutes = 30_000
        val result = controller.applyBehaviorSettings()

        assertIs<SettingsApplyResult.InvalidFields>(result)
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun handleLateResponseRefusesAnAmbiguousBehaviorReply() = runTest {
        val sender = ScriptedCliSender().apply { respond = { throw RemoteNodeError.Timeout } }
        val controller = controller(sender)
        controller.fetchBehaviorSettings()
        assertTrue(controller.behaviorError)

        // All three Behavior queries timed out and all three parse a bare integer, so "45" could answer
        // any of them. Adopting it would write an advert interval into the flood-hop limit.
        assertFalse(controller.handleLateResponse("45"))
        assertEquals(null, controller.advertIntervalMinutes)
        assertEquals(null, controller.floodMaxHops)
    }

    @Test
    fun handleLateResponseRecoversTheOneOutstandingBehaviorField() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    RoomSettingCommand.GET_ADVERT_INTERVAL -> throw RemoteNodeError.Timeout
                    RoomSettingCommand.GET_FLOOD_ADVERT_INTERVAL -> "2"
                    RoomSettingCommand.GET_FLOOD_MAX -> "3"
                    else -> "OK"
                }
            }
        }
        val controller = controller(sender)
        controller.fetchBehaviorSettings()
        assertTrue(controller.behaviorError)

        // Only the advert interval is still owed an answer, so "45" can only belong to it.
        assertTrue(controller.handleLateResponse("45"))
        assertEquals(45, controller.advertIntervalMinutes)
        // The section is complete again, so the error clears — as on iOS, which recomputes it here.
        assertFalse(controller.behaviorError)
    }

    @Test
    fun handleLateResponseIgnoresUnrelated() = runTest {
        val sender = ScriptedCliSender()
        val controller = controller(sender)
        assertFalse(controller.handleLateResponse("nothing to see"))
    }
}
