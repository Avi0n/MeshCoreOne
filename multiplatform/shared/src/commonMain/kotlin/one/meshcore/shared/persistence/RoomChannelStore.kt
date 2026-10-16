package one.meshcore.shared.persistence

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.channel.ChannelLocalUpdates
import one.meshcore.shared.channel.ChannelStore
import one.meshcore.shared.chat.pathdetail.ChannelMessageDeleter
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.Uuid

/**
 * Room-backed [ChannelStore], the production adapter [one.meshcore.shared.channel.ChannelService] drives.
 * Rows key on `(radioID, index)` (both frozen) and a same-secret frame upsert rewrites only name+secret, so
 * local-only fields survive. A different secret is a different occupant: messages and derived counters
 * are wiped first. Unconfigured and pruned slots wipe leftover messages even when the row is already
 * gone. The clear/delete cascade drops the channel's messages through [messageDao].
 *
 * [frameMutex] serializes the read-modify-write of an upsert so a concurrent sync cannot assign two rows the
 * same `(radioID, index)` — the Room analogue of Swift `@ModelActor` serialization.
 */
class RoomChannelStore internal constructor(parts: RoomChannelParts) :
    ChannelStore,
    ChannelLocalUpdates by RoomChannelLocalUpdates(parts.channelDao) {
    constructor(database: MeshDatabase, newId: () -> Uuid = Uuid::random) : this(
        RoomChannelParts(database, newId),
    )

    private val database = parts.database
    private val newId = parts.newId
    private val channelDao = parts.channelDao
    private val messageDao = parts.messageDao
    private val pendingSendDao = database.pendingSendDao()
    private val reactionDao = database.reactionDao()
    private val messageRepeatDao = database.messageRepeatDao()
    private val frameMutex = Mutex()

    override suspend fun fetchChannels(radioID: Uuid): List<ChannelDto> =
        channelDao.listByRadio(radioID).map { it.toDto() }

    override suspend fun fetchChannel(radioID: Uuid, index: Int): ChannelDto? =
        channelDao.getByRadioAndIndex(radioID, index)?.toDto()

    override suspend fun saveChannel(radioID: Uuid, info: ChannelInfo): Uuid = frameMutex.withLock {
        withWriterTransaction { upsertFrame(radioID, info).id }
    }

    override suspend fun batchSaveChannels(
        radioID: Uuid,
        configured: List<ChannelInfo>,
        unconfiguredIndices: List<Int>,
        pruneBeyond: Int?,
    ): List<ChannelDto> = batchSaveChannels(radioID, configured, unconfiguredIndices, pruneBeyond, beforeCommit = null)

    /**
     * Runs the whole sync pass inside one transaction so a failure part-way through leaves the store
     * exactly as it was, rather than committing the unconfigured-slot deletes while the upserts that were
     * meant to replace them are lost — which would strand the messages keyed to those slots. Mirrors the
     * Swift store staging the pass on one context and calling `rollback()` on a failed `save()`.
     * [beforeCommit] runs at the commit point so a test can prove the rollback.
     */
    internal suspend fun batchSaveChannels(
        radioID: Uuid,
        configured: List<ChannelInfo>,
        unconfiguredIndices: List<Int>,
        pruneBeyond: Int?,
        beforeCommit: (suspend () -> Unit)?,
    ): List<ChannelDto> = frameMutex.withLock {
        withWriterTransaction {
            configured.forEach { upsertFrame(radioID, it) }
            for (index in unconfiguredIndices) {
                cascadeDeleteChannelMessages(radioID, index)
                channelDao.getByRadioAndIndex(radioID, index)?.let { channelDao.deleteById(it.id) }
            }
            if (pruneBeyond != null) {
                channelDao.listByRadio(radioID)
                    .filter { it.index >= pruneBeyond }
                    .forEach { row ->
                        cascadeDeleteChannelMessages(radioID, row.index)
                        channelDao.deleteById(row.id)
                    }
            }
            val rows = channelDao.listByRadio(radioID).map { it.toDto() }
            beforeCommit?.invoke()
            rows
        }
    }

    override suspend fun deleteChannel(id: Uuid) {
        frameMutex.withLock {
            withWriterTransaction {
                val row = channelDao.getById(id) ?: return@withWriterTransaction
                cascadeDeleteChannelMessages(row.radioID, row.index)
                channelDao.deleteById(id)
            }
        }
    }

    /**
     * Drops the slot's messages together with the rows that hang off them, in one transaction. A pending
     * send that outlived its message is re-enqueued by the send queue's hydrate on the next connect, which
     * would broadcast text the user cleared. No entity declares a foreign key, so the cascade is explicit,
     * as the Swift store's is.
     */
    override suspend fun deleteMessagesForChannel(radioID: Uuid, channelIndex: Int) {
        withWriterTransaction {
            cascadeDeleteChannelMessages(radioID, channelIndex)
        }
    }

    private suspend fun <T> withWriterTransaction(block: suspend () -> T): T {
        val owner = currentCoroutineContext().job
        owner.ensureActive()
        return database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                owner.ensureActive()
                val result = block()
                owner.ensureActive()
                result
            }
        }
    }

    // Insert-or-merge one frame onto its existing row (if any). A same-secret update rewrites only
    // name+secret; a different secret wipes the previous occupant's history first. Callers hold
    // [frameMutex] and run this inside an open writer transaction.
    private suspend fun upsertFrame(radioID: Uuid, info: ChannelInfo): ChannelDto {
        val existing = channelDao.getByRadioAndIndex(radioID, info.index)
        val row = if (existing != null) {
            if (existing.secret.contentEquals(info.secret)) {
                existing.copy(name = info.name, secret = info.secret)
            } else {
                cascadeDeleteChannelMessages(radioID, info.index)
                existing.copy(
                    name = info.name,
                    secret = info.secret,
                    unreadCount = 0,
                    unreadMentionCount = 0,
                    lastMessageDate = null,
                )
            }
        } else {
            ChannelEntity(
                id = newId(),
                radioID = radioID,
                index = info.index,
                name = info.name,
                secret = info.secret,
                isEnabled = true,
                unreadCount = 0,
            )
        }
        channelDao.upsertPreservingPin(row)
        return row.toDto()
    }

    private suspend fun cascadeDeleteChannelMessages(radioID: Uuid, channelIndex: Int) {
        val messageIDs = messageDao.channelMessageIds(radioID, channelIndex)
        for (chunk in messageIDs.chunked(ChannelMessageDeleter.DELETION_CHUNK_SIZE)) {
            pendingSendDao.deleteByMessageIds(chunk)
            reactionDao.deleteByMessageIds(chunk)
            messageRepeatDao.deleteByMessageIds(chunk)
        }
        messageDao.deleteMessagesForChannel(radioID, channelIndex)
    }
}
