package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * App-side list invalidations. A Room fault leaves the collector running; cancellation and a thrown
 * error stop later reloads and keep the thrown instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactListInvalidationFaultTest {
    @Test
    fun aCancelledInvalidationRethrowsTheSameExceptionAndStopsLaterReloads() = runTest(UnconfinedTestDispatcher()) {
        val cancelled = CancellationException("superseded")
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + UnconfinedTestDispatcher(testScheduler))
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
        var reads = 0
        val mem = InMemoryContactStore().also { it.saveContact(invalidationChat("Alice")) }
        val vm = invalidationViewModel(
            service = testContactService(store = mem),
            scope = scope,
            invalidations = invalidations,
            radioId = {
                reads += 1
                if (reads == 2) throw cancelled
                invalidationRadio
            },
        )
        vm.load()
        vm.bindListInvalidations()
        val job = parent.children.single()
        var cause: Throwable? = null
        job.invokeOnCompletion { cause = it }
        mem.saveContact(invalidationChat("Bob"))

        invalidations.emit(Unit)
        invalidations.emit(Unit)

        assertSame(cancelled, cause)
        assertEquals(2, reads)
        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
        assertNull(vm.state.value.error)
        scope.cancel()
    }

    @Test
    fun anInvalidationErrorRethrowsTheSameErrorAndStopsLaterReloads() = runTest(UnconfinedTestDispatcher()) {
        val failure = ListInvalidationProbeError()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> errors += error },
        )
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
        var reads = 0
        val mem = InMemoryContactStore().also { it.saveContact(invalidationChat("Alice")) }
        val vm = invalidationViewModel(
            service = testContactService(store = mem),
            scope = scope,
            invalidations = invalidations,
            radioId = {
                reads += 1
                if (reads == 2) throw failure
                invalidationRadio
            },
        )
        vm.load()
        vm.bindListInvalidations()
        mem.saveContact(invalidationChat("Bob"))

        invalidations.emit(Unit)
        invalidations.emit(Unit)

        assertSame(failure, errors.single())
        assertEquals(2, reads)
        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
        assertNull(vm.state.value.error)
        scope.cancel()
    }
}

private class ListInvalidationProbeError : Error("probe")

private val invalidationRadio = ContactTestIds.RADIO

private fun invalidationChat(name: String) = contactDto(
    id = ContactTestIds.fresh(),
    radioID = invalidationRadio,
    keyByte = if (name == "Bob") 0x02 else 0x01,
    name = name,
    typeRawValue = ContactType.CHAT.raw,
)

private fun invalidationViewModel(
    service: ContactService,
    scope: CoroutineScope,
    invalidations: MutableSharedFlow<Unit>,
    radioId: () -> Uuid?,
): ContactListViewModel = ContactListViewModel(
    scope = scope,
    dependencies = ContactListViewModel.Dependencies(
        contactService = { service },
        radioId = radioId,
        isConnected = { true },
        isReady = { true },
        listInvalidations = invalidations,
        errorText = { "Localized error" },
    ),
)
