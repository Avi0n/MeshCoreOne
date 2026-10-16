package one.meshcore.shared.backup

import one.meshcore.shared.chat.DeduplicationKey
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Pure backup merge helpers mirroring `PersistenceStore+BackupHelpers.swift`.
 * Adopt foreign values only over local defaults; never un-block, un-mute, un-favorite, or unpin.
 * Room wiring (fetch + apply) lands with the import orchestrator.
 * Row identity strings live on [BackupMergeKeys].
 */
@OptIn(ExperimentalTime::class)
object BackupMerge {
    private const val MILLIS_PER_SECOND = 1000

    /** iOS `SyncCoordinator.timestampToleranceFuture` (5 minutes) for phone-clock lastHeard stamps. */
    const val TIMESTAMP_TOLERANCE_FUTURE_SECONDS: Long = 5L * 60L

    /**
     * Prefix marking a backup key that carries a message's own identity rather than its content.
     * Mirrors Swift `DeduplicationKey.outgoingIdentityPrefix`; it lives here because only the backup
     * reconciliation path forms this key, never live ingest.
     */
    const val OUTGOING_IDENTITY_PREFIX = "out-"

    /**
     * Dedup key for backup reconciliation of one message. Outgoing messages key on their own id so two
     * intentional sends with identical text, timestamp and recipient do not collapse on restore.
     * Incoming messages key on their stored live-sync dedup key (or a content-based derivation when the
     * field was never populated), scoped by `radioID` so two companion radios that both received the
     * same wire packet restore as two rows instead of collapsing into one. Mirrors Swift
     * `messageBackupKey`.
     */
    fun messageBackupKey(dto: MessageDto): String {
        if (dto.direction == MessageDirection.OUTGOING) {
            return "$OUTGOING_IDENTITY_PREFIX${dto.id.value}"
        }
        val base = dto.deduplicationKey ?: contentDeduplicationKey(dto)
        return "${dto.radioID.value}-$base"
    }

    /**
     * The content-based dedup key for an incoming message, used both to key it on import and to
     * backfill the field on export for rows written before it existed. Mirrors Swift
     * `fetchAllMessagesForBackup`'s backfill.
     */
    fun contentDeduplicationKey(dto: MessageDto): String = DeduplicationKey.contentBased(
        contactID = dto.contactID,
        channelIndex = dto.channelIndex,
        senderNodeName = dto.senderNodeName,
        timestamp = dto.timestamp,
        content = dto.text,
    )

    /**
     * Two distinct snapshots for one node within the same millisecond intentionally coalesce (the
     * second counts as skipped); the id is deliberately not part of the key so re-import stays
     * idempotent. Mirrors Swift `nodeStatusSnapshotKey`.
     */
    fun nodeStatusSnapshotKey(nodePublicKey: ByteArray, timestamp: Double): String {
        val milliseconds = (timestamp * MILLIS_PER_SECOND).toLong()
        return "${nodePublicKey.toBase64()}-$milliseconds"
    }

    /**
     * Rewrite the contact-UUID segment of a DM dedup key. Only the `dm-{uuid}-` prefix is
     * touched (mirrors Swift `rewriteDMDeduplicationKey`).
     */
    fun rewriteDMDeduplicationKey(key: String, fromBackupId: Uuid, toLocalId: Uuid): String {
        val oldPrefix = "${DeduplicationKey.DIRECT_MESSAGE_PREFIX}${fromBackupId.value}-"
        if (!key.startsWith(oldPrefix)) return key
        val newPrefix = "${DeduplicationKey.DIRECT_MESSAGE_PREFIX}${toLocalId.value}-"
        return newPrefix + key.removePrefix(oldPrefix)
    }

    /**
     * Rewrite the channel-index segment of a content-based channel dedup key when a backup
     * channel relocates to a different local slot.
     */
    fun rewriteChannelDeduplicationKey(key: String, fromBackupIndex: Int, toLocalIndex: Int): String {
        val oldPrefix = "${DeduplicationKey.CHANNEL_PREFIX}$fromBackupIndex-"
        if (!key.startsWith(oldPrefix)) return key
        val newPrefix = "${DeduplicationKey.CHANNEL_PREFIX}$toLocalIndex-"
        return newPrefix + key.removePrefix(oldPrefix)
    }

