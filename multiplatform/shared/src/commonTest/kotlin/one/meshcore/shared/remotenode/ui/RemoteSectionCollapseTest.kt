package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.room.FakeRoomAdminSession
import one.meshcore.shared.room.RoomAdminSession
import one.meshcore.shared.room.roomSession
import one.meshcore.shared.room.ui.setContactInfoExpanded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSectionCollapseTest {
    @Test
    fun repeaterCollapseAndReopenKeepThePendingSectionRequest() = runTest {
        val radio = FakeNodeSession().apply { ownerInfoError = RemoteNodeError.Timeout }
        val harness = RemoteAdminTestHarness(backgroundScope, nodeSession = radio)
        val session = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        harness.store.seedSession(session)
        val settings = harness.workspaces.family(session).repeaterSettings()
        runCurrent()
        settings.setContactInfoExpanded(true)
        runCurrent()
        assertTrue(settings.state.value.isLoadingContactInfo)
        settings.setContactInfoExpanded(false)
        settings.setContactInfoExpanded(true)
        runCurrent()
        assertEquals(1, radio.sendCommandCalls.size)
        assertTrue(settings.state.value.isLoadingContactInfo)

        radio.emitCliResponse("00|Retained reading")
        runCurrent()
        assertEquals("Retained reading", settings.state.value.ownerInfo)
        assertTrue(settings.state.value.contactInfoLoaded)
        assertEquals(1, radio.sendCommandCalls.size)
    }

    @Test
    fun roomCollapseAndReopenKeepThePendingSectionRequest() = runTest {
        val reply = CompletableDeferred<String>()
        var calls = 0
        val transport = object : RoomAdminSession by FakeRoomAdminSession() {
            override suspend fun sendCommand(publicKey: ByteArray, command: String, timeout: Duration): String {
                calls++
                return reply.await()
            }
        }
        val harness = RemoteAdminTestHarness(backgroundScope, roomSession = transport)
        val session = roomSession(permissionLevel = RoomPermissionLevel.ADMIN)
        harness.store.seedSession(session)
        val settings = harness.workspaces.family(session).roomSettings()
        settings.setContactInfoExpanded(true)
        runCurrent()
        assertTrue(settings.state.value.isLoadingContactInfo)
        settings.setContactInfoExpanded(false)
        settings.setContactInfoExpanded(true)
        runCurrent()
        assertEquals(1, calls)
        assertTrue(settings.state.value.isLoadingContactInfo)

        reply.complete("Retained reading")
        runCurrent()
        assertEquals("Retained reading", settings.state.value.ownerInfo)
        assertTrue(settings.state.value.contactInfoLoaded)
        assertEquals(1, calls)
    }
}
