package one.meshcore.shared.contact

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.RecordingNotifier
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.InMemoryRemoteNodeStore
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.testSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `ContactCleanupCoordinator` side effects: channel-message deletion and blocked-name
 * cache refresh on block, notification cleanup on block/delete, an always-on badge update, and
 * remote-node session teardown on delete.
 */
class ContactCleanupCoordinatorTest {
    private class Fixture(private val isBlocked: Boolean) {
        val store = InMemoryContactStore()
        val channelMessages = RecordingChannelMessageDeleter()
        val blockedNames = RecordingBlockedNamesRefresher()
        val syncCoordinator = RecordingContactSyncCoordinator()
        val notifications = RecordingNotificationCleanup()
        val remoteNodes = RecordingRemoteNodeSessionCleanup()
        val contactID = ContactTestIds.fresh()
        val key = publicKey(0x11)

        val coordinator = ContactCleanupCoordinator(
            contactStore = store,
            channelMessageDeleter = channelMessages,
            blockedNamesRefresher = blockedNames,
            syncCoordinator = syncCoordinator,
            notifications = notifications,
            remoteNodes = remoteNodes,
        )

        suspend fun seed() {
            store.saveContact(
                contactDto(
                    id = contactID,
                    radioID = ContactTestIds.RADIO,
                    keyByte = 0x11,
                    name = "Blocked Node",
                    isBlocked = isBlocked,
                ),
            )
        }
    }

    @Test
    fun blockDeletesChannelMessagesRefreshesCacheAndNotifies() = runTest {
        val f = Fixture(isBlocked = true)
        f.seed()

        f.coordinator.handleCleanup(f.contactID, ContactCleanupReason.BLOCKED, f.key)

        assertEquals(1, f.channelMessages.calls.size)
        assertEquals("Blocked Node", f.channelMessages.calls[0].senderName)
        assertEquals(ContactTestIds.RADIO, f.channelMessages.calls[0].radioID)
        assertEquals(listOf(ContactTestIds.RADIO), f.blockedNames.refreshedRadioIDs)
        assertEquals(1, f.syncCoordinator.conversationsChanged)
        assertEquals(listOf(f.contactID), f.notifications.removedForContactIDs)
        assertEquals(1, f.notifications.badgeUpdates)
    }

    @Test
    fun unblockRefreshesCacheButDoesNotDeleteChannelMessages() = runTest {
        val f = Fixture(isBlocked = false)
        f.seed()

        f.coordinator.handleCleanup(f.contactID, ContactCleanupReason.UNBLOCKED, f.key)

        assertTrue(f.channelMessages.calls.isEmpty())
        assertEquals(listOf(ContactTestIds.RADIO), f.blockedNames.refreshedRadioIDs)
        assertEquals(1, f.syncCoordinator.conversationsChanged)
        assertTrue(f.notifications.removedForContactIDs.isEmpty())
        assertEquals(1, f.notifications.badgeUpdates)
    }

    @Test
    fun deleteRemovesRemoteNodeSessionAndDeliveredNotifications() = runTest {
        val f = Fixture(isBlocked = false)
        f.seed()

        f.coordinator.handleCleanup(f.contactID, ContactCleanupReason.DELETED, f.key)

        assertTrue(f.channelMessages.calls.isEmpty())
        assertTrue(f.blockedNames.refreshedRadioIDs.isEmpty())
        assertEquals(listOf(f.contactID), f.notifications.removedForContactIDs)
        assertEquals(1, f.notifications.badgeUpdates)
        assertEquals(1, f.remoteNodes.removedPublicKeys.size)
        assertTrue(f.remoteNodes.removedPublicKeys[0].contentEquals(f.key))
        assertEquals(1, f.syncCoordinator.conversationsChanged)
    }

    @Test
    fun missingContactSkipsBlockSideEffectsButStillUpdatesBadge() = runTest {
        val f = Fixture(isBlocked = true)
        // No seed: the contact row is absent (e.g. deleted before the cleanup ran).

        f.coordinator.handleCleanup(f.contactID, ContactCleanupReason.BLOCKED, f.key)

        assertTrue(f.channelMessages.calls.isEmpty())
        assertTrue(f.blockedNames.refreshedRadioIDs.isEmpty())
        assertEquals(0, f.syncCoordinator.conversationsChanged)
        // Block still removes delivered notifications (contact-id-keyed) and updates the badge.
        assertEquals(listOf(f.contactID), f.notifications.removedForContactIDs)
        assertEquals(1, f.notifications.badgeUpdates)
    }

    @Test
    fun productionAdaptersForwardToNotifierAndRemoteNodeService() = runTest {
        val notifier = RecordingNotifier()
        val store = InMemoryRemoteNodeStore()
        val passwordStore = InMemoryNodePasswordStore()
        val remote = RemoteNodeService(
            nodeSession = FakeNodeSession(),
            store = store,
            passwordStore = passwordStore,
            scope = backgroundScope,
        )
        val key = publicKey(0x42)
        val sessionId = ContactTestIds.fresh()
        store.saveSession(
            testSession(
                id = sessionId,
                radioID = ContactTestIds.RADIO,
                publicKey = key,
            ),
        )
        passwordStore.storePassword(key, "secret")

        val notifications = NotifierContactNotificationCleanup(notifier)
        val remoteCleanup = RemoteNodeServiceSessionCleanup(store, remote)
        val contactID = ContactTestIds.fresh()

        notifications.removeDeliveredNotifications(contactID)
        notifications.updateBadgeCount()
        remoteCleanup.removeSession(key)

        assertEquals(listOf(contactID), notifier.removedDeliveredForContactIDs)
        assertEquals(1, notifier.badgeUpdates)
        assertNull(store.fetchSessionByPublicKey(key))
        assertNull(passwordStore.retrievePassword(key))
    }
}
