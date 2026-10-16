package one.meshcore.shared.notification

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.chat.ActiveChannel
import one.meshcore.shared.chat.ChannelMessageNotice
import one.meshcore.shared.chat.IngestNotifier
import one.meshcore.shared.chat.ResolvedContact
import one.meshcore.shared.chat.RoomMessageNotice
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid

/** Message posting, unresolved-channel diagnostics, and the debounced badge. */
internal class NotificationIngest(private val runtime: NotificationRuntime) : IngestNotifier {
    override fun setSyncNotificationSuppression(suppress: Boolean) {
        runtime.isSuppressingNotifications = suppress
        if (suppress) {
            startSuppressionWatchdog()
        } else {
            cancelSuppressionWatchdog()
        }
    }

    private fun startSuppressionWatchdog() {
        cancelSuppressionWatchdog()
        runtime.suppressionWatchdogJob = runtime.scope.launch {
            SyncNotificationSuppressionWatchdog.awaitThenForceClear(
                stillSuppressing = { runtime.isSuppressingNotifications },
                clear = { runtime.isSuppressingNotifications = false },
                log = { line ->
                    runtime.diagnostic(line)
                    runtime.logger.warning(line)
                },
                timeout = runtime.suppressionWatchdogTimeout,
            )
        }
    }

    private fun cancelSuppressionWatchdog() {
        runtime.suppressionWatchdogJob?.cancel()
        runtime.suppressionWatchdogJob = null
    }

    override suspend fun postDirectMessage(
        contact: ResolvedContact,
        text: String,
        messageID: Uuid,
        hasSelfMention: Boolean,
    ) {
        if (!runtime.isAuthorized() || runtime.isSuppressingNotifications) return
        val prefs = runtime.preferences.snapshot()
        if (!prefs.contactMessagesEnabled) return
        val active = runtime.isForeground && runtime.activeConversation.activeContactID() == contact.id
        if (!NotificationGate.shouldPostDirect(contact.isMuted, active)) return
        runtime.poster.postDirect(
            DirectMessageNotification(
                contactID = contact.id,
                messageID = messageID,
                title = contact.displayName,
                body = text,
                playSound = prefs.soundEnabled,
                channelKey = NotificationChannelKey.MESSAGES,
            ),
        )
    }

    override suspend fun postChannelMessage(notice: ChannelMessageNotice) {
        if (!runtime.isAuthorized() || runtime.isSuppressingNotifications) return
        val prefs = runtime.preferences.snapshot()
        if (!prefs.channelMessagesEnabled) return
        val channel = notice.channel
        val onScreen = ActiveChannel(channel.radioID, channel.index)
        val active = runtime.isForeground && runtime.activeConversation.activeChannel() == onScreen
        if (!NotificationGate.shouldPostChannel(
                channel.notificationLevel,
                notice.hasSelfMention,
                active,
                channelResolved = true,
            )
        ) {
            return
        }
        val senderNodeName = notice.senderNodeName
        runtime.poster.postChannel(
            ChannelMessageNotification(
                radioID = notice.radioID,
                channelIndex = notice.channelIndex,
                messageID = notice.messageID,
                title = channel.name,
                body = if (senderNodeName != null) "$senderNodeName: ${notice.text}" else notice.text,
                playSound = prefs.soundEnabled,
                channelKey = channelKey(channel.notificationLevel),
            ),
        )
    }

    override suspend fun postRoomMessage(notice: RoomMessageNotice) {
        if (notice.notificationLevel == NotificationLevel.MUTED) return
        if (!runtime.isAuthorized() || runtime.isSuppressingNotifications) return
        if (runtime.isForeground && runtime.activeConversation.activeRoomSessionID() == notice.sessionID) return
        val prefs = runtime.preferences.snapshot()
        if (!prefs.roomMessagesEnabled) return
        runtime.poster.postRoom(
            RoomMessageNotification(
                roomName = notice.roomName,
                sessionID = notice.sessionID,
                senderName = notice.senderName,
                messageText = notice.text,
                messageID = notice.messageID,
                playSound = prefs.soundEnabled,
            ),
        )
    }

    override suspend fun recordUnresolvedChannel(channelIndex: Int, radioID: Uuid, senderTimestamp: Long) {
        runtime.logger.warning(suppressingUnresolvedChannelLine(channelIndex, radioID, senderTimestamp))
        val summary = runtime.unresolvedMutex.withLock {
            runtime.unresolvedChannelIndices += channelIndex
            val last = runtime.unresolvedLastEmit[channelIndex]
            val current = runtime.now()
            if (last == null || current - last >= UNRESOLVED_SUMMARY_INTERVAL_SECONDS) {
                runtime.unresolvedLastEmit[channelIndex] = current
                unresolvedChannelSummaryLine(runtime.unresolvedChannelIndices)
            } else {
                null
            }
        }
        if (summary != null) {
            runtime.diagnostic(
                "Channel message on unconfigured slot $channelIndex (radio ${radioID.value}) suppressed",
            )
            runtime.logger.warning(summary)
        }
    }

    override suspend fun updateBadge() {
        runtime.badgeJob?.cancel()
        runtime.badgeJob = runtime.scope.launch {
            delay(BADGE_DEBOUNCE_MS)
            runtime.performBadgeUpdate()
        }
    }

    override suspend fun removeDeliveredForContact(contactID: Uuid) {
        runtime.poster.removeDeliveredForContact(contactID)
    }

    override suspend fun notifyConversationsChanged() = runtime.onConversationsChanged()

    private companion object {
        const val BADGE_DEBOUNCE_MS = 100L
        const val UNRESOLVED_SUMMARY_INTERVAL_SECONDS = 60.0
    }
}

private fun channelKey(level: NotificationLevel): NotificationChannelKey = when (level) {
    NotificationLevel.MENTIONS_ONLY -> NotificationChannelKey.MENTIONS
    else -> NotificationChannelKey.MESSAGES
}

private suspend fun NotificationRuntime.performBadgeUpdate() {
    val prefs = preferences.snapshot()
    val counts = if (prefs.badgeEnabled) unreadCounts() else UnreadCounts.Zero
    val total = BadgeCalculator.compute(counts, prefs)
    badgeCountFlow.value = total
    poster.setBadge(total)
}
