package one.meshcore.shared.contact

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryAddNodeTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    /**
     * The device cap has to reach the message: "Node list is full" alone leaves the user with no idea how
     * many nodes they must remove, and the raw exception text is untranslated English.
     */
    @Test
    fun aFullDeviceTableSurfacesTheLocalizableErrorCarryingTheCap() = runTest {
        val session = FakeContactSession().apply { addContactError = ContactProtocolErrorCode.TABLE_FULL }
        val store = InMemoryDiscoveredNodeStore()
        val node = DiscoveredNodeRecord(
            id = newDiscoveredNodeId(),
            radioID = radio,
            publicKey = ByteArray(32) { 0x42 },
            name = "Rpt",
            typeRawValue = ContactType.REPEATER.raw,
            lastHeardEpochSeconds = 1,
        )
        store.upsert(node)
        val viewModel = DiscoveryViewModel(
            scope = backgroundScope,
            dependencies = DiscoveryViewModel.Dependencies(
                store = { store },
                contactService = { testContactService(session = session) },
                radioId = { radio },
                isRadioConnected = { true },
                maxContacts = { 200 },
                loadFailedMessage = { DISCOVERY_LOAD_FAILED_MESSAGE },
                errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
            ),
        )

        viewModel.addNode(node)

        val error = assertIs<AddContactError.NodeListFull>(viewModel.state.value.addError)
        assertEquals(200, error.maxContacts)

        viewModel.clearError()
        assertNull(viewModel.state.value.addError)
    }

    @Test
    fun addWhileDisconnectedDoesNotReachTheRadio() = runTest {
        val session = FakeContactSession()
        val node = DiscoveredNodeRecord(
            id = newDiscoveredNodeId(),
            radioID = radio,
            publicKey = ByteArray(32) { 0x42 },
            name = "Rpt",
            typeRawValue = ContactType.REPEATER.raw,
            lastHeardEpochSeconds = 1,
        )
        val viewModel = DiscoveryViewModel(
            scope = backgroundScope,
            dependencies = DiscoveryViewModel.Dependencies(
                store = { InMemoryDiscoveredNodeStore() },
                contactService = { testContactService(session = session) },
                radioId = { radio },
                isRadioConnected = { false },
                loadFailedMessage = { DISCOVERY_LOAD_FAILED_MESSAGE },
                errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
            ),
        )

        viewModel.addNode(node)

        assertTrue(session.addedContacts.isEmpty())
        assertNull(viewModel.state.value.addError)
        assertNull(viewModel.state.value.addingNodeId)
    }

    @Test
    fun bindServiceEventsRefreshesAddedBadgesOnAutoAdd() = runTest(UnconfinedTestDispatcher()) {
        val discovered = InMemoryDiscoveredNodeStore()
        val mem = InMemoryContactStore()
        val key = publicKey(0x2F)
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x2F, name = "AutoAdded")
        }
        val service = testContactService(session = session, store = mem)
        val viewModel = DiscoveryViewModel(
            scope = backgroundScope,
            dependencies = DiscoveryViewModel.Dependencies(
                store = { discovered },
                contactService = { service },
                radioId = { radio },
                isRadioConnected = { true },
                loadFailedMessage = { DISCOVERY_LOAD_FAILED_MESSAGE },
                errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
            ),
        )
        viewModel.load()
        assertTrue(viewModel.state.value.addedPublicKeyHexes.isEmpty())

        viewModel.bindServiceEvents()
        assertTrue(service.handleAdvertisement(radio, key))

        assertTrue(key.toUppercaseHex() in viewModel.state.value.addedPublicKeyHexes)
    }

    @Test
    fun toContactFrameUsesFloodAndName() {
        val node = DiscoveredNodeRecord(
            id = newDiscoveredNodeId(),
            radioID = radio,
            publicKey = ByteArray(32) { 0x42 },
            name = "Rpt",
            typeRawValue = ContactType.REPEATER.raw,
            lastHeardEpochSeconds = 99,
            lastAdvertTimestamp = 77,
        )
        val frame = node.toContactFrame(nowEpochSeconds = 1000)
        assertEquals("Rpt", frame.name)
        assertEquals(ContactType.REPEATER, frame.type)
        assertEquals(CONTACT_FLOOD_PATH_SENTINEL, frame.outPathLength)
        assertEquals(1000, frame.lastModified)
        assertTrue(frame.publicKey.contentEquals(node.publicKey))
    }

    /**
     * The contact row persists this timestamp and both stale-node age and share-contact freshness read it
     * back, so a receiver wall clock here makes an added node look freshly heard forever.
     */
    @Test
    fun toContactFrameCarriesTheFirmwareAdvertTimeNotTheReceiverClock() {
        val node = DiscoveredNodeRecord(
            id = newDiscoveredNodeId(),
            radioID = radio,
            publicKey = ByteArray(32) { 0x42 },
            name = "Rpt",
            typeRawValue = ContactType.REPEATER.raw,
            lastHeardEpochSeconds = 1_700_000_900,
            lastAdvertTimestamp = 1_700_000_123,
        )

        val frame = node.toContactFrame(nowEpochSeconds = 1_700_009_999)

        assertEquals(1_700_000_123, frame.lastAdvertTimestamp)
    }

    /** A row that has never carried an advert timestamp forwards zero, not "now". */
    @Test
    fun toContactFrameForwardsAZeroAdvertTimeUnchanged() {
        val node = DiscoveredNodeRecord(
            id = newDiscoveredNodeId(),
            radioID = radio,
            publicKey = ByteArray(32) { 0x43 },
            name = "NoAdvert",
            typeRawValue = ContactType.CHAT.raw,
            lastHeardEpochSeconds = 1_700_000_900,
        )

        assertEquals(0, node.toContactFrame(nowEpochSeconds = 1_700_009_999).lastAdvertTimestamp)
    }
}
