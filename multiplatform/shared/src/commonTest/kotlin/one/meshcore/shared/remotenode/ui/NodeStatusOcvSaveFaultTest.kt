package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.FakeContactSession
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.testSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed battery-curve save is shown on the section. Cancellation clears the saving flag. A thrown
 * error propagates and leaves the saving flag set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeStatusOcvSaveFaultTest {
    @Test
    fun aCancelledOcvSaveClearsTheSavingFlag() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val thrown = mutableListOf<Throwable>()
        val vm = viewModel(stopped, thrown)

        vm.selectOcvPreset(OCVPreset.LI_FE_PO4)

        assertFalse(vm.state.value.isSavingOcv)
        assertNull(vm.state.value.ocvError)
        assertTrue(thrown.isEmpty())
        assertTrue(vm.ocvJob!!.isCancelled)
    }

    @Test
    fun anOcvSaveFailureReportsTheSaveError() = runTest(UnconfinedTestDispatcher()) {
        val thrown = mutableListOf<Throwable>()
        val vm = viewModel(OcvSaveFailure(), thrown)

        vm.selectOcvPreset(OCVPreset.LI_FE_PO4)

        assertFalse(vm.state.value.isSavingOcv)
        assertEquals(
            NodeSectionError.Message(NodeStatusViewModel.OCV_SAVE_FAILED_KEY),
            vm.state.value.ocvError,
        )
        assertTrue(thrown.isEmpty())
    }

    @Test
    fun anOcvSaveErrorPropagatesAndLeavesTheSavingFlagSet() = runTest(UnconfinedTestDispatcher()) {
        val probe = OcvSaveProbeError()
        val thrown = mutableListOf<Throwable>()
        val vm = viewModel(probe, thrown)

        vm.selectOcvPreset(OCVPreset.LI_FE_PO4)

        assertTrue(vm.state.value.isSavingOcv)
        assertNull(vm.state.value.ocvError)
        assertSame(probe, thrown.single())
    }

    private suspend fun TestScope.viewModel(failure: Throwable, thrown: MutableList<Throwable>): NodeStatusViewModel {
        val session = testSession()
        val radioID = testRadioId
        val backing = InMemoryContactStore()
        backing.saveContact(repeaterContact(session.publicKey, radioID))
        val contactService = ContactService(
            session = FakeContactSession(),
            store = SaveThrowingStore(backing, failure),
            now = { 0L },
        )
        return NodeStatusViewModel(
            scope = CoroutineScope(
                SupervisorJob() + UnconfinedTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error ->
                    thrown += error
                },
            ),
            session = session,
            dependencies = NodeStatusViewModel.Dependencies(
                repeaterAdminService = { null },
                contactService = { contactService },
                radioId = { radioID },
            ),
        ).also { it.setBatteryCurveExpanded(true) }
    }
}

private class SaveThrowingStore(private val backing: InMemoryContactStore, private val failure: Throwable) :
    ContactStore by backing {
    override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? = throw failure
}

private fun repeaterContact(publicKey: ByteArray, radioID: Uuid): ContactDto = ContactDto(
    id = testContactId,
    radioID = radioID,
    publicKey = publicKey,
    name = "Repeater",
    typeRawValue = ContactType.REPEATER.raw,
    flags = 0,
    outPathLength = 0,
    outPath = ByteArray(0),
    lastAdvertTimestamp = 0,
    latitude = 0.0,
    longitude = 0.0,
    lastModified = 0,
    isBlocked = false,
    isMuted = false,
    isFavorite = false,
    unreadCount = 0,
    ocvPreset = OCVPreset.LI_ION.rawValue,
)

private val testRadioId = Uuid.normalized("R0000000-0000-0000-0000-0000000000A1")
private val testContactId = Uuid.normalized("C0000000-0000-0000-0000-0000000000A1")

private class OcvSaveFailure : Exception("radio")

private class OcvSaveProbeError : Error("probe")
