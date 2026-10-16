package one.meshcore.shared.remotenode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.room.RoomSettingsController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NodeSettingsDraftLifetimeTest {
    @Test
    fun ownerInfoRefreshKeepsEditedFieldsAndTheirOriginals() {
        val controller = NodeSettingsController({ _, _ -> "OK" }, { _, _ -> "OK" })
        controller.setNodeInfo("1.0", "North", "Original owner")
        controller.name = "Edited north"
        controller.ownerInfo = "Edited owner"

        controller.setNodeInfo("1.1", "Renamed north", "Updated owner")

        assertEquals("Edited north", controller.name)
        assertEquals("Edited owner", controller.ownerInfo)
        assertEquals("North", controller.originalName)
        assertEquals("Original owner", controller.originalOwnerInfo)
        assertEquals("1.1", controller.firmwareVersion)
    }

    @Test
    fun pendingContactInfoApplyKeepsLaterDraftDirty() = runTest {
        val reply = CompletableDeferred<String>()
        val commands = mutableListOf<String>()
        val controller = NodeSettingsController(
            { command, _ ->
                commands += command
                reply.await()
            },
            { _, _ -> "OK" },
        )
        controller.setNodeInfo(null, null, "Original owner")
        controller.ownerInfo = "Sent owner"
        val apply = async { controller.applyContactInfoSettings() }
        runCurrent()
        controller.ownerInfo = "Later draft"
        reply.complete("OK")

        assertEquals(SettingsApplyResult.Applied, apply.await())
        assertEquals(listOf("set owner.info Sent owner"), commands)
        assertEquals("Sent owner", controller.originalOwnerInfo)
        assertEquals("Later draft", controller.ownerInfo)
        assertTrue(controller.contactInfoSettingsModified)
    }

    @Test
    fun pendingRadioApplyKeepsLaterFrequencyDirty() = runTest {
        val reply = CompletableDeferred<String>()
        val commands = mutableListOf<String>()
        val controller = NodeSettingsController(
            { command, _ ->
                commands += command
                reply.await()
            },
            { _, _ -> "OK" },
        )
        controller.frequency = 910.0
        controller.bandwidth = 250.0
        controller.spreadingFactor = 10
        controller.codingRate = 5
        controller.radioSettingsModified = true
        val apply = async { controller.applyRadioSettings() }
        runCurrent()
        controller.frequency = 915.0
        controller.radioSettingsModified = true
        reply.complete("OK")

        assertEquals(SettingsApplyResult.Applied, apply.await())
        assertEquals(listOf("set radio 910.0,250.0,10,5"), commands)
        assertEquals(915.0, controller.frequency)
        assertTrue(controller.radioSettingsModified)
    }

    @Test
    fun contactAckAfterEditingBackToOriginalStillKeepsDraftDirty() = runTest {
        val reply = CompletableDeferred<String>()
        val controller = NodeSettingsController({ _, _ -> reply.await() }, { _, _ -> "OK" })
        controller.setNodeInfo(null, null, "Original owner")
        controller.ownerInfo = "Sent owner"
        val apply = async { controller.applyContactInfoSettings() }
        runCurrent()
        controller.ownerInfo = "Original owner"
        reply.complete("OK")

        assertEquals(SettingsApplyResult.Applied, apply.await())
        assertEquals("Sent owner", controller.originalOwnerInfo)
        assertTrue(controller.contactInfoSettingsModified)
    }

    @Test
    fun rejectedContactApplyKeepsOriginalAndLaterDraft() = runTest {
        val reply = CompletableDeferred<String>()
        val controller = NodeSettingsController({ _, _ -> reply.await() }, { _, _ -> "OK" })
        controller.setNodeInfo(null, null, "Original owner")
        controller.ownerInfo = "Sent owner"
        val apply = async { controller.applyContactInfoSettings() }
        runCurrent()
        controller.ownerInfo = "Later draft"
        reply.complete("ERR: rejected")

        assertEquals(SettingsApplyResult.PartialFailure, apply.await())
        assertEquals("Original owner", controller.originalOwnerInfo)
        assertEquals("Later draft", controller.ownerInfo)
    }

    @Test
    fun unchangedContactAckAdvancesBaselineAndClearsDirty() = runTest {
        val controller = NodeSettingsController({ _, _ -> "OK" }, { _, _ -> "OK" })
        controller.setNodeInfo(null, null, "Original owner")
        controller.ownerInfo = "Sent owner"

        assertEquals(SettingsApplyResult.Applied, controller.applyContactInfoSettings())
        assertEquals("Sent owner", controller.originalOwnerInfo)
        assertFalse(controller.contactInfoSettingsModified)
    }

    @Test
    fun pendingPasswordAckKeepsNewCredentials() = runTest {
        val reply = CompletableDeferred<String>()
        val commands = mutableListOf<String>()
        val controller = NodeSettingsController(
            { _, _ -> "OK" },
            { command, _ ->
                commands += command
                reply.await()
            },
        )
        controller.newPassword = "sent password"
        controller.confirmPassword = "sent password"
        val apply = async { controller.changePassword() }
        runCurrent()
        controller.newPassword = "later password"
        controller.confirmPassword = "later confirmation"
        reply.complete("OK")

        assertEquals(PasswordApplyResult.Applied, apply.await())
        assertEquals(listOf("password sent password"), commands)
        assertEquals("later password", controller.newPassword)
        assertEquals("later confirmation", controller.confirmPassword)
    }

    @Test
    fun repeaterBehaviorSendsEntryValuesAndKeepsLaterDraft() = runTest {
        val reply = CompletableDeferred<String>()
        val commands = mutableListOf<String>()
        val send: suspend (String, kotlin.time.Duration) -> String = { command, _ ->
            commands += command
            if (commands.size == 1) reply.await() else "OK"
        }
        val controller = RepeaterSettingsController(NodeSettingsController(send, send), send, send)
        controller.repeaterEnabled = true
        controller.advertIntervalMinutes = 60
        controller.floodAdvertIntervalHours = 3
        controller.floodMaxHops = 3
        val apply = async { controller.applyBehaviorSettings() }
        runCurrent()
        controller.advertIntervalMinutes = 120
        controller.floodAdvertIntervalHours = 6
        controller.floodMaxHops = 5
        reply.complete("OK")

        assertEquals(SettingsApplyResult.Applied, apply.await())
        assertEquals(
            listOf("set repeat on", "set advert.interval 60", "set flood.advert.interval 3", "set flood.max 3"),
            commands,
        )
        assertTrue(controller.behaviorSettingsModified)
    }

    @Test
    fun roomAccessSendsEntryReadOnlyValueAndKeepsLaterDraft() = runTest {
        val reply = CompletableDeferred<String>()
        val commands = mutableListOf<String>()
        val send: suspend (String, kotlin.time.Duration) -> String = { command, _ ->
            commands += command
            if (commands.size == 1) reply.await() else "OK"
        }
        val controller = RoomSettingsController(NodeSettingsController(send, send), send, send)
        controller.guestPassword = "sent guest"
        controller.allowReadOnly = true
        val apply = async { controller.applyRoomAccess() }
        runCurrent()
        controller.allowReadOnly = false
        reply.complete("OK")

        assertEquals(SettingsApplyResult.Applied, apply.await())
        assertEquals(listOf("set guest.password sent guest", "set allow.read.only on"), commands)
        assertTrue(controller.roomAccessModified)
    }
}
