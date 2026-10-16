package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import one.meshcore.shared.backup.BlockedChannelSenderDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/**
 * Blocks and unblocks channel sender names — the name-only block for senders heard on a channel, which
 * may not be contacts at all. Blocking persists the name and deletes that sender's existing channel
 * messages; both paths rebuild the blocked-name cache and
 * refresh conversations. Kotlin port of the Swift `ChatConversationView.performBlock` channel-sender
 * effects and `BlockedChannelSendersView.unblock`.
 *
 * Blocking any matching contact rows (a channel sender that is also a contact) is the caller's job, via
 * [ContactService.updateContactPreferences]; that path runs [ContactCleanupCoordinator] independently.
 */
class BlockedChannelSenderService(
    private val store: BlockedChannelSenderStore,
    private val channelMessageDeleter: ChannelMessageDeleting,
    private val blockedNamesRefresher: BlockedNamesRefreshing,
    private val syncCoordinator: ContactSyncCoordinator,
    private val now: () -> Long,
    private val newId: () -> Uuid,
) {
    /**
     * Blocks [senderName] on [radioID]: persists the block, deletes the sender's existing channel messages,
     * rebuilds the blocked-name cache, and refreshes conversations.
     */
    suspend fun blockSender(radioID: Uuid, senderName: String) {
        store.save(
            BlockedChannelSenderDto(
                id = newId(),
                name = senderName,
                radioID = radioID,
                dateBlocked = now().toDouble(),
            ),
        )
        channelMessageDeleter.deleteChannelMessages(senderName = senderName, radioID = radioID)
        blockedNamesRefresher.refresh(radioID)
        syncCoordinator.notifyConversationsChanged()
    }

    /** Unblocks [senderName] on [radioID], then rebuilds the blocked-name cache and refreshes conversations. */
    suspend fun unblockSender(radioID: Uuid, senderName: String) {
        store.delete(radioID = radioID, name = senderName)
        blockedNamesRefresher.refresh(radioID)
        syncCoordinator.notifyConversationsChanged()
    }

    /** A radio's blocked senders, newest block first (the Blocked Channel Senders screen query). */
    suspend fun blockedSenders(radioID: Uuid): List<BlockedChannelSenderDto> = store.list(radioID)

    /**
     * Connect-time purge of channel messages from [blockedNames] (blocked contacts ∪ blocked channel
     * senders). After the first pass, deletes match zero rows and are free. Mirrors iOS
     * `SyncCoordinator.deleteBlockedSenderMessages` — handles legacy rows from versions that filtered
     * at read time instead of deleting at block time.
     */
    suspend fun purgeExistingMessagesForBlockedNames(radioID: Uuid, blockedNames: Set<String>) {
        for (name in blockedNames) {
            if (name.isEmpty()) continue
            deleteBlockedSenderMessages(radioID, name)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun deleteBlockedSenderMessages(radioID: Uuid, name: String) {
        try {
            channelMessageDeleter.deleteChannelMessages(senderName = name, radioID = radioID)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PersistentLogger(CoroutineScope(currentCoroutineContext()), category = "BlockedChannelSenderService")
                .warning("Blocked sender message purge failed: ${error::class.simpleName}")
        }
    }
}
