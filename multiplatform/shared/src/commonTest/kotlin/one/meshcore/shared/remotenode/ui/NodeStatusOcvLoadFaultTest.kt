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
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.FakeContactSession
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.remotenode.testUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed battery-curve read is shown on the section. Cancellation leaves the curve unset. A thrown
 * error propagates and does not mark the curve failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeStatusOcvLoadFaultTest {
    @Test
    fun aCancelledOcvLoadLeavesTheCurveUnset() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val thrown = mutableListOf<Throwable>()
        val vm = viewModel(stopped, thrown)

        assertFalse(vm.state.value.ocvLoaded)
        assertNull(vm.state.value.ocvError)
        assertTrue(thrown.isEmpty())
        assertTrue(vm.ocvJob!!.isCancelled)
    }

    @Test
    fun anOcvLoadFailureMarksTheCurveUnavailable() = runTest(UnconfinedTestDispatcher()) {
        val thrown = mutableListOf<Throwable>()
        val vm = viewModel(OcvLoadFailure(), thrown)

        assertTrue(vm.state.value.ocvLoaded)
        assertEquals(
            NodeSectionError.Message(NodeStatusViewModel.OCV_LOAD_FAILED_KEY),
            vm.state.value.ocvError,
        )
        assertTrue(thrown.isEmpty())
    }

    @Test
    fun anOcvLoadErrorPropagatesAndLeavesTheCurveUnset() = runTest(UnconfinedTestDispatcher()) {
        val probe = OcvLoadProbeError()
        val thrown = mutableListOf<Throwable>()
        val vm = viewModel(probe, thrown)

        assertFalse(vm.state.value.ocvLoaded)
        assertNull(vm.state.value.ocvError)
        assertSame(probe, thrown.single())
    }

    private fun TestScope.viewModel(failure: Throwable, thrown: MutableList<Throwable>): NodeStatusViewModel {
        val contactService = ContactService(
            session = FakeContactSession(),
            store = ThrowingContactStore(failure),
            now = { 0L },
        )
        return NodeStatusViewModel(
            scope = CoroutineScope(
                SupervisorJob() + UnconfinedTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error ->
                    thrown += error
                },
            ),
            session = testSession(),
            dependencies = NodeStatusViewModel.Dependencies(
                repeaterAdminService = { null },
                contactService = { contactService },
                radioId = { testUuid(99) },
            ),
        ).also { it.setBatteryCurveExpanded(true) }
    }
}

private class ThrowingContactStore(private val failure: Throwable) : ContactStore by InMemoryContactStore() {
    override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? = throw failure
}

private class OcvLoadFailure : Exception("radio")

private class OcvLoadProbeError : Error("probe")
