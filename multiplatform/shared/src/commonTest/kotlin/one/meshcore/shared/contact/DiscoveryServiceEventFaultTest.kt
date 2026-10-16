package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Discover reloads on device contact events. A Room fault leaves the collector running; cancellation
 * and a thrown error stop later reloads and keep the thrown instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryServiceEventFaultTest {
    @Test
    fun aRoomFaultStillReloadsTheNextContactEvent() = runTest(UnconfinedTestDispatcher()) {
        val backing = InMemoryDiscoveredNodeStore()
        var reads = 0
        val service = testContactService(session = twoKnownContacts())
        val vm = serviceEventViewModel(
            scope = backgroundScope,
            service = service,
            store = {
                reads += 1
                if (reads == 2) error("db locked")
                backing
            },
        )
        backing.upsert(serviceEventNode(0x01, "Alpha"))
        vm.load()
        vm.bindServiceEvents()

        assertTrue(service.handleAdvertisement(serviceEventRadio, publicKey(0x2F)))
        assertEquals(DISCOVERY_LOAD_FAILED_MESSAGE, vm.state.value.errorMessage)
        backing.upsert(serviceEventNode(0x02, "Bravo", heard = 2))
        assertTrue(service.handleAdvertisement(serviceEventRadio, publicKey(0x30)))

        assertEquals(3, reads)
        assertEquals(listOf("Bravo", "Alpha"), vm.state.value.nodes.map { it.name })
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun aCancelledContactEventRethrowsTheSameExceptionAndStopsLaterReloads() = runTest(UnconfinedTestDispatcher()) {
        val cancelled = CancellationException("superseded")
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + UnconfinedTestDispatcher(testScheduler))
        var reads = 0
        val service = testContactService(session = twoKnownContacts())
        val vm = serviceEventViewModel(
            scope = scope,
            service = service,
            store = {
                reads += 1
                if (reads == 2) throw cancelled
                InMemoryDiscoveredNodeStore()
            },
        )
        vm.load()
        vm.bindServiceEvents()
        val job = parent.children.single()
        var cause: Throwable? = null
        job.invokeOnCompletion { cause = it }

        assertTrue(service.handleAdvertisement(serviceEventRadio, publicKey(0x2F)))
        assertTrue(service.handleAdvertisement(serviceEventRadio, publicKey(0x30)))

        assertSame(cancelled, cause)
        assertEquals(2, reads)
        assertNull(vm.state.value.errorMessage)
        scope.cancel()
    }

    @Test
    fun aContactEventErrorRethrowsTheSameErrorAndStopsLaterReloads() = runTest(UnconfinedTestDispatcher()) {
        val failure = DiscoverServiceEventProbeError()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> errors += error },
        )
        var reads = 0
        val service = testContactService(session = twoKnownContacts())
        val vm = serviceEventViewModel(
            scope = scope,
            service = service,
            store = {
                reads += 1
                if (reads == 2) throw failure
                InMemoryDiscoveredNodeStore()
            },
        )
        vm.load()
        vm.bindServiceEvents()

        assertTrue(service.handleAdvertisement(serviceEventRadio, publicKey(0x2F)))
        assertTrue(service.handleAdvertisement(serviceEventRadio, publicKey(0x30)))

        assertSame(failure, errors.single())
        assertEquals(2, reads)
        assertNull(vm.state.value.errorMessage)
        scope.cancel()
    }
}

private class DiscoverServiceEventProbeError : Error("probe")

private val serviceEventRadio = ContactTestIds.RADIO

private fun twoKnownContacts() = FakeContactSession().apply {
    stubbedContacts = listOf(
        meshContact(keyByte = 0x2F, name = "One"),
        meshContact(keyByte = 0x30, name = "Two"),
    )
}

private fun serviceEventNode(keyByte: Int, name: String, heard: Long = 1) = DiscoveredNodeRecord(
    id = newDiscoveredNodeId(),
    radioID = serviceEventRadio,
    publicKey = publicKey(keyByte),
    name = name,
    typeRawValue = ContactType.CHAT.raw,
    lastHeardEpochSeconds = heard,
)

private fun serviceEventViewModel(scope: CoroutineScope, service: ContactService, store: () -> DiscoveredNodeStore?) =
    DiscoveryViewModel(
        scope = scope,
        dependencies = DiscoveryViewModel.Dependencies(
            store = store,
            contactService = { service },
            radioId = { serviceEventRadio },
            isRadioConnected = { true },
            loadFailedMessage = { DISCOVERY_LOAD_FAILED_MESSAGE },
            errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
        ),
    )
