package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Nodes-list reload faults that must keep the thrown instance. A contact-read exception still becomes
 * a list error in the existing load tests; cancellation and a store error leave the spinner up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactListReloadFaultTest {
    @Test
    fun aCancelledContactReadRethrowsTheSameExceptionAndLeavesTheSpinner() = runTest {
        val cancelled = CancellationException("superseded")
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid): Nothing = throw cancelled
        }
        val vm = reloadViewModel(testContactService(store = store), backgroundScope)

        val thrown = assertFailsWith<CancellationException> { vm.load() }

        assertSame(cancelled, thrown)
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasLoadedOnce)
    }

    @Test
    fun aContactReadErrorRethrowsTheSameErrorAndLeavesTheSpinner() = runTest {
        val failure = ReloadProbeError()
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid): Nothing = throw failure
        }
        val vm = reloadViewModel(testContactService(store = store), backgroundScope)

        val thrown = assertFailsWith<ReloadProbeError> { vm.load() }

        assertSame(failure, thrown)
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertTrue(vm.state.value.contacts.isEmpty())
    }

    @Test
    fun aCancelledHopReadRethrowsTheSameExceptionAndLeavesTheSpinner() = runTest {
        val cancelled = CancellationException("superseded")
        val vm = reloadViewModel(
            testContactService(store = savedContactStore(reloadChat("Alice"))),
            backgroundScope,
            hops = { throw cancelled },
        )

        val thrown = assertFailsWith<CancellationException> { vm.load() }

        assertSame(cancelled, thrown)
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.contacts.isEmpty())
        assertFalse(vm.state.value.hasLoadedOnce)
    }

    @Test
    fun aHopReadErrorRethrowsTheSameErrorAndLeavesTheSpinner() = runTest {
        val failure = ReloadProbeError()
        val vm = reloadViewModel(
            testContactService(store = savedContactStore(reloadChat("Alice"))),
            backgroundScope,
            hops = { throw failure },
        )

        val thrown = assertFailsWith<ReloadProbeError> { vm.load() }

        assertSame(failure, thrown)
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.contacts.isEmpty())
        assertFalse(vm.state.value.hasLoadedOnce)
    }
}

private class ReloadProbeError : Error("probe")

private val reloadRadio = ContactTestIds.RADIO

private fun reloadChat(name: String) = contactDto(
    id = ContactTestIds.fresh(),
    radioID = reloadRadio,
    keyByte = 0x01,
    name = name,
    typeRawValue = ContactType.CHAT.raw,
)

private fun TestScope.reloadViewModel(
    service: ContactService,
    scope: CoroutineScope,
    hops: suspend (Uuid) -> Map<String, Int> = { emptyMap() },
): ContactListViewModel = ContactListViewModel(
    scope = scope,
    dependencies = ContactListViewModel.Dependencies(
        contactService = { service },
        radioId = { reloadRadio },
        isConnected = { true },
        isReady = { true },
        inboundHops = hops,
        errorText = { "Localized error" },
    ),
)
