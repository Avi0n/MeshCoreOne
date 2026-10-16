package one.meshcore.shared.chat.pathdetail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.BlockedChannelSenderDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.contact.BlockedChannelSenderService
import one.meshcore.shared.contact.BlockedChannelSenderStore
import one.meshcore.shared.contact.InMemoryBlockedChannelSenderStore
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.RecordingBlockedNamesRefresher
import one.meshcore.shared.contact.RecordingChannelMessageDeleter
import one.meshcore.shared.contact.RecordingContactSyncCoordinator
import one.meshcore.shared.contact.contactDto
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Block-sender name persists. A store failure still blocks the selected contacts and dismisses.
 * Cancellation and a thrown error dismiss without blocking those contacts and keep the thrown instance.
 */
class BlockSenderPersistFaultTest {
    @Test
    fun aCancelledPersistRethrowsTheSameExceptionAndSkipsContactBlocks() = runTest {
        val cancelled = CancellationException("stopped")
        val harness = persistHarness { throw cancelled }

        val thrown = assertFailsWith<CancellationException> { harness.block() }

        assertSame(cancelled, thrown)
        assertDismissedWithoutBlocking(harness)
    }

    @Test
    fun aPersistErrorRethrowsTheSameErrorAndSkipsContactBlocks() = runTest {
        val probe = BlockSenderPersistProbeError()
        val harness = persistHarness { throw probe }

        val thrown = assertFailsWith<BlockSenderPersistProbeError> { harness.block() }

        assertSame(probe, thrown)
        assertDismissedWithoutBlocking(harness)
    }

    @Test
    fun aStoreExceptionStillBlocksTheSelectedContactAndDismisses() = runTest {
        val harness = persistHarness { throw BlockSenderPersistStoreException("db locked") }

        harness.block()

        assertTrue(harness.vm.state.value.blockCompleted)
        assertFalse(harness.vm.state.value.isBlocking)
        assertTrue(harness.contacts.fetchContact(harness.contactId)?.isBlocked == true)
    }
}

private const val PERSIST_CLOCK_SECONDS = 1_700_000_000L

private class BlockSenderPersistProbeError : Error("probe")

private class BlockSenderPersistStoreException(message: String) : Exception(message)

private class PersistHarness(val vm: BlockSenderViewModel, val contacts: InMemoryContactStore, val contactId: Uuid) {
    suspend fun block() = vm.blockAnyway(senderName = "Spammer", radioID = ChatFixtures.RADIO)
}

private suspend fun assertDismissedWithoutBlocking(harness: PersistHarness) {
    assertTrue(harness.vm.state.value.blockCompleted)
    assertFalse(harness.vm.state.value.isBlocking)
    assertFalse(harness.contacts.fetchContact(harness.contactId)?.isBlocked == true)
}

private suspend fun persistHarness(onSave: () -> Nothing): PersistHarness {
    val senderStore = object : BlockedChannelSenderStore by InMemoryBlockedChannelSenderStore() {
        override suspend fun save(sender: BlockedChannelSenderDto) = onSave()
    }
    val senderService = BlockedChannelSenderService(
        store = senderStore,
        channelMessageDeleter = RecordingChannelMessageDeleter(),
        blockedNamesRefresher = RecordingBlockedNamesRefresher(),
        syncCoordinator = RecordingContactSyncCoordinator(),
        now = { PERSIST_CLOCK_SECONDS },
        newId = { ChatFixtures.freshUuid() },
    )
    val contactId = ChatFixtures.freshUuid()
    val contacts = InMemoryContactStore()
    contacts.saveContact(contactDto(contactId, ChatFixtures.RADIO, name = "Alice"))
    val vm = BlockSenderViewModel(
        BlockSenderViewModel.Dependencies(
            contactService = { testContactService(store = contacts) },
            blockedChannelSenderService = { senderService },
        ),
    )
    vm.toggleContact(contactId)
    return PersistHarness(vm, contacts, contactId)
}
