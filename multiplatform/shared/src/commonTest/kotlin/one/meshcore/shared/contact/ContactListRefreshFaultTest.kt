package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pull-to-refresh faults that must keep the thrown instance. A radio exception still becomes a list
 * error in the existing load tests; cancellation and a store error clear the spinner and propagate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactListRefreshFaultTest {
    @Test
    fun aCancelledRefreshRethrowsTheSameExceptionAndClearsTheSpinner() = runTest {
        val cancelled = CancellationException("superseded")
        val session = FakeContactSession().apply { onGetContacts = { throw cancelled } }
        val vm = refreshViewModel(testContactService(session = session), backgroundScope)

        val thrown = assertFailsWith<CancellationException> { vm.refresh() }

        assertSame(cancelled, thrown)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isSyncing)
    }

    @Test
    fun aRefreshStoreErrorRethrowsTheSameErrorAndClearsTheSpinner() = runTest {
        val failure = RefreshProbeError()
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid): Nothing = throw failure
        }
        val vm = refreshViewModel(testContactService(store = store), backgroundScope)

        val thrown = assertFailsWith<RefreshProbeError> { vm.refresh() }

        assertSame(failure, thrown)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isSyncing)
    }
}

private class RefreshProbeError : Error("probe")

private fun TestScope.refreshViewModel(service: ContactService, scope: CoroutineScope): ContactListViewModel =
    ContactListViewModel(
        scope = scope,
        dependencies = ContactListViewModel.Dependencies(
            contactService = { service },
            radioId = { ContactTestIds.RADIO },
            isConnected = { true },
            isReady = { true },
            errorText = { "Localized error" },
        ),
    )
