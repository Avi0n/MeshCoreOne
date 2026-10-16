package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.meshcore.OwnerInfoResponse
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodeSnapshotStore
import one.meshcore.shared.remotenode.NodeSession
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.testContact
import one.meshcore.shared.remotenode.testOwnerInfoResponse
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.remotenode.testUuid
import one.meshcore.shared.room.FakeRoomAdminSession
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.room.roomSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteAdminWorkspaceTest {
    @Test
    fun remintedSessionAndNameKeepSettingsAndCliDrafts() = runTest {
        val harness = RemoteAdminTestHarness(backgroundScope)
        val original = testSession()
        val family = harness.workspaces.family(original)
        val settings = family.repeaterSettings()
        val cli = family.cli(remoteCliMessages)
        settings.setOwnerInfo("Saved draft")
        cli.setInput("get name")
        val renamed = original.copy(id = testUuid(2), name = "Renamed node")
        val reopened = harness.workspaces.family(renamed)

        assertSame(family, reopened)
        assertSame(settings, reopened.repeaterSettings())
        assertSame(cli, reopened.cli(remoteCliMessages))
        assertEquals("Saved draft", settings.state.value.ownerInfo)
        assertEquals("get name", cli.state.value.currentInput)
        assertTrue(cli.state.value.promptText.startsWith("@Renamed node"))
    }

    @Test
    fun fullKeyRoleAndRadioPartitionAreIndependentAndCopied() = runTest {
        val harness = RemoteAdminTestHarness(backgroundScope)
        val original = testSession()
        val copy = original.copy(publicKey = original.publicKey.copyOf())
        val family = harness.workspaces.family(original)
        val changedTail = copy.copy(publicKey = copy.publicKey.copyOf().also { it[it.lastIndex]++ })
        assertNotSame(family, harness.workspaces.family(changedTail))
        assertNotSame(family, harness.workspaces.family(copy.copy(role = RemoteNodeRole.ROOM_SERVER)))
        assertNotSame(family, harness.workspaces.family(copy.copy(radioID = testUuid(100))))
        original.publicKey[0]++
        assertSame(family, harness.workspaces.family(copy))
    }

    @Test
    fun openingSettingsDoesNotStartStatusReads() = runTest {
        val node = FakeNodeSession()
        val harness = RemoteAdminTestHarness(backgroundScope, nodeSession = node)
        val session = testSession(isConnected = true)
        harness.store.seedSession(session)
        val family = harness.workspaces.family(session)
        runCurrent()
        assertTrue(node.requestOwnerInfoCalls.isEmpty())
        val settings = family.repeaterSettings()
        runCurrent()
        assertEquals(0, node.requestOwnerInfoCalls.size)
        settings.setContactInfoExpanded(true)
        runCurrent()
        assertEquals(1, node.requestOwnerInfoCalls.size)
        assertTrue(node.requestStatusCalls.isEmpty())
        assertTrue(node.requestTelemetryCalls.isEmpty())
        assertTrue(node.requestNeighboursCalls.isEmpty())
    }

    @Test
    fun resetRejectsAReadingThatIgnoresCancellation() = runTest {
        val reply = CompletableDeferred<OwnerInfoResponse>()
        val node = object : NodeSession by FakeNodeSession() {
            override suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse =
                withContext(NonCancellable) { reply.await() }
        }
        val harness = RemoteAdminTestHarness(backgroundScope, nodeSession = node)
        val session = testSession(isConnected = true)
        harness.store.seedSession(session)
        val old = harness.workspaces.family(session).repeaterSettings()
        old.setContactInfoExpanded(true)
        runCurrent()
        harness.workspaces.reset()
        runCurrent()
        val replacement = harness.workspaces.family(session).repeaterSettings()
        replacement.setContactInfoExpanded(true)
        old.setOwnerInfo("Old draft")
        replacement.setOwnerInfo("New draft")
        runCurrent()
        reply.complete(testOwnerInfoResponse(ownerInfo = "Remote reading"))
        runCurrent()

        assertNotSame(old, replacement)
        assertEquals("Old draft", old.state.value.ownerInfo)
        assertEquals("New draft", replacement.state.value.ownerInfo)
        assertFalse(old.state.value.contactInfoLoaded)
        assertTrue(replacement.state.value.deviceInfoLoaded)
        assertFalse(old.state.value.deviceInfoLoaded)
    }

    @Test
    fun staleRadioFamiliesCannotReadOrSaveThroughReplacementGraph() = runTest {
        val node = FakeNodeSession()
        val room = FakeRoomAdminSession()
        val harness = RemoteAdminTestHarness(backgroundScope, nodeSession = node, roomSession = room)
        val original = testSession(isConnected = true)
        harness.workspaces.family(original)
        harness.workspaces.reset()
        runCurrent()
        harness.radioId = testUuid(100)
        val contact = testContact(radioID = testUuid(100))
        val backing = InMemoryContactStore()
        backing.saveContact(contact)
        var contactReads = 0
        var contactWrites = 0
        val store = object : ContactStore by backing {
            override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? {
                contactReads++
                return backing.fetchContact(radioID, publicKey)
            }
            override suspend fun saveContact(dto: ContactDto) {
                contactWrites++
                backing.saveContact(dto)
            }
            override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? =
                backing.updateContactFields(id) { current ->
                    val changed = update(current)
                    contactWrites++
                    changed
                }
        }
        harness.currentContacts = ContactService(NoOpContactSession, store, now = { 0L })
        val snapshots = InMemoryNodeSnapshotStore()
        harness.currentSnapshots = NodeSnapshotService(snapshots)
        val staleRepeater = harness.workspaces.family(original).repeaterStatus()
        val staleRoom = harness.workspaces.family(original.copy(role = RemoteNodeRole.ROOM_SERVER)).roomStatus()
        staleRepeater.setStatusExpanded(true)
        staleRoom.setStatusExpanded(true)
        runCurrent()
        staleRepeater.selectOcvPreset(OCVPreset.LI_FE_PO4)
        staleRoom.selectOcvPreset(OCVPreset.LI_FE_PO4)
        runCurrent()
        assertTrue(node.requestStatusCalls.isEmpty())
        assertTrue(room.statusCalls.isEmpty())
        assertEquals(0, contactReads)
        assertEquals(0, contactWrites)
        assertTrue(snapshots.fetchSnapshots(original.publicKey, null).isEmpty())

        val current = original.copy(radioID = testUuid(100))
        harness.store.seedSession(current)
        val freshRepeater = harness.workspaces.family(current).repeaterStatus()
        val freshRoomSession = roomSession(id = testUuid(3), radioID = testUuid(100))
        harness.store.seedSession(freshRoomSession)
        harness.currentRoom = RoomAdminService(room, harness.store)
        val freshRoom = harness.workspaces.family(freshRoomSession).roomStatus()
        freshRepeater.setStatusExpanded(true)
        freshRoom.setStatusExpanded(true)
        runCurrent()
        assertTrue(node.requestStatusCalls.isNotEmpty())
        assertTrue(room.statusCalls.isNotEmpty())
        assertTrue(contactReads >= 2)
        freshRepeater.selectOcvPreset(OCVPreset.LI_FE_PO4)
        runCurrent()
        assertEquals(1, contactWrites)
    }

    @Test
    fun obsoleteResetCannotRetireTheCurrentWorkspace() = runTest {
        val harness = RemoteAdminTestHarness(backgroundScope)
        val session = testSession()
        val settings = harness.workspaces.family(session).repeaterSettings()
        harness.workspaces.reset { false }
        runCurrent()
        assertSame(settings, harness.workspaces.family(session).repeaterSettings())
        harness.workspaces.reset()
        runCurrent()
        assertNotSame(settings, harness.workspaces.family(session).repeaterSettings())
    }
}
