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
 * Discover list invalidations. A Room fault leaves the collector running; cancellation and a thrown
 * error stop later reloads and keep the thrown instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryListInvalidationFaultTest {
    @Test
    fun aRoomFaultStillReloadsTheNextChange() = runTest(UnconfinedTestDispatcher()) {
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
        val store = InMemoryDiscoveredNodeStore()
        var reads = 0
        val vm = reloadViewModel(
            scope = backgroundScope,
            store = store,
            invalidations = invalidations,
            radioId = {
                reads += 1
                if (reads == 2) error("db locked")
                reloadRadio
            },
        )
        store.upsert(reloadNode(0x01, "Alpha"))
        vm.load()
        vm.bindListInvalidations()

        invalidations.emit(Unit)
        assertEquals(DISCOVERY_LOAD_FAILED_MESSAGE, vm.state.value.errorMessage)
        store.upsert(reloadNode(0x02, "Bravo", heard = 2))
        invalidations.emit(Unit)

        assertEquals(3, reads)
        assertEquals(listOf("Bravo", "Alpha"), vm.state.value.nodes.map { it.name })
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun aCancelledInvalidationRethrowsTheSameExceptionAndStopsLaterReloads() = runTest(UnconfinedTestDispatcher()) {
        val cancelled = CancellationException("superseded")
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + UnconfinedTestDispatcher(testScheduler))
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
        var reads = 0
        val vm = reloadViewModel(
            scope = scope,
            store = InMemoryDiscoveredNodeStore(),
            invalidations = invalidations,
            radioId = {
                reads += 1
                if (reads == 2) throw cancelled
                reloadRadio
            },
        )
        vm.load()
        vm.bindListInvalidations()
        val job = parent.children.single()
        var cause: Throwable? = null
        job.invokeOnCompletion { cause = it }

        invalidations.emit(Unit)
        invalidations.emit(Unit)

        assertSame(cancelled, cause)
        assertEquals(2, reads)
        assertNull(vm.state.value.errorMessage)
        scope.cancel()
    }

    @Test
    fun anInvalidationErrorRethrowsTheSameErrorAndStopsLaterReloads() = runTest(UnconfinedTestDispatcher()) {
        val failure = DiscoverReloadProbeError()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> errors += error },
        )
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
        var reads = 0
        val vm = reloadViewModel(
            scope = scope,
            store = InMemoryDiscoveredNodeStore(),
            invalidations = invalidations,
            radioId = {
                reads += 1
                if (reads == 2) throw failure
                reloadRadio
            },
        )
        vm.load()
        vm.bindListInvalidations()

        invalidations.emit(Unit)
        invalidations.emit(Unit)

        assertSame(failure, errors.single())
        assertEquals(2, reads)
        assertNull(vm.state.value.errorMessage)
        scope.cancel()
    }
}

private class DiscoverReloadProbeError : Error("probe")

private val reloadRadio = ContactTestIds.RADIO

private fun reloadNode(keyByte: Int, name: String, heard: Long = 1) = DiscoveredNodeRecord(
    id = newDiscoveredNodeId(),
    radioID = reloadRadio,
    publicKey = publicKey(keyByte),
    name = name,
    typeRawValue = ContactType.CHAT.raw,
    lastHeardEpochSeconds = heard,
)

private fun reloadViewModel(
    scope: CoroutineScope,
    store: DiscoveredNodeStore,
    invalidations: MutableSharedFlow<Unit>,
    radioId: () -> Uuid?,
) = DiscoveryViewModel(
    scope = scope,
    dependencies = DiscoveryViewModel.Dependencies(
        store = { store },
        contactService = { null },
        radioId = radioId,
        listInvalidations = invalidations,
        loadFailedMessage = { DISCOVERY_LOAD_FAILED_MESSAGE },
        errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
    ),
)
