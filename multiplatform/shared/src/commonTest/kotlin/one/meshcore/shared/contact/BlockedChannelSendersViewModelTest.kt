package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.BlockedChannelSenderDto
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A persist fault on the blocked-senders screen must clear the spinner. iOS
 * `BlockedChannelSendersView` uses `defer { isLoading = false }` and lands an empty list.
 */
class BlockedChannelSendersViewModelTest {
    @Test
    fun loadShowsBlockedSendersNewestFirst() = runTest {
        val f = Fixture()
        f.service.blockSender(ContactTestIds.RADIO, "Older")
        f.clock = 200L
        f.service.blockSender(ContactTestIds.RADIO, "Newer")
        val viewModel = f.viewModel()

        viewModel.load()

        val state = viewModel.state.value
        assertTrue(state.hasLoadedOnce)
        assertFalse(state.isLoading)
        assertEquals(listOf("Newer", "Older"), state.senders.map { it.name })
    }

    @Test
    fun loadPersistFaultClearsSpinnerAndShowsEmpty() = runTest {
        val f = Fixture(listThrows = true)
        val viewModel = f.viewModel()

        viewModel.load()

        val state = viewModel.state.value
        assertTrue(state.hasLoadedOnce)
        assertFalse(state.isLoading)
        assertTrue(state.senders.isEmpty())
    }

    @Test
    fun aCancelledLoadPropagatesWithoutClearingTheSpinner() = runTest {
        val f = Fixture()
        val cancelled = CancellationException("superseded")
        f.store.listFailure = cancelled
        val viewModel = f.viewModel()

        val thrown = assertFailsWith<CancellationException> { viewModel.load() }
        assertSame(cancelled, thrown)

        val state = viewModel.state.value
        assertFalse(state.hasLoadedOnce)
        assertTrue(state.isLoading)
        assertTrue(state.senders.isEmpty())
    }

    @Test
    fun aLoadErrorPropagatesWithoutClearingTheSpinner() = runTest {
        val f = Fixture()
        f.store.listFailure = BlockedSenderProbeError()
        val viewModel = f.viewModel()

        assertFailsWith<BlockedSenderProbeError> { viewModel.load() }

        val state = viewModel.state.value
        assertFalse(state.hasLoadedOnce)
        assertTrue(state.isLoading)
        assertTrue(state.senders.isEmpty())
    }

    @Test
    fun loadWhenDisconnectedShowsEmptyNotSpinner() = runTest {
        val viewModel = BlockedChannelSendersViewModel(
            BlockedChannelSendersViewModel.Dependencies(
                service = { null },
                radioId = { null },
            ),
        )

        viewModel.load()

        val state = viewModel.state.value
        assertTrue(state.hasLoadedOnce)
        assertFalse(state.isLoading)
        assertTrue(state.senders.isEmpty())
    }

    @Test
    fun unblockingAChannelSenderWhileDisconnectedDoesNotRadio() = runTest {
        val f = Fixture()
        f.service.blockSender(ContactTestIds.RADIO, "Spammer")
        var serviceReads = 0
        var radioReads = 0
        val viewModel = BlockedChannelSendersViewModel(
            BlockedChannelSendersViewModel.Dependencies(
                service = {
                    serviceReads += 1
                    f.service
                },
                radioId = {
                    radioReads += 1
                    null
                },
            ),
        )

        viewModel.unblock("Spammer")

        assertEquals(1, serviceReads)
        assertEquals(1, radioReads)
        assertEquals(listOf("Spammer"), f.service.blockedSenders(ContactTestIds.RADIO).map { it.name })
        val state = viewModel.state.value
        assertFalse(state.hasLoadedOnce)
        assertFalse(state.isLoading)
        assertTrue(state.senders.isEmpty())
    }

