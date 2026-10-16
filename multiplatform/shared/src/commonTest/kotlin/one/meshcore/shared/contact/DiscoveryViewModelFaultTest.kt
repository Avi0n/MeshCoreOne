package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryViewModelFaultTest {
    private val radio = ContactTestIds.RADIO

    @Test
    fun aCancelledLoadLeavesTheListLoading() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val vm = viewModel(
            scope = this,
            inputs = FaultInputs(
                store = throwingListStore { throw CancellationException("superseded") },
                trace = PersistentLogger(this, category = "discover-trace", record = { recorded += it }),
            ),
        )

        assertFailsWith<CancellationException> { vm.load() }

        assertTrue(vm.state.value.isLoading)
        assertEquals(false, vm.state.value.hasLoadedOnce)
        assertNull(vm.state.value.errorMessage)
        assertTrue(recorded.none { it.message.contains("B4 view reload FAILED") })
    }

    @Test
    fun aLoadErrorLeavesTheListLoading() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val vm = viewModel(
            scope = this,
            inputs = FaultInputs(
                store = throwingListStore { throw DiscoveryFaultProbeError() },
                trace = PersistentLogger(this, category = "discover-trace", record = { recorded += it }),
            ),
        )

        assertFailsWith<DiscoveryFaultProbeError> { vm.load() }

        assertTrue(vm.state.value.isLoading)
        assertEquals(false, vm.state.value.hasLoadedOnce)
        assertNull(vm.state.value.errorMessage)
        assertTrue(recorded.none { it.message.contains("B4 view reload FAILED") })
    }

    @Test
    fun aCancelledClearLeavesTheRows() = runTest {
        val backing = seededStore()
        val store = object : DiscoveredNodeStore by backing {
            override suspend fun clear(radioID: Uuid) = throw CancellationException("superseded")
        }
        val vm = viewModel(scope = this, inputs = FaultInputs(store))
        vm.load()

        assertFailsWith<CancellationException> { vm.clearAll() }

        assertEquals(listOf("Alpha"), vm.state.value.nodes.map { it.name })
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun aClearErrorLeavesTheRows() = runTest {
        val backing = seededStore()
        val store = object : DiscoveredNodeStore by backing {
            override suspend fun clear(radioID: Uuid) = throw DiscoveryFaultProbeError()
        }
        val vm = viewModel(scope = this, inputs = FaultInputs(store))
        vm.load()

        assertFailsWith<DiscoveryFaultProbeError> { vm.clearAll() }

        assertEquals(listOf("Alpha"), vm.state.value.nodes.map { it.name })
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun aCancelledDeleteLeavesTheRow() = runTest {
        val backing = seededStore()
        val store = object : DiscoveredNodeStore by backing {
            override suspend fun remove(radioID: Uuid, publicKey: ByteArray) = throw CancellationException("superseded")
        }
        val vm = viewModel(scope = this, inputs = FaultInputs(store))
        vm.load()

        assertFailsWith<CancellationException> { vm.deleteNode(vm.state.value.nodes.single()) }

        assertEquals(listOf("Alpha"), vm.state.value.nodes.map { it.name })
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun aDeleteErrorLeavesTheRow() = runTest {
        val backing = seededStore()
        val store = object : DiscoveredNodeStore by backing {
            override suspend fun remove(radioID: Uuid, publicKey: ByteArray) = throw DiscoveryFaultProbeError()
        }
        val vm = viewModel(scope = this, inputs = FaultInputs(store))
        vm.load()

        assertFailsWith<DiscoveryFaultProbeError> { vm.deleteNode(vm.state.value.nodes.single()) }

        assertEquals(listOf("Alpha"), vm.state.value.nodes.map { it.name })
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun aCancelledAddClearsTheBusyFlag() = runTest {
        val session = FakeContactSession().apply { addContactThrowable = CancellationException("superseded") }
        val vm = viewModel(
            scope = this,
            inputs = FaultInputs(
                store = InMemoryDiscoveredNodeStore(),
                service = testContactService(session = session),
                connected = true,
            ),
        )

        assertFailsWith<CancellationException> { vm.addNode(node(0x11, "Alpha")) }

        assertNull(vm.state.value.addingNodeId)
        assertNull(vm.state.value.addError)
        assertTrue(session.addedContacts.isEmpty())
    }

    @Test
    fun anAddErrorClearsTheBusyFlag() = runTest {
        val session = FakeContactSession()
        var lists = 0
        val store = object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
            override suspend fun list(radioID: Uuid): List<DiscoveredNodeRecord> {
                lists += 1
                if (lists > 1) throw DiscoveryFaultProbeError()
                return emptyList()
            }
        }
        val vm = viewModel(
            scope = this,
            inputs = FaultInputs(
                store = store,
                service = testContactService(session = session),
                connected = true,
            ),
        )
        vm.load()

        assertFailsWith<DiscoveryFaultProbeError> { vm.addNode(node(0x11, "Alpha")) }

        assertNull(vm.state.value.addingNodeId)
        assertNull(vm.state.value.addError)
        assertEquals(1, session.addedContacts.size)
    }

    @Test
    fun anInvalidationExceptionStillReloadsTheNextChange() = runTest(UnconfinedTestDispatcher()) {
        var lists = 0
        val backing = seededStore()
        val store = object : DiscoveredNodeStore by backing {
            override suspend fun list(radioID: Uuid): List<DiscoveredNodeRecord> {
                lists += 1
                if (lists == 2) error("db locked")
                return backing.list(radioID)
            }
        }
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
        val vm = viewModel(scope = backgroundScope, inputs = FaultInputs(store = store, invalidations = invalidations))
        vm.load()
        vm.bindListInvalidations()

        backing.upsert(node(0x02, "Bravo", heard = 2))
        invalidations.emit(Unit)
        invalidations.emit(Unit)

        assertEquals(listOf("Bravo", "Alpha"), vm.state.value.nodes.map { it.name })
        assertEquals(3, lists)
    }

    @Test
    fun aCancelledInvalidationStopsLaterReloads() = runTest(UnconfinedTestDispatcher()) {
        var lists = 0
        val store = object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
            override suspend fun list(radioID: Uuid): List<DiscoveredNodeRecord> {
                lists += 1
                if (lists > 1) throw CancellationException("superseded")
                return emptyList()
            }
        }
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
        val vm = viewModel(scope = this, inputs = FaultInputs(store = store, invalidations = invalidations))
        vm.load()
        vm.bindListInvalidations()

        invalidations.emit(Unit)
        invalidations.emit(Unit)

        assertEquals(2, lists)
    }

    @Test
    fun anInvalidationErrorStopsLaterReloads() = runTest(UnconfinedTestDispatcher()) {
        val errors = mutableListOf<Throwable>()
        val scope = supervisor(UnconfinedTestDispatcher(testScheduler), errors)
        var lists = 0
        val store = object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
            override suspend fun list(radioID: Uuid): List<DiscoveredNodeRecord> {
                lists += 1
                if (lists > 1) throw DiscoveryFaultProbeError()
                return emptyList()
            }
        }
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
        val vm = viewModel(scope = scope, inputs = FaultInputs(store = store, invalidations = invalidations))
        vm.load()
        vm.bindListInvalidations()

        invalidations.emit(Unit)
        invalidations.emit(Unit)

        assertTrue(errors.single() is DiscoveryFaultProbeError)
        assertEquals(2, lists)
        scope.cancel()
    }

    @Test
    fun aServiceEventErrorStopsLaterReloads() = runTest(UnconfinedTestDispatcher()) {
        val errors = mutableListOf<Throwable>()
        val scope = supervisor(UnconfinedTestDispatcher(testScheduler), errors)
        var lists = 0
        val store = object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
            override suspend fun list(radioID: Uuid): List<DiscoveredNodeRecord> {
                lists += 1
                if (lists > 1) throw DiscoveryFaultProbeError()
                return emptyList()
            }
        }
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(
                meshContact(keyByte = 0x2F, name = "One"),
                meshContact(keyByte = 0x30, name = "Two"),
            )
        }
        val service = testContactService(session = session)
        val vm = viewModel(
            scope = scope,
            inputs = FaultInputs(store = store, service = service, connected = true),
        )
        vm.load()
        vm.bindServiceEvents()

        assertTrue(service.handleAdvertisement(radio, publicKey(0x2F)))
        assertTrue(service.handleAdvertisement(radio, publicKey(0x30)))

        assertTrue(errors.single() is DiscoveryFaultProbeError)
        assertEquals(2, lists)
        scope.cancel()
    }

    private fun supervisor(dispatcher: CoroutineDispatcher, errors: MutableList<Throwable>) = CoroutineScope(
        SupervisorJob() +
            dispatcher +
            CoroutineExceptionHandler { _, error -> errors += error },
    )

    private data class FaultInputs(
        val store: DiscoveredNodeStore,
        val service: ContactService? = null,
        val connected: Boolean = false,
        val invalidations: MutableSharedFlow<Unit>? = null,
        val trace: PersistentLogger? = null,
    )

    private fun viewModel(scope: CoroutineScope, inputs: FaultInputs) = DiscoveryViewModel(
        scope = scope,
        dependencies = DiscoveryViewModel.Dependencies(
            store = { inputs.store },
            contactService = { inputs.service },
            radioId = { radio },
            isRadioConnected = { inputs.connected },
            listInvalidations = inputs.invalidations ?: MutableSharedFlow(),
            discoverTrace = inputs.trace,
            loadFailedMessage = { DISCOVERY_LOAD_FAILED_MESSAGE },
            errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
        ),
    )

    private suspend fun seededStore(): DiscoveredNodeStore {
        val store = InMemoryDiscoveredNodeStore()
        store.upsert(node(0x01, "Alpha"))
        return store
    }

    private fun throwingListStore(onList: () -> Nothing) =
        object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
            override suspend fun list(radioID: Uuid): List<DiscoveredNodeRecord> = onList()
        }

    private fun node(keyByte: Int, name: String, heard: Long = 1) = DiscoveredNodeRecord(
        id = newDiscoveredNodeId(),
        radioID = radio,
        publicKey = publicKey(keyByte),
        name = name,
        typeRawValue = ContactType.CHAT.raw,
        lastHeardEpochSeconds = heard,
    )
}

private class DiscoveryFaultProbeError : Error("probe")
