package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

/** Records every `deleteChannelMessages` call so tests can assert the block deleted the sender's messages. */
internal class RecordingChannelMessageDeleter : ChannelMessageDeleting {
    data class Call(val senderName: String, val radioID: Uuid)

    val calls = mutableListOf<Call>()

    override suspend fun deleteChannelMessages(senderName: String, radioID: Uuid) {
        calls += Call(senderName, radioID)
    }
}

/** Records each blocked-name-cache refresh so tests can assert block/unblock rebuilt the cache. */
internal class RecordingBlockedNamesRefresher : BlockedNamesRefreshing {
    val refreshedRadioIDs = mutableListOf<Uuid>()

    override suspend fun refresh(radioID: Uuid) {
        refreshedRadioIDs += radioID
    }
}

/** Records the contacts/conversations change notifications the cleanup and sender service emit. */
internal class RecordingContactSyncCoordinator : ContactSyncCoordinator {
    var contactsChanged = 0
    var conversationsChanged = 0

    override suspend fun notifyContactsChanged() {
        contactsChanged++
    }

    override suspend fun notifyConversationsChanged() {
        conversationsChanged++
    }
}

/** Records the notification-cleanup calls (removed-notification contact ids and badge updates). */
internal class RecordingNotificationCleanup : ContactNotificationCleanup {
    val removedForContactIDs = mutableListOf<Uuid>()
    var badgeUpdates = 0

    override suspend fun removeDeliveredNotifications(contactID: Uuid) {
        removedForContactIDs += contactID
    }

    override suspend fun updateBadgeCount() {
        badgeUpdates++
    }
}

/** Records the remote-node session removals a delete cleanup triggers. */
internal class RecordingRemoteNodeSessionCleanup : RemoteNodeSessionCleanup {
    val removedPublicKeys = mutableListOf<ByteArray>()

    override suspend fun removeSession(publicKey: ByteArray) {
        removedPublicKeys += publicKey
    }
}

/** A blocked-name sink capturing the last published set; the test double for the W2 `BlockedSendersCache`. */
internal class FakeBlockedNamesCache : BlockedNamesCache {
    var lastNames: Set<String> = emptySet()

    override suspend fun refresh(names: Collection<String>) {
        lastNames = names.toSet()
    }
}

/** An in-memory [StaleNodeCleanupPreferences] with a settable threshold and last-run clock. */
internal class FakeStaleNodeCleanupPreferences(
    private var thresholdDays: Int = 0,
    private var lastRunSeconds: Long = 0L,
) : StaleNodeCleanupPreferences {
    override suspend fun thresholdDays(): Int = thresholdDays
    override suspend fun lastRunSeconds(): Long = lastRunSeconds
    override suspend fun setLastRunSeconds(seconds: Long) {
        lastRunSeconds = seconds
    }
}

/** A [StaleNodeRemoving] that returns a canned result (or throws), recording each threshold it was asked. */
internal class FakeStaleNodeRemover(
    private val result: StaleNodeRemovalResult = StaleNodeRemovalResult(removed = 0, total = 0),
    private val error: Throwable? = null,
) : StaleNodeRemoving {
    val requestedDays = mutableListOf<Int>()

    override suspend fun removeStaleNodes(olderThanDays: Int): StaleNodeRemovalResult {
        requestedDays += olderThanDays
        error?.let { throw it }
        return result
    }
}