    @Test
    fun unblockRemovesTheRow() = runTest {
        val f = Fixture()
        f.service.blockSender(ContactTestIds.RADIO, "Spammer")
        val viewModel = f.viewModel()
        viewModel.load()

        viewModel.unblock("Spammer")

        assertTrue(viewModel.state.value.senders.isEmpty())
        assertTrue(f.service.blockedSenders(ContactTestIds.RADIO).isEmpty())
    }

    @Test
    fun unblockPersistFaultKeepsTheScreenUsable() = runTest {
        val f = Fixture()
        f.service.blockSender(ContactTestIds.RADIO, "Spammer")
        f.store.deleteThrows = true
        val viewModel = f.viewModel()
        viewModel.load()

        viewModel.unblock("Spammer")

        val state = viewModel.state.value
        assertTrue(state.hasLoadedOnce)
        assertFalse(state.isLoading)
        assertEquals(listOf("Spammer"), state.senders.map { it.name })
    }

    @Test
    fun aCancelledUnblockPropagatesWithoutReloading() = runTest {
        val f = Fixture()
        f.service.blockSender(ContactTestIds.RADIO, "Spammer")
        val viewModel = f.viewModel()
        viewModel.load()
        val cancelled = CancellationException("superseded")
        f.store.deleteFailure = cancelled
        f.store.listFailure = BlockedSenderProbeError()

        val thrown = assertFailsWith<CancellationException> { viewModel.unblock("Spammer") }
        assertSame(cancelled, thrown)

        assertEquals(listOf("Spammer"), viewModel.state.value.senders.map { it.name })
        f.store.listFailure = null
        assertEquals(listOf("Spammer"), f.service.blockedSenders(ContactTestIds.RADIO).map { it.name })
    }

    @Test
    fun anUnblockErrorPropagatesWithoutReloading() = runTest {
        val f = Fixture()
        f.service.blockSender(ContactTestIds.RADIO, "Spammer")
        val viewModel = f.viewModel()
        viewModel.load()
        f.store.deleteFailure = BlockedSenderProbeError()
        f.store.listFailure = IllegalStateException("reload")

        assertFailsWith<BlockedSenderProbeError> { viewModel.unblock("Spammer") }

        assertEquals(listOf("Spammer"), viewModel.state.value.senders.map { it.name })
    }

    private class Fixture(listThrows: Boolean = false) {
        val store = ControllableBlockedChannelSenderStore(listThrows = listThrows)
        val channelMessages = RecordingChannelMessageDeleter()
        val cache = FakeBlockedNamesCache()
        val refresher = ContactBlockedNamesRefresher(InMemoryContactStore(), store, cache)
        val syncCoordinator = RecordingContactSyncCoordinator()
        var clock = 100L

        val service = BlockedChannelSenderService(
            store = store,
            channelMessageDeleter = channelMessages,
            blockedNamesRefresher = refresher,
            syncCoordinator = syncCoordinator,
            now = { clock },
            newId = { ContactTestIds.fresh() },
        )

        fun viewModel() = BlockedChannelSendersViewModel(
            BlockedChannelSendersViewModel.Dependencies(
                service = { service },
                radioId = { ContactTestIds.RADIO },
            ),
        )
    }
}

private class ControllableBlockedChannelSenderStore(
    var listThrows: Boolean = false,
    var deleteThrows: Boolean = false,
    var listFailure: Throwable? = null,
    var deleteFailure: Throwable? = null,
) : BlockedChannelSenderStore {
    private val inner = InMemoryBlockedChannelSenderStore()

    override suspend fun save(sender: BlockedChannelSenderDto) = inner.save(sender)

    override suspend fun delete(radioID: Uuid, name: String) {
        deleteFailure?.let { throw it }
        if (deleteThrows) error("db locked")
        inner.delete(radioID, name)
    }

    override suspend fun list(radioID: Uuid): List<BlockedChannelSenderDto> {
        listFailure?.let { throw it }
        if (listThrows) error("db locked")
        return inner.list(radioID)
    }
}

private class BlockedSenderProbeError : Error("probe")
