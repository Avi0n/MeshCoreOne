package one.meshcore.shared.map.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.contact.AddContactError
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.ContactTestIds
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.FakeContactSession
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.map.FakeLocationService
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Locating the user and adding a discovered node from the map. A missed phone fix still frames the
 * radio when one is known. A failed add still reports the mapped contact error and clears the
 * spinner. Cancellation and an error propagate and do not record a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapScreenActionsTest {
    @Test
    fun aKnownPhoneFixFramesWithoutAsking() = runTest {
        val harness = LocateHarness(phone = PHONE, fix = { FIX })
        harness.locate(mapScope())
        advanceUntilIdle()

        assertEquals(0, harness.service.requestCount)
        assertEquals(PHONE, harness.state.viewportRegion?.center)
        assertTrue(harness.state.isCenteredOnUser)
    }

    @Test
    fun aFreshFixFramesTheMap() = runTest {
        val harness = LocateHarness(fix = { FIX })
        harness.locate(mapScope())
        advanceUntilIdle()

        assertEquals(1, harness.service.requestCount)
        assertEquals(FIX, harness.state.viewportRegion?.center)
        assertTrue(harness.state.isCenteredOnUser)
    }

    @Test
    fun aMissedFixFramesTheRadioPosition() = runTest {
        val harness = LocateHarness(
            fix = {
                harnessNode = NODE
                throw IllegalArgumentException("miss")
            },
        )
        harness.locate(mapScope())
        advanceUntilIdle()

        assertEquals(NODE, harness.state.viewportRegion?.center)
        assertTrue(harness.state.isCenteredOnUser)
    }

    @Test
    fun aMissedFixWithNoRadioPositionDoesNotFrame() = runTest {
        val harness = LocateHarness(fix = { throw IllegalArgumentException("miss") })
        harness.locate(mapScope())
        advanceUntilIdle()

        assertNull(harness.state.viewportRegion)
        assertTrue(!harness.state.isCenteredOnUser)
    }

    @Test
    fun aCancelledLocatePropagatesWithoutFraming() = runTest {
        val stopped = CancellationException("superseded")
        val harness = LocateHarness(
            fix = {
                harnessNode = NODE
                throw stopped
            },
        )
        val scope = mapScope()
        harness.locate(scope)
        val child = scope.coroutineContext[Job]!!.children.single()
        child.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(stopped, harness.cause)
        assertNull(harness.state.viewportRegion)
        assertTrue(!harness.state.isCenteredOnUser)
    }

    @Test
    fun aLocateErrorPropagatesWithoutFraming() = runTest {
        var thrown: Throwable? = null
        val harness = LocateHarness(
            fix = {
                harnessNode = NODE
                throw MapScreenProbeError()
            },
        )
        harness.locate(mapScope { thrown = it })
        advanceUntilIdle()

        assertIs<MapScreenProbeError>(thrown)
        assertNull(harness.state.viewportRegion)
        assertTrue(!harness.state.isCenteredOnUser)
    }

    @Test
    fun aDisconnectedAddReportsNotConnected() = runTest {
        val harness = AddHarness(connected = false)
        harness.add(mapScope())
        advanceUntilIdle()

        assertEquals(AddContactError.NotConnected, harness.state.addError)
        assertNull(harness.state.addingDiscoveredId)
        assertTrue(harness.session.addedContacts.isEmpty())
    }

    @Test
    fun anInFlightAddReturnsBeforeTheWrite() = runTest {
        val harness = AddHarness()
        harness.state.addingDiscoveredId = harness.node.id
        harness.add(mapScope())
        advanceUntilIdle()

        assertEquals(harness.node.id, harness.state.addingDiscoveredId)
        assertNull(harness.state.addError)
        assertTrue(harness.session.addedContacts.isEmpty())
    }

    @Test
    fun anAddWritesTheNodeAndClearsTheSelection() = runTest {
        val harness = AddHarness()
        harness.state.selectedDiscovered = harness.node
        harness.add(mapScope())
        advanceUntilIdle()

        assertEquals(1, harness.session.addedContacts.size)
        assertNull(harness.state.selectedDiscovered)
        assertNull(harness.state.addingDiscoveredId)
        assertNull(harness.state.addError)
        assertEquals(1, harness.state.contactReloadKey)
    }

    @Test
    fun anAddExceptionReportsTheMappedText() = runTest {
        val harness = AddHarness()
        harness.session.addContactThrowable = IllegalArgumentException("bad add")
        harness.state.selectedDiscovered = harness.node
        harness.add(mapScope())
        advanceUntilIdle()

        assertEquals(AddContactError.ServiceMessage("bad add"), harness.state.addError)
        assertEquals(harness.node, harness.state.selectedDiscovered)
        assertNull(harness.state.addingDiscoveredId)
        assertEquals(0, harness.state.contactReloadKey)
    }

    @Test
    fun aCancelledAddPropagatesWithoutAFailure() = runTest {
        val stopped = CancellationException("superseded")
        val harness = AddHarness()
        harness.session.addContactThrowable = stopped
        val scope = mapScope()
        harness.add(scope)
        val child = scope.coroutineContext[Job]!!.children.single()
        child.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(stopped, harness.cause)
        assertNull(harness.state.addError)
        assertNull(harness.state.addingDiscoveredId)
        assertEquals(0, harness.state.contactReloadKey)
    }

    @Test
    fun anAddErrorPropagatesWithoutAFailure() = runTest {
        var thrown: Throwable? = null
        val harness = AddHarness(store = SaveThrows { throw MapScreenProbeError() })
        harness.state.selectedDiscovered = harness.node
        harness.add(mapScope { thrown = it })
        advanceUntilIdle()

        assertIs<MapScreenProbeError>(thrown)
        assertEquals(1, harness.session.addedContacts.size)
        assertNull(harness.state.addError)
        assertEquals(harness.node, harness.state.selectedDiscovered)
        assertNull(harness.state.addingDiscoveredId)
        assertEquals(0, harness.state.contactReloadKey)
    }
}

