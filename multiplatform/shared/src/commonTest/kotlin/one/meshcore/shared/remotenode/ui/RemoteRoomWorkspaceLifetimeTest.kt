package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.room.FakeRoomAdminSession
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.room.RoomAdminSession
import one.meshcore.shared.room.roomContact
import one.meshcore.shared.room.roomSession
import one.meshcore.shared.room.ui.applyContactInfo
import one.meshcore.shared.room.ui.loadContactInfo
import one.meshcore.shared.room.ui.loadRadio
import one.meshcore.shared.room.ui.setOwnerInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteRoomWorkspaceLifetimeTest {
    @Test
    fun hiddenApplyKeepsLaterDraftAndSuccessUntilShown() = runTest {
        val ack = CompletableDeferred<String>()
        val transport = ControlledRoomSession { command ->
            if (command.startsWith("set ")) ack.await() else "Original owner"
        }
        val harness = RemoteAdminTestHarness(backgroundScope, roomSession = transport)
        val session = roomSession(permissionLevel = RoomPermissionLevel.ADMIN)
        harness.store.seedSession(session)
        val settings = harness.workspaces.family(session).roomSettings()
        settings.loadContactInfo()
        runCurrent()
        val mount = settings.settingsShown()
        settings.setOwnerInfo("Submitted owner")
        settings.applyContactInfo()
        runCurrent()
        settings.settingsHidden(mount)
        settings.setOwnerInfo("Later draft")
        ack.complete("OK")
        runCurrent()
        advanceTimeBy(APPLY_SUCCESS_FLASH_MS * 2)
        runCurrent()

        assertSame(settings, harness.workspaces.family(session).roomSettings())
        assertEquals("Later draft", settings.state.value.ownerInfo)
        assertTrue(settings.state.value.contactInfoModified)
        assertFalse(settings.state.value.isApplyingContactInfo)
        assertTrue(settings.state.value.contactInfoApplySuccess)
        settings.settingsShown()
        runCurrent()
        advanceTimeBy(APPLY_SUCCESS_FLASH_MS)
        runCurrent()
        assertFalse(settings.state.value.contactInfoApplySuccess)
        assertEquals(listOf("get owner.info", "set owner.info Submitted owner"), transport.commands)
    }

    @Test
    fun anOutgoingMountCannotHideTheIncomingMountsFeedback() = runTest {
        val harness =
            RemoteAdminTestHarness(
                backgroundScope,
                roomSession = FakeRoomAdminSession().apply {
                    commandResponse =
                        "OK"
                },
            )
        val session = roomSession(permissionLevel = RoomPermissionLevel.ADMIN)
        harness.store.seedSession(session)
        val settings = harness.workspaces.family(session).roomSettings()
        settings.loadContactInfo()
        runCurrent()
        val outgoing = settings.settingsShown()
        settings.settingsShown()
        settings.settingsHidden(outgoing)
        settings.setOwnerInfo("Changed")
        settings.applyContactInfo()
        runCurrent()
        assertTrue(settings.state.value.contactInfoApplySuccess)
        advanceTimeBy(APPLY_SUCCESS_FLASH_MS)
        runCurrent()
        assertFalse(settings.state.value.contactInfoApplySuccess)
    }

    @Test
    fun sameRadioReplacementUsesLiveServiceAndKeepsDraft() = runTest {
        val oldTransport = ControlledRoomSession { "Original" }
        val harness = RemoteAdminTestHarness(backgroundScope, roomSession = oldTransport)
        val session = roomSession(permissionLevel = RoomPermissionLevel.ADMIN)
        harness.store.seedSession(session)
        val settings = harness.workspaces.family(session).roomSettings()
        settings.loadContactInfo()
        runCurrent()
        settings.setOwnerInfo("Retained draft")
        val replacement = ControlledRoomSession { "OK" }
        harness.currentRoom = RoomAdminService(replacement, harness.store)
        harness.workspaces.reattach()
        runCurrent()
        assertSame(settings, harness.workspaces.family(session).roomSettings())
        settings.applyContactInfo()
        runCurrent()
        assertEquals(listOf("get owner.info"), oldTransport.commands)
        assertEquals(listOf("set owner.info Retained draft"), replacement.commands)
        assertFalse(settings.state.value.contactInfoModified)
    }

    @Test
    fun resetRejectsNonCooperativeApplyAck() = runTest {
        val ack = CompletableDeferred<String>()
        val transport = ControlledRoomSession { command ->
            if (command.startsWith("set ")) withContext(NonCancellable) { ack.await() } else "Original"
        }
        val harness = RemoteAdminTestHarness(backgroundScope, roomSession = transport)
        val session = roomSession(permissionLevel = RoomPermissionLevel.ADMIN)
        harness.store.seedSession(session)
        val old = harness.workspaces.family(session).roomSettings()
        old.loadContactInfo()
        runCurrent()
        old.setOwnerInfo("Submitted")
        old.applyContactInfo()
        runCurrent()
        harness.workspaces.reset()
        runCurrent()
        val replacement = harness.workspaces.family(session).roomSettings()
        replacement.setOwnerInfo("New draft")
        val retiredState = old.state.value
        ack.complete("OK")
        runCurrent()
        assertEquals(retiredState, old.state.value)
        assertEquals("New draft", replacement.state.value.ownerInfo)
        assertFalse(replacement.state.value.contactInfoApplySuccess)
    }

    @Test
    fun lateRepliesRouteByFullKeyAndIgnoreReplacedService() = runTest {
        val transport = ControlledRoomSession { throw RemoteNodeError.Timeout }
        val harness = RemoteAdminTestHarness(backgroundScope, roomSession = transport)
        val first = roomSession(permissionLevel = RoomPermissionLevel.ADMIN)
        val second = first.copy(
            id = one.meshcore.shared.remotenode.testUuid(2),
            publicKey = testPublicKey().also {
                it[it.lastIndex]++
            },
        )
        harness.store.seedSession(first)
        harness.store.seedSession(second)
        val firstModel = harness.workspaces.family(first).roomSettings()
        val secondModel = harness.workspaces.family(second).roomSettings()
        firstModel.loadRadio()
        secondModel.loadRadio()
        harness.workspaces.reattach()
        runCurrent()
        harness.room.invokeCLIHandler(roomContact(publicKey = first.publicKey), "915,125,7,5")
        harness.workspaces.reattach()
        runCurrent()
        assertEquals("915.0", firstModel.state.value.frequencyText)
        assertEquals("", secondModel.state.value.frequencyText)
        harness.currentRoom = RoomAdminService(FakeRoomAdminSession(), harness.store)
        harness.workspaces.reattach()
        runCurrent()
        harness.room.invokeCLIHandler(roomContact(publicKey = second.publicKey), "930,125,7,5")
        runCurrent()
        assertEquals("", secondModel.state.value.frequencyText)
        harness.currentRoom!!.invokeCLIHandler(roomContact(publicKey = second.publicKey), "920,125,7,5")
        runCurrent()
        assertEquals("920.0", secondModel.state.value.frequencyText)
        assertNull(secondModel.state.value.applyBanner)
    }
}

private class ControlledRoomSession(private val send: suspend (String) -> String) :
    RoomAdminSession by FakeRoomAdminSession() {
    val commands = mutableListOf<String>()
    override suspend fun sendCommand(publicKey: ByteArray, command: String, timeout: Duration): String {
        commands += command
        return send(command)
    }
    override suspend fun sendRawCommand(publicKey: ByteArray, command: String, timeout: Duration): String =
        sendCommand(publicKey, command, timeout)
}
