package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.room.RoomSettingsController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsCancellationLifetimeTest {
    @Test
    fun cancelledContactApplyCannotCommitANoncooperativeAck() = runTest {
        val reply = CompletableDeferred<String>()
        val controller = NodeSettingsController(
            { _, _ -> withContext(NonCancellable) { reply.await() } },
            { _, _ -> "OK" },
        )
        controller.setNodeInfo(null, null, "Original owner")
        controller.ownerInfo = "Sent owner"
        val apply = async { controller.applyContactInfoSettings() }
        runCurrent()
        apply.cancel()
        reply.complete("OK")

        assertFailsWith<CancellationException> { apply.await() }
        assertEquals("Original owner", controller.originalOwnerInfo)
        assertTrue(controller.contactInfoSettingsModified)
    }

    @Test
    fun cancelledBehaviorKeepsEarlierAckAndStopsLaterCommands() = runTest {
        val reply = CompletableDeferred<String>()
        val commands = mutableListOf<String>()
        var blockAdvert = true
        val send: suspend (String, kotlin.time.Duration) -> String = { command, _ ->
            commands += command
            if (command.startsWith("set advert.interval") && blockAdvert) {
                withContext(NonCancellable) { reply.await() }
            } else {
                "OK"
            }
        }
        val controller = RepeaterSettingsController(NodeSettingsController(send, send), send, send)
        controller.repeaterEnabled = true
        controller.advertIntervalMinutes = 60
        controller.floodAdvertIntervalHours = 3
        controller.floodMaxHops = 3
        val apply = async { controller.applyBehaviorSettings() }
        runCurrent()
        apply.cancel()
        reply.complete("OK")
        assertFailsWith<CancellationException> { apply.await() }
        assertEquals(listOf("set repeat on", "set advert.interval 60"), commands)
        commands.clear()
        blockAdvert = false

        assertEquals(SettingsApplyResult.Applied, controller.applyBehaviorSettings())
        assertEquals(listOf("set advert.interval 60", "set flood.advert.interval 3", "set flood.max 3"), commands)
    }

    @Test
    fun cancelledTimeoutCannotRegisterLateRecovery() = runTest {
        val reply = CompletableDeferred<Unit>()
        val controller = NodeSettingsController(
            { _, _ ->
                withContext(NonCancellable) { reply.await() }
                throw RemoteNodeError.Timeout
            },
            { _, _ -> "OK" },
        )
        val read = async { controller.fetchRadioSettings() }
        runCurrent()
        read.cancel()
        reply.complete(Unit)

        assertFailsWith<CancellationException> { read.await() }
        assertFalse(controller.handleCommonLateResponse("910.0,250.0,10,5"))
        assertNull(controller.frequency)
        assertFalse(controller.radioError)
    }

    @Test
    fun cancelledRoomPasswordReadCannotPublishItsLateValue() = runTest {
        val reply = CompletableDeferred<String>()
        val commands = mutableListOf<String>()
        val raw: suspend (String, kotlin.time.Duration) -> String = { command, _ ->
            commands += command
            withContext(NonCancellable) { reply.await() }
        }
        val controller = RoomSettingsController(NodeSettingsController({ _, _ -> "OK" }, raw), { _, _ -> "OK" }, raw)
        val read = async { controller.fetchRoomAccess() }
        runCurrent()
        read.cancel()
        reply.complete("Late guest")

        assertFailsWith<CancellationException> { read.await() }
        assertEquals(listOf("get guest.password"), commands)
        assertNull(controller.guestPassword)
        assertNull(controller.allowReadOnly)
    }
}