private class MapScreenProbeError : Error("probe")

private val PHONE = LatLng(latitude = 1.0, longitude = 2.0)

private val NODE = LatLng(latitude = 3.0, longitude = 4.0)

private val FIX = LatLng(latitude = 5.0, longitude = 6.0)

private var harnessNode: LatLng? = null

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.mapScope(onError: (Throwable) -> Unit = {}): CoroutineScope = CoroutineScope(
    SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error -> onError(error) },
)

private class LocateHarness(phone: LatLng? = null, private val fix: () -> LatLng) {
    val state = MapScreenState()
    val preferences = InMemoryPreferencesStore()
    val service = FakeLocationService(locationResult = fix)
    var cause: Throwable? = null

    private val inputs = MapLocateInputs(
        service = service,
        userLocation = { phone },
        nodeLocation = { harnessNode },
    )

    fun locate(scope: CoroutineScope) {
        harnessNode = null
        state.locateOnUser(scope, preferences, inputs).invoke()
    }
}

private class AddHarness(connected: Boolean = true, store: ContactStore = InMemoryContactStore()) {
    val state = MapScreenState()
    val session = FakeContactSession()
    val node = DiscoveredNodeRecord(
        id = ContactTestIds.fresh(),
        radioID = ContactTestIds.RADIO,
        publicKey = ByteArray(PUBLIC_KEY_SIZE) { KEY_BYTE.toByte() },
        name = "Relay",
        typeRawValue = ContactType.REPEATER.raw,
        lastHeardEpochSeconds = 0L,
    )
    var cause: Throwable? = null

    private val service: ContactService = testContactService(session = session, store = store)
    private val inputs = MapDiscoveredAddInputs(
        contactService = { service },
        isRadioConnected = { connected },
        nowEpochSeconds = { 1_000L },
        maxContacts = { null },
        contactErrorText = { error -> error.message ?: "Localized error" },
    )

    fun add(scope: CoroutineScope) {
        state.addDiscovered(scope, inputs).invoke(node)
    }
}

private class SaveThrows(private val fail: () -> Nothing) : ContactStore by InMemoryContactStore() {
    override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid = fail()
}

private const val PUBLIC_KEY_SIZE = 32

private const val KEY_BYTE = 0x11
