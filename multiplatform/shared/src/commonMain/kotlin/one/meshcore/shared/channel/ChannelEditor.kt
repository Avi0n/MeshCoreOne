package one.meshcore.shared.channel

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import one.meshcore.shared.contact.MAX_USABLE_NAME_BYTES
import one.meshcore.shared.contact.utf8BytePrefix
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.Uuid

/** Slot writes: create, replace, flood scope, and clear. */
interface ChannelEditing {
    /** Hashes [passphrase] to a secret, truncates [name], writes the slot, and refreshes the RX cache. */
    suspend fun setChannel(radioID: Uuid, index: Int, name: String, passphrase: String)

    /** Writes a slot with a precomputed 16-byte [secret], validating its length first. */
    suspend fun setChannelWithSecret(radioID: Uuid, index: Int, name: String, secret: ByteArray)

    /**
     * Persists a channel's local flood-scope preference (the app-side send region), a store-only write with
     * no radio push. Mirrors the Swift `PersistenceStore.setChannelFloodScope` the join/URL flows apply.
     */
    suspend fun setChannelFloodScope(channelID: Uuid, scope: ChannelFloodScope)

    /**
     * Leaves/deletes a channel: capture the row first (an empty-named channel is hard to re-find), clear the
     * slot on the device, delete its messages and local row, drop drafts for the freed slot, refresh the RX
     * cache. Mirrors `clearChannel(radioID:index:)`.
     */
    suspend fun clearChannel(radioID: Uuid, index: Int)

    /**
     * Clears a channel's messages while keeping the channel: also clears the last-message date (no preview)
     * and zeroes both unread counters so an emptied channel does not inflate the badge. Mirrors
     * `clearChannelMessages(radioID:channelIndex:)`.
     */
    suspend fun clearChannelMessages(radioID: Uuid, channelIndex: Int)

    /** Creates or resets the reserved public channel on slot 0 with its fixed name and secret. */
    suspend fun setupPublicChannel(radioID: Uuid)
}

internal class ChannelEditor(
    private val session: ChannelSession,
    private val store: ChannelStore,
    private val state: ChannelServiceState,
    private val cache: ChannelRxCacheRefresh,
) : ChannelEditing {
    override suspend fun setChannel(radioID: Uuid, index: Int, name: String, passphrase: String) {
        writeChannel(radioID, index, name.utf8BytePrefix(MAX_USABLE_NAME_BYTES), ChannelSecret.hashSecret(passphrase))
    }

    override suspend fun setChannelWithSecret(radioID: Uuid, index: Int, name: String, secret: ByteArray) {
        if (!ChannelSecret.validateSecret(secret)) throw ChannelServiceError.SecretHashingFailed()
        writeChannel(radioID, index, name.utf8BytePrefix(MAX_USABLE_NAME_BYTES), secret)
    }

    override suspend fun setChannelFloodScope(channelID: Uuid, scope: ChannelFloodScope) {
        store.updateChannelFloodScope(channelID, scope)
    }

    override suspend fun clearChannel(radioID: Uuid, index: Int) {
        val callerContext = currentCoroutineContext()
        val channelToDelete = store.fetchChannel(radioID, index)
        mapChannelSessionError { session.setChannel(index, "", ByteArray(ChannelSecret.SIZE_BYTES)) }
        // Capture the confirmed slot clear even if the caller leaves the screen.
        withContext(NonCancellable) {
            store.deleteMessagesForChannel(radioID, index)
            channelToDelete?.let { store.deleteChannel(it.id) }
        }
        callerContext.ensureActive()
        state.slotOccupantChanged.onOccupantChanged(radioID, setOf(index))
        callerContext.ensureActive()
        cache.fromStore(radioID)
    }

    override suspend fun clearChannelMessages(radioID: Uuid, channelIndex: Int) {
        store.deleteMessagesForChannel(radioID, channelIndex)
        store.fetchChannel(radioID, channelIndex)?.let {
            store.updateChannelLastMessage(it.id, null)
            store.clearChannelUnreadCount(it.id)
            store.clearChannelUnreadMentionCount(it.id)
        }
    }

    override suspend fun setupPublicChannel(radioID: Uuid) {
        setChannelWithSecret(
            radioID,
            index = ChannelSecret.PUBLIC_CHANNEL_SLOT,
            name = ChannelSecret.PUBLIC_CHANNEL_NAME,
            secret = ChannelSecret.publicChannelSecret(),
        )
    }

    private suspend fun writeChannel(radioID: Uuid, index: Int, name: String, secret: ByteArray) {
        val callerContext = currentCoroutineContext()
        val prior = store.fetchChannel(radioID, index)
        mapChannelSessionError { session.setChannel(index, name, secret) }
        // Capture the confirmed slot configuration even if the caller leaves the screen.
        withContext(NonCancellable) {
            store.saveChannel(radioID, ChannelInfo(index = index, name = name, secret = secret))
        }
        callerContext.ensureActive()
        if (prior != null && !prior.secret.contentEquals(secret)) {
            state.slotOccupantChanged.onOccupantChanged(radioID, setOf(index))
        }
        callerContext.ensureActive()
        cache.fromStore(radioID)
    }
}
