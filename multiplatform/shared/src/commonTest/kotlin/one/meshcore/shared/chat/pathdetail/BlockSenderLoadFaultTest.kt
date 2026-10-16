package one.meshcore.shared.chat.pathdetail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Block-sender contact loads. A store failure clears the spinner and leaves no matches.
 * Cancellation and a thrown error leave the spinner and keep the thrown instance.
 */
class BlockSenderLoadFaultTest {
    @Test
    fun aCancelledLoadRethrowsTheSameExceptionAndLeavesTheSpinner() = runTest {
        val cancelled = CancellationException("stopped")
        val vm = loadViewModel { throw cancelled }

        val thrown =
            assertFailsWith<CancellationException> { vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO) }

        assertSame(cancelled, thrown)
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.matchingContacts.isEmpty())
    }

    @Test
    fun aLoadErrorRethrowsTheSameErrorAndLeavesTheSpinner() = runTest {
        val probe = BlockSenderLoadProbeError()
        val vm = loadViewModel { throw probe }

        val thrown = assertFailsWith<BlockSenderLoadProbeError> {
            vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)
        }

        assertSame(probe, thrown)
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.matchingContacts.isEmpty())
    }

    @Test
    fun aStoreExceptionClearsTheSpinnerAndLeavesNoMatches() = runTest {
        val vm = loadViewModel { throw BlockSenderLoadStoreException("db locked") }

        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)

        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.matchingContacts.isEmpty())
    }
}

private class BlockSenderLoadProbeError : Error("probe")

private class BlockSenderLoadStoreException(message: String) : Exception(message)

private fun loadViewModel(onFetch: () -> Nothing): BlockSenderViewModel {
    val store = object : ContactStore by InMemoryContactStore() {
        override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> = onFetch()
    }
    return BlockSenderViewModel(
        BlockSenderViewModel.Dependencies(
            contactService = { testContactService(store = store) },
            blockedChannelSenderService = { null },
        ),
    )
}
