package one.meshcore.shared.notification

import one.meshcore.shared.chat.ActiveChannel
import one.meshcore.shared.chat.reactions.ReactionNotice
import one.meshcore.shared.chat.reactions.ReactionNotifier
import one.meshcore.shared.contact.ContactDiscoveryNotifier
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid

/** Battery thresholds, reaction banners, and new-contact discovery banners. */
internal class NotificationSignals(private val runtime: NotificationRuntime) :
    BatteryNotifier,
    ReactionNotifier,
    ContactDiscoveryNotifier {
    override suspend fun initializeBatteryThreshold(deviceName: String, percentage: Int?, isBatteryPresent: Boolean) {
        val shouldNotify = runtime.batteryMonitor.initialize(percentage, isBatteryPresent)
        if (shouldNotify && percentage != null) postLowBatteryIfEnabled(deviceName, percentage)
    }

    override suspend fun checkBatteryThreshold(deviceName: String, percentage: Int, isBatteryPresent: Boolean) {
        if (runtime.batteryMonitor.check(percentage, isBatteryPresent)) {
            postLowBatteryIfEnabled(deviceName, percentage)
        }
    }

    override suspend fun checkMissedBatteryThreshold(deviceName: String, percentage: Int, isBatteryPresent: Boolean) {
        if (runtime.batteryMonitor.checkMissed(percentage, isBatteryPresent)) {
            postLowBatteryIfEnabled(deviceName, percentage)
        }
    }

    override fun clearBatteryThresholds() = runtime.batteryMonitor.clearThresholds()

    private suspend fun postLowBatteryIfEnabled(deviceName: String, percentage: Int) {
        if (!runtime.isAuthorized()) return
        if (!runtime.preferences.snapshot().lowBatteryEnabled) return
        runtime.poster.postLowBattery(
            LowBatteryNotification(
                deviceName,
                percentage,
                playSound = runtime.preferences.snapshot().soundEnabled,
            ),
        )
    }

    override suspend fun notifyReaction(notice: ReactionNotice) {
        if (!runtime.isAuthorized() || runtime.isSuppressingNotifications) return
        if (!runtime.preferences.snapshot().reactionsEnabled) return
        val onOpenThread = runtime.isForeground &&
            when {
                notice.contactID != null -> runtime.activeConversation.activeContactID() == notice.contactID
                notice.channelIndex != null ->
                    runtime.activeConversation.activeChannel() == ActiveChannel(notice.radioID, notice.channelIndex)
                else -> false
            }
        if (!NotificationGate.shouldPostReaction(onOpenThread)) return
        runtime.poster.postReaction(
            ReactionNotification(
                reactorName = notice.reactorName,
                emoji = notice.emoji,
                messagePreview = notice.messagePreview,
                messageID = notice.messageID,
                contactID = notice.contactID,
                channelIndex = notice.channelIndex,
                radioID = notice.radioID,
                playSound = runtime.preferences.snapshot().soundEnabled,
            ),
        )
    }

    override suspend fun notifyNewContact(contactID: Uuid, contactName: String?, contactType: ContactType) {
        if (!runtime.isAuthorized()) return
        val prefs = runtime.preferences.snapshot()
        if (!prefs.newContactDiscoveredEnabled) return
        val typeEnabled = when (contactType) {
            ContactType.CHAT -> prefs.discoveryContactEnabled
            ContactType.REPEATER -> prefs.discoveryRepeaterEnabled
            ContactType.ROOM -> prefs.discoveryRoomEnabled
        }
        if (!typeEnabled) return
        runtime.poster.postNewContact(
            NewContactNotification(contactID, contactName, contactType, playSound = prefs.soundEnabled),
        )
    }
}