    /**
     * Merge backup contact metadata into an existing local contact. Returns the merged row and
     * whether any field changed (Swift returns `Bool` for save gating).
     */
    fun mergeContactMetadata(
        local: ContactDto,
        foreign: ContactDto,
        nowEpochSeconds: Long = Clock.System.now().epochSeconds,
    ): MergeResult<ContactDto> {
        val nickname = adoptIfLocalNull(local.nickname, foreign.nickname)
        val isBlocked = adoptTrueFlag(local.isBlocked, foreign.isBlocked)
        val isMuted = adoptTrueFlag(local.isMuted, foreign.isMuted)
        val isFavorite = adoptTrueFlag(local.isFavorite, foreign.isFavorite)
        val isPinned = adoptTrueFlag(local.isPinned, foreign.isPinned)
        val lastMessageDate = adoptLaterDate(local.lastMessageDate, foreign.lastMessageDate)
        val unreadCount = maxOf(local.unreadCount, foreign.unreadCount)
        val unreadMentionCount = maxOf(local.unreadMentionCount, foreign.unreadMentionCount)
        val ocvPreset = adoptIfLocalNull(local.ocvPreset, foreign.ocvPreset)
        val customOCVArrayString = adoptIfLocalNull(local.customOCVArrayString, foreign.customOCVArrayString)
        val avatarImageData = adoptIfLocalNull(local.avatarImageData, foreign.avatarImageData)
        val lastHeardTimestamp = maxHeard(
            local.lastHeardTimestamp,
            clampedBackupLastHeardTimestamp(foreign.lastHeardTimestamp, nowEpochSeconds),
        )
        val changed =
            nickname != local.nickname ||
                isBlocked != local.isBlocked ||
                isMuted != local.isMuted ||
                isFavorite != local.isFavorite ||
                isPinned != local.isPinned ||
                lastMessageDate != local.lastMessageDate ||
                unreadCount != local.unreadCount ||
                unreadMentionCount != local.unreadMentionCount ||
                ocvPreset != local.ocvPreset ||
                customOCVArrayString != local.customOCVArrayString ||
                avatarImageData != local.avatarImageData ||
                lastHeardTimestamp != local.lastHeardTimestamp
        if (!changed) return MergeResult(local, changed = false)
        return MergeResult(
            local.copy(
                nickname = nickname,
                isBlocked = isBlocked,
                isMuted = isMuted,
                isFavorite = isFavorite,
                isPinned = isPinned,
                lastMessageDate = lastMessageDate,
                unreadCount = unreadCount,
                unreadMentionCount = unreadMentionCount,
                ocvPreset = ocvPreset,
                customOCVArrayString = customOCVArrayString,
                avatarImageData = avatarImageData,
                lastHeardTimestamp = lastHeardTimestamp,
            ),
            changed = true,
        )
    }

    fun mergeChannelMetadata(local: ChannelDto, foreign: ChannelDto): MergeResult<ChannelDto> {
        val lastMessageDate = adoptLaterDate(local.lastMessageDate, foreign.lastMessageDate)
        val unreadCount = maxOf(local.unreadCount, foreign.unreadCount)
        val unreadMentionCount = maxOf(local.unreadMentionCount, foreign.unreadMentionCount)
        val notificationLevel = adoptNotificationIfDefault(local.notificationLevel, foreign.notificationLevel)
        val isPinned = adoptTrueFlag(local.isPinned, foreign.isPinned)
        val flood = adoptFloodScopeIfInherit(local, foreign)
        val changed =
            lastMessageDate != local.lastMessageDate ||
                unreadCount != local.unreadCount ||
                unreadMentionCount != local.unreadMentionCount ||
                notificationLevel != local.notificationLevel ||
                isPinned != local.isPinned ||
                flood.mode != local.floodScopeModeRawValue ||
                flood.region != local.regionScope
        if (!changed) return MergeResult(local, changed = false)
        return MergeResult(
            local.copy(
                lastMessageDate = lastMessageDate,
                unreadCount = unreadCount,
                unreadMentionCount = unreadMentionCount,
                notificationLevel = notificationLevel,
                isPinned = isPinned,
                floodScopeModeRawValue = flood.mode,
                regionScope = flood.region,
            ),
            changed = true,
        )
    }

    fun mergeRemoteNodeSessionMetadata(
        local: RemoteNodeSessionDto,
        foreign: RemoteNodeSessionDto,
    ): MergeResult<RemoteNodeSessionDto> {
        val unreadCount = maxOf(local.unreadCount, foreign.unreadCount)
        val notificationLevel = adoptNotificationIfDefault(local.notificationLevel, foreign.notificationLevel)
        val isPinned = adoptTrueFlag(local.isPinned, foreign.isPinned)
        val lastSyncTimestamp = maxOf(local.lastSyncTimestamp, foreign.lastSyncTimestamp)
        val lastMessageDate = adoptLaterDate(local.lastMessageDate, foreign.lastMessageDate)
        val changed =
            unreadCount != local.unreadCount ||
                notificationLevel != local.notificationLevel ||
                isPinned != local.isPinned ||
                lastSyncTimestamp != local.lastSyncTimestamp ||
                lastMessageDate != local.lastMessageDate
        if (!changed) return MergeResult(local, changed = false)
        return MergeResult(
            local.copy(
                unreadCount = unreadCount,
                notificationLevel = notificationLevel,
                isPinned = isPinned,
                lastSyncTimestamp = lastSyncTimestamp,
                lastMessageDate = lastMessageDate,
            ),
            changed = true,
        )
    }

