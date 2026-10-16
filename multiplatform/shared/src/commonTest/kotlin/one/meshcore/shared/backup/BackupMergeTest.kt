package one.meshcore.shared.backup

import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupMergeTest {
    private val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val contactId = Uuid.normalized("C0000000-0000-0000-0000-000000000001")
    private val publicKey = ByteArray(32) { 0x11 }

    private data class ContactOpts(
        val nickname: String? = null,
        val isBlocked: Boolean = false,
        val isMuted: Boolean = false,
        val isFavorite: Boolean = false,
        val unreadCount: Int = 0,
        val unreadMentionCount: Int = 0,
        val lastMessageDate: Double? = null,
        val avatarImageData: ByteArray? = null,
    )

    private data class SessionOpts(
        val unreadCount: Int = 0,
        val lastSyncTimestamp: Long = 0,
        val notificationLevel: NotificationLevel = NotificationLevel.ALL,
        val isPinned: Boolean = false,
    )

    @Test
    fun contactKeysAreStableAcrossBase64() {
        val key = BackupMergeKeys.contactKey(radioId, publicKey)
        assertTrue(key.startsWith("${radioId.value}-"))
        assertEquals(key, BackupMergeKeys.contactKey(radioId, publicKey.copyOf()))
    }

    @Test
    fun rewriteDmDedupKeyOnlyTouchesPrefix() {
        val from = Uuid.normalized("11111111-1111-1111-1111-111111111111")
        val to = Uuid.normalized("22222222-2222-2222-2222-222222222222")
        val key = "dm-${from.value}-1700000000-AABBCCDD"
        assertEquals(
            "dm-${to.value}-1700000000-AABBCCDD",
            BackupMerge.rewriteDMDeduplicationKey(key, from, to),
        )
        assertEquals(key, BackupMerge.rewriteDMDeduplicationKey(key, to, from))
    }

    @Test
    fun rewriteChannelDedupKeyOnlyTouchesIndexSegment() {
        val key = "ch-3-1700000000-Alice-AABBCCDD"
        assertEquals(
            "ch-7-1700000000-Alice-AABBCCDD",
            BackupMerge.rewriteChannelDeduplicationKey(key, fromBackupIndex = 3, toLocalIndex = 7),
        )
        assertEquals(key, BackupMerge.rewriteChannelDeduplicationKey(key, fromBackupIndex = 9, toLocalIndex = 1))
    }

    @Test
    fun contactMergeAdoptsNicknameOnlyWhenLocalNull() {
        val local = sampleContact(ContactOpts(nickname = "Local"))
        val foreign = sampleContact(ContactOpts(nickname = "Foreign"))
        val result = BackupMerge.mergeContactMetadata(local, foreign)
        assertFalse(result.changed)
        assertEquals("Local", result.value.nickname)

        val emptyLocal = sampleContact(ContactOpts(nickname = null))
        val adopted = BackupMerge.mergeContactMetadata(emptyLocal, foreign)
        assertTrue(adopted.changed)
        assertEquals("Foreign", adopted.value.nickname)
    }

    @Test
    fun contactMergeAdoptsAvatarOnlyWhenLocalNull() {
        val foreignAvatar = ByteArray(4) { 0x42 }
        val local = sampleContact(ContactOpts(avatarImageData = ByteArray(4) { 0x01 }))
        val foreign = sampleContact(ContactOpts(avatarImageData = foreignAvatar))
        val kept = BackupMerge.mergeContactMetadata(local, foreign)
        assertFalse(kept.changed)
        assertTrue(kept.value.avatarImageData.contentEquals(ByteArray(4) { 0x01 }))

        val emptyLocal = sampleContact(ContactOpts(avatarImageData = null))
        val adopted = BackupMerge.mergeContactMetadata(emptyLocal, foreign)
        assertTrue(adopted.changed)
        assertTrue(adopted.value.avatarImageData.contentEquals(foreignAvatar))

        val noForeign = sampleContact(ContactOpts(avatarImageData = null))
        val unchanged = BackupMerge.mergeContactMetadata(noForeign, sampleContact())
        assertFalse(unchanged.changed)
        assertEquals(null, unchanged.value.avatarImageData)
    }

    @Test
    fun contactMergeNeverUnblocksOrUnfavorites() {
        val local = sampleContact(ContactOpts(isBlocked = true, isFavorite = true, isMuted = true))
        val foreign = sampleContact(ContactOpts(isBlocked = false, isFavorite = false, isMuted = false))
        val result = BackupMerge.mergeContactMetadata(local, foreign)
        assertFalse(result.changed)
        assertTrue(result.value.isBlocked)
        assertTrue(result.value.isFavorite)
        assertTrue(result.value.isMuted)
    }

    @Test
    fun contactMergeTakesMaxUnreadAndNewerLastMessage() {
        val local = sampleContact(ContactOpts(unreadCount = 2, unreadMentionCount = 1, lastMessageDate = 100.0))
        val foreign = sampleContact(ContactOpts(unreadCount = 5, unreadMentionCount = 0, lastMessageDate = 200.0))
        val result = BackupMerge.mergeContactMetadata(local, foreign)
        assertTrue(result.changed)
        assertEquals(5, result.value.unreadCount)
        assertEquals(1, result.value.unreadMentionCount)
        assertEquals(200.0, result.value.lastMessageDate)
    }

    @Test
    fun contactMergeTakesMaxLastHeardAfterFutureClockClamp() {
        val now = 1_700_000_000L
        val local = sampleContact().copy(lastHeardTimestamp = 100)
        val foreign = sampleContact().copy(lastHeardTimestamp = 500)
        val result = BackupMerge.mergeContactMetadata(local, foreign, nowEpochSeconds = now)
        assertTrue(result.changed)
        assertEquals(500, result.value.lastHeardTimestamp)

        val future = sampleContact().copy(lastHeardTimestamp = now + 10_000)
        val clamped = BackupMerge.mergeContactMetadata(local, future, nowEpochSeconds = now)
        assertEquals(now + BackupMerge.TIMESTAMP_TOLERANCE_FUTURE_SECONDS, clamped.value.lastHeardTimestamp)

        val kept = BackupMerge.mergeContactMetadata(
            local.copy(lastHeardTimestamp = 900),
            sampleContact().copy(lastHeardTimestamp = 500),
            nowEpochSeconds = now,
        )
        assertFalse(kept.changed)
        assertEquals(900, kept.value.lastHeardTimestamp)
    }

    @Test
    fun channelMergeAdoptsNotificationLevelOnlyFromDefault() {
        val localAll = sampleChannel(notificationLevel = NotificationLevel.ALL)
        val foreignMuted = sampleChannel(notificationLevel = NotificationLevel.MUTED)
        val adopted = BackupMerge.mergeChannelMetadata(localAll, foreignMuted)
        assertTrue(adopted.changed)
        assertEquals(NotificationLevel.MUTED, adopted.value.notificationLevel)

        val localMentions = sampleChannel(notificationLevel = NotificationLevel.MENTIONS_ONLY)
        val notAdopted = BackupMerge.mergeChannelMetadata(localMentions, foreignMuted)
        assertFalse(notAdopted.changed)
        assertEquals(NotificationLevel.MENTIONS_ONLY, notAdopted.value.notificationLevel)
    }

    @Test
    fun channelMergeAdoptsFloodScopeOnlyFromInherit() {
        val localInherit = sampleChannel(floodScope = ChannelFloodScope.Inherit)
        val foreignRegion = sampleChannel(floodScope = ChannelFloodScope.Region("US"))
        val adopted = BackupMerge.mergeChannelMetadata(localInherit, foreignRegion)
        assertTrue(adopted.changed)
        assertEquals(ChannelFloodScope.Region("US"), adopted.value.floodScope)

        val localSpecific = sampleChannel(floodScope = ChannelFloodScope.Region("EU"))
        val kept = BackupMerge.mergeChannelMetadata(localSpecific, foreignRegion)
        assertFalse(kept.changed)
        assertEquals(ChannelFloodScope.Region("EU"), kept.value.floodScope)
    }

    @Test
    fun remoteSessionMergeTakesMaxSyncAndUnread() {
        val local =
            sampleSession(
                SessionOpts(
                    unreadCount = 1,
                    lastSyncTimestamp = 10,
                    notificationLevel = NotificationLevel.ALL,
                    isPinned = false,
                ),
            )
        val foreign =
            sampleSession(
                SessionOpts(
                    unreadCount = 4,
                    lastSyncTimestamp = 99,
                    notificationLevel = NotificationLevel.MENTIONS_ONLY,
                    isPinned = true,
                ),
            )
        val result = BackupMerge.mergeRemoteNodeSessionMetadata(local, foreign)
        assertTrue(result.changed)
        assertEquals(4, result.value.unreadCount)
        assertEquals(99, result.value.lastSyncTimestamp)
        assertEquals(NotificationLevel.MENTIONS_ONLY, result.value.notificationLevel)
        assertTrue(result.value.isPinned)
    }

    private fun sampleContact(opts: ContactOpts = ContactOpts()): ContactDto = ContactDto(
        id = contactId,
        radioID = radioId,
        publicKey = publicKey,
        name = "Alice",
        typeRawValue = 1,
        flags = 0,
        outPathLength = 255,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = opts.isBlocked,
        isMuted = opts.isMuted,
        isFavorite = opts.isFavorite,
        unreadCount = opts.unreadCount,
        unreadMentionCount = opts.unreadMentionCount,
        nickname = opts.nickname,
        lastMessageDate = opts.lastMessageDate,
        avatarImageData = opts.avatarImageData,
    )

    private fun sampleChannel(
        notificationLevel: NotificationLevel = NotificationLevel.ALL,
        floodScope: ChannelFloodScope = ChannelFloodScope.Inherit,
        unreadCount: Int = 0,
        isPinned: Boolean = false,
    ): ChannelDto {
        val (mode, region) = ChannelFloodScopeStorage.decompose(floodScope)
        return ChannelDto(
            id = Uuid.normalized("C0000000-0000-0000-0000-0000000000AA"),
            radioID = radioId,
            index = 1,
            name = "#ops",
            secret = ByteArray(16) { 0x22 },
            isEnabled = true,
            unreadCount = unreadCount,
            notificationLevel = notificationLevel,
            isPinned = isPinned,
            floodScopeModeRawValue = mode,
            regionScope = region,
        )
    }

    private fun sampleSession(opts: SessionOpts): RemoteNodeSessionDto = RemoteNodeSessionDto(
        id = Uuid.normalized("S0000000-0000-0000-0000-000000000001"),
        radioID = radioId,
        publicKey = publicKey,
        name = "Repeater",
        role = RemoteNodeRole.REPEATER,
        latitude = 0.0,
        longitude = 0.0,
        isConnected = false,
        permissionLevel = RoomPermissionLevel.GUEST,
        unreadCount = opts.unreadCount,
        notificationLevel = opts.notificationLevel,
        isPinned = opts.isPinned,
        neighborCount = 0,
        lastSyncTimestamp = opts.lastSyncTimestamp,
    )
}
