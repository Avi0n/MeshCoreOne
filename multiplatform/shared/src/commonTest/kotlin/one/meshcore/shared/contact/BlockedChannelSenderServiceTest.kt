package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `ChatConversationView.performBlock` channel-sender effects and
 * `BlockedChannelSendersView.unblock`: blocking persists the name and deletes that sender's channel
 * messages, unblocking drops it, and both rebuild the blocked-name cache and refresh conversations.
 */
class BlockedChannelSenderServiceTest {
    private class Fixture(deleter: ChannelMessageDeleting? = null) {
        val contactStore = InMemoryContactStore()
        val senderStore = InMemoryBlockedChannelSenderStore()
        val channelMessages = RecordingChannelMessageDeleter()
        val cache = FakeBlockedNamesCache()
        val refresher = ContactBlockedNamesRefresher(contactStore, senderStore, cache)
        val syncCoordinator = RecordingContactSyncCoordinator()
        var clock = 1_700_000_000L

        val service = BlockedChannelSenderService(
            store = senderStore,
            channelMessageDeleter = deleter ?: channelMessages,
            blockedNamesRefresher = refresher,
            syncCoordinator = syncCoordinator,
            now = { clock },
            newId = { ContactTestIds.fresh() },
        )
    }

    @Test
    fun blockPersistsDeletesChannelMessagesAndRefreshesCache() = runTest {
        val f = Fixture()

        f.service.blockSender(ContactTestIds.RADIO, "Spammer")

        val stored = f.service.blockedSenders(ContactTestIds.RADIO)
        assertEquals(listOf("Spammer"), stored.map { it.name })
        assertEquals(1, f.channelMessages.calls.size)
        assertEquals("Spammer", f.channelMessages.calls[0].senderName)
        assertEquals(ContactTestIds.RADIO, f.channelMessages.calls[0].radioID)
        assertEquals(setOf("Spammer"), f.cache.lastNames)
        assertEquals(1, f.syncCoordinator.conversationsChanged)
    }

    @Test
    fun unblockRemovesAndRefreshesWithoutDeletingMessages() = runTest {
        val f = Fixture()
        f.service.blockSender(ContactTestIds.RADIO, "Spammer")
        f.channelMessages.calls.clear()

        f.service.unblockSender(ContactTestIds.RADIO, "Spammer")

        assertTrue(f.service.blockedSenders(ContactTestIds.RADIO).isEmpty())
        assertTrue(f.channelMessages.calls.isEmpty())
        assertEquals(emptySet(), f.cache.lastNames)
    }

    @Test
    fun cacheUnionsBlockedContactNamesAndBlockedSenderNames() = runTest {
        val f = Fixture()
        f.contactStore.saveContact(
            contactDto(ContactTestIds.fresh(), ContactTestIds.RADIO, name = "Blocked Contact", isBlocked = true),
        )

        f.service.blockSender(ContactTestIds.RADIO, "Spammer")

        assertEquals(setOf("Blocked Contact", "Spammer"), f.cache.lastNames)
    }

    @Test
    fun blockingTheSameNameTwiceKeepsOneRowAndTakesTheNewDate() = runTest {
        val f = Fixture()
        f.clock = 100L
        f.service.blockSender(ContactTestIds.RADIO, "Spammer")
        f.clock = 200L
        f.service.blockSender(ContactTestIds.RADIO, "Spammer")

        val stored = f.service.blockedSenders(ContactTestIds.RADIO)
        assertEquals(1, stored.size)
        assertEquals(200.0, stored[0].dateBlocked)
    }

    @Test
    fun blockedSendersAreListedNewestFirst() = runTest {
        val f = Fixture()
        f.clock = 100L
        f.service.blockSender(ContactTestIds.RADIO, "Older")
        f.clock = 200L
        f.service.blockSender(ContactTestIds.RADIO, "Newer")

        val stored = f.service.blockedSenders(ContactTestIds.RADIO)
        assertEquals(listOf("Newer", "Older"), stored.map { it.name })
    }

    @Test
    fun purgeExistingMessagesForBlockedNamesDeletesEachName() = runTest {
        val f = Fixture()
        f.channelMessages.calls.clear()

        f.service.purgeExistingMessagesForBlockedNames(
            ContactTestIds.RADIO,
            setOf("Spammer", "Bot", ""),
        )

        assertEquals(2, f.channelMessages.calls.size)
        assertEquals(setOf("Spammer", "Bot"), f.channelMessages.calls.map { it.senderName }.toSet())
        // Empty names skipped — no third call.
    }

    @Test
    fun purgeExistingMessagesForBlockedNamesNoOpsOnEmptySet() = runTest {
        val f = Fixture()
        f.channelMessages.calls.clear()
        f.service.purgeExistingMessagesForBlockedNames(ContactTestIds.RADIO, emptySet())
        assertTrue(f.channelMessages.calls.isEmpty())
    }

    @Test
    fun cancelledPurgeStopsBeforeDeletingTheNextSender() = runTest {
        val cancelled = CancellationException("purge cancelled")
        val attempts = mutableListOf<String>()
        val f = Fixture(object : ChannelMessageDeleting {
            override suspend fun deleteChannelMessages(senderName: String, radioID: one.meshcore.shared.model.Uuid) {
                attempts += senderName
                throw cancelled
            }
        })

        val thrown = assertFailsWith<CancellationException> {
            f.service.purgeExistingMessagesForBlockedNames(ContactTestIds.RADIO, linkedSetOf("first", "second"))
        }

        assertSame(cancelled, thrown)
        assertEquals(listOf("first"), attempts)
    }
}