    /**
     * Clamps a backup phone-clock stamp so a future exporting clock cannot pin sort/prune under
     * max-wins. Nil (legacy envelope) becomes 0. Shares the same 5-minute future bound as live
     * `touchContactHeard`.
     */
    fun clampedBackupLastHeardTimestamp(stamp: Long?, nowEpochSeconds: Long): Long {
        val raw = stamp ?: return 0L
        val upperBound = nowEpochSeconds + TIMESTAMP_TOLERANCE_FUTURE_SECONDS
        return minOf(raw, upperBound).coerceAtLeast(0L)
    }

    /** Max-wins lastHeard. A missing local stamp and a zero foreign stamp stay missing. */
    fun lastHeardForInsert(stamp: Long?, nowEpochSeconds: Long): Long? {
        val clamped = clampedBackupLastHeardTimestamp(stamp, nowEpochSeconds)
        return clamped.takeIf { it > 0L }
    }
}

/** Identity strings that match a backup row to the local row with the same radio and payload. */
internal object BackupMergeKeys {
    fun contactKey(radioID: Uuid, publicKey: ByteArray): String = "${radioID.value}-${publicKey.toBase64()}"

    fun channelKey(radioID: Uuid, index: Int): String = "${radioID.value}-$index"

    fun remoteNodeSessionKey(radioID: Uuid, publicKey: ByteArray): String = "${radioID.value}-${publicKey.toBase64()}"

    fun savedTracePathKey(radioID: Uuid, pathBytes: ByteArray, hashSize: Int): String =
        "${radioID.value}-${pathBytes.toBase64()}-$hashSize"

    fun roomMessageKey(sessionID: Uuid, deduplicationKey: String): String = "${sessionID.value}-$deduplicationKey"

    /** Dedup key for a reaction: `(messageID, senderName, emoji)`. Mirrors Swift `reactionKey`. */
    fun reactionKey(messageID: Uuid, senderName: String, emoji: String): String =
        "${messageID.value}-$senderName-$emoji"

    fun blockedChannelSenderKey(radioID: Uuid, name: String): String = "${radioID.value}-$name"

    /** Dedup key for a discovered node: `(radioID, publicKey)`. Mirrors Swift `discoveredNodeKey`. */
    fun discoveredNodeKey(radioID: Uuid, publicKey: ByteArray): String = "${radioID.value}-${publicKey.toBase64()}"
}

/** Result of a pure merge: the (possibly updated) local row and whether anything changed. */
data class MergeResult<T>(val value: T, val changed: Boolean)

private data class FloodWire(val mode: String?, val region: String?)

private fun maxHeard(local: Long?, foreignClamped: Long): Long? {
    val stamp = maxOf(local ?: 0L, foreignClamped)
    return if (stamp == 0L && local == null) null else stamp
}

private fun <T> adoptIfLocalNull(local: T?, foreign: T?): T? = if (local == null && foreign != null) foreign else local

private fun adoptTrueFlag(local: Boolean, foreign: Boolean): Boolean = local || foreign

private fun adoptLaterDate(local: Double?, foreign: Double?): Double? = when {
    foreign == null -> local
    local == null || local < foreign -> foreign
    else -> local
}

private fun adoptNotificationIfDefault(local: NotificationLevel, foreign: NotificationLevel): NotificationLevel =
    if (local == NotificationLevel.ALL && foreign != NotificationLevel.ALL) foreign else local

private fun adoptFloodScopeIfInherit(local: ChannelDto, foreign: ChannelDto): FloodWire {
    if (local.floodScope != ChannelFloodScope.Inherit || foreign.floodScope == ChannelFloodScope.Inherit) {
        return FloodWire(local.floodScopeModeRawValue, local.regionScope)
    }
    val (mode, region) = ChannelFloodScopeStorage.decompose(foreign.floodScope)
    return FloodWire(mode, region)
}

@OptIn(ExperimentalEncodingApi::class)
private fun ByteArray.toBase64(): String = Base64.encode(this)
