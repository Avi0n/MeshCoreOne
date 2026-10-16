package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.InMemoryNodeSnapshotStore
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.NodeSnapshotStore
import one.meshcore.shared.remotenode.testContact
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.room.roomSession
import one.meshcore.shared.room.ui.RoomStatusViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemotePersistenceLifetimeTest {
    @Test
    fun retiredRepeaterCannotSaveCurveAfterSuspendedContactLookup() = runTest {
        verifyCurveBoundary(room = false, retire = true)
    }

    @Test
    fun retiredRoomCannotSaveCurveAfterSuspendedContactLookup() = runTest {
        verifyCurveBoundary(room = true, retire = true)
    }

    @Test
    fun replacedServiceCannotSaveCurveAfterSuspendedContactLookup() = runTest {
        verifyCurveBoundary(room = false, retire = false)
    }

    @Test
    fun retiredStatusCannotCaptureAfterNonCooperativeBaselineRead() = runTest {
        val baseline = CompletableDeferred<NodeStatusSnapshotDto?>()
        val backing = InMemoryNodeSnapshotStore()
        var reads = 0
        val store = object : NodeSnapshotStore by backing {
            override suspend fun previousStatusSnapshot(
                nodePublicKey: ByteArray,
                before: Double,
            ): NodeStatusSnapshotDto? {
                reads++
                return withContext(NonCancellable) { baseline.await() }
            }
        }
        val snapshots = NodeSnapshotService(store)
        val harness = RemoteAdminTestHarness(backgroundScope)
        val session = testSession(isConnected = true)
        harness.store.seedSession(session)
        val vm = NodeStatusViewModel(
            backgroundScope,
            session,
            NodeStatusViewModel.Dependencies(
                repeaterAdminService = { harness.repeater },
                nodeSnapshotService = { snapshots },
            ),
        )
        vm.setStatusExpanded(true)
        runCurrent()
        assertEquals(1, reads)
        vm.close()
        val state = vm.state.value
        baseline.complete(null)
        runCurrent()
        assertEquals(state, vm.state.value)
        assertNull(backing.fetchLatestSnapshot(session.publicKey)?.uptimeSeconds)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.verifyCurveBoundary(room: Boolean, retire: Boolean) {
    val backing = InMemoryContactStore()
    val contact = testContact()
    backing.saveContact(contact)
    val gate = CompletableDeferred<Unit>()
    var writes = 0
    var reads = 0
    val store = object : ContactStore by backing {
        override suspend fun fetchContact(id: Uuid): ContactDto? {
            reads++
            withContext(NonCancellable) { gate.await() }
            return backing.fetchContact(id)
        }
        override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? =
            backing.updateContactFields(id) { current ->
                val changed = update(current)
                writes++
                changed
            }
    }
    var currentService: ContactService? = ContactService(NoOpContactSession, store, now = { 0L })
    val select: () -> Unit
    val close: () -> Unit
    val fields: () -> OcvCurveState
    if (room) {
        val vm = RoomStatusViewModel(
            backgroundScope,
            roomSession(),
            RoomStatusViewModel.Dependencies(
                roomAdminService = { null },
                contactService = { currentService },
                radioId = { contact.radioID },
            ),
        )
        vm.setBatteryCurveExpanded(true)
        select = { vm.selectOcvPreset(OCVPreset.LI_FE_PO4) }
        close = vm::close
        fields = { vm.state.value }
    } else {
        val vm = NodeStatusViewModel(
            backgroundScope,
            testSession(),
            NodeStatusViewModel.Dependencies(
                repeaterAdminService = { null },
                contactService = { currentService },
                radioId = { contact.radioID },
            ),
        )
        vm.setBatteryCurveExpanded(true)
        select = { vm.selectOcvPreset(OCVPreset.LI_FE_PO4) }
        close = vm::close
        fields = { vm.state.value }
    }
    runCurrent()
    assertTrue(fields().ocvLoaded)
    select()
    runCurrent()
    assertEquals(1, reads)
    if (retire) close() else currentService = ContactService(NoOpContactSession, backing, now = { 0L })
    val retired = fields()
    gate.complete(Unit)
    runCurrent()
    assertEquals(0, writes)
    assertNull(fields().ocvError)
    if (retire) assertEquals(retired, fields()) else assertFalse(fields().isSavingOcv)
}
