package one.meshcore.shared.notification

import one.meshcore.shared.prefs.PreferencesStore

/**
 * Notification preference toggles mirror the message, sound, and badge subset of Swift
 * `NotificationPreferences`. Device-local settings (never backed up), so the keys need not match iOS.
 */
data class NotificationPreferencesSnapshot(
    val contactMessagesEnabled: Boolean,
    val channelMessagesEnabled: Boolean,
    val roomMessagesEnabled: Boolean,
    val reactionsEnabled: Boolean,
    val lowBatteryEnabled: Boolean,
    val newContactDiscoveredEnabled: Boolean,
    val discoveryContactEnabled: Boolean,
    val discoveryRepeaterEnabled: Boolean,
    val discoveryRoomEnabled: Boolean,
    val soundEnabled: Boolean,
    val badgeEnabled: Boolean,
) {
    companion object {
        /** Every toggle on, matching Swift `AppStorageKey.defaultNotificationEnabled = true`. */
        val AllEnabled = NotificationPreferencesSnapshot(
            contactMessagesEnabled = true,
            channelMessagesEnabled = true,
            roomMessagesEnabled = true,
            reactionsEnabled = true,
            lowBatteryEnabled = true,
            newContactDiscoveredEnabled = true,
            discoveryContactEnabled = true,
            discoveryRepeaterEnabled = true,
            discoveryRoomEnabled = true,
            soundEnabled = true,
            badgeEnabled = true,
        )
    }
}

/** The [PreferencesStore] keys the notification toggles persist under. */
object NotificationPreferenceKeys {
    const val CONTACT_MESSAGES = "notify_contact_messages"
    const val CHANNEL_MESSAGES = "notify_channel_messages"
    const val ROOM_MESSAGES = "notify_room_messages"
    const val REACTIONS = "notify_reactions"
    const val LOW_BATTERY = "notify_low_battery"
    const val NEW_CONTACT_DISCOVERED = "notify_new_contact_discovered"
    const val DISCOVERY_CONTACT = "notify_discovery_contact"
    const val DISCOVERY_REPEATER = "notify_discovery_repeater"
    const val DISCOVERY_ROOM = "notify_discovery_room"
    const val SOUND = "notification_sound_enabled"
    const val BADGE = "notification_badge_enabled"
}

/** Supplies the current preference snapshot; the coordinator reads it per message and per badge update. */
interface NotificationPreferencesProvider {
    suspend fun snapshot(): NotificationPreferencesSnapshot

    /** A fixed all-enabled provider for unit tests and the pre-settings default. */
    object AllEnabled : NotificationPreferencesProvider {
        override suspend fun snapshot() = NotificationPreferencesSnapshot.AllEnabled
    }
}

/**
 * A [PreferencesStore]-backed provider with a short read cache (Swift's 5s `preferences cache TTL`) so a
 * message burst does not hammer the store. An absent key reads as enabled, matching the iOS default. The
 * cache is time-gated through the injected [now] (epoch seconds), the codebase's clock-injection idiom.
 * Discovery toggles delegate to [DiscoveryNotificationPreferenceWrites].
 */
class StoredNotificationPreferences private constructor(private val parts: NotificationPreferenceParts) :
    NotificationPreferencesProvider by parts.cache,
    DiscoveryNotificationPreferences by parts.discovery {
    constructor(store: PreferencesStore, now: () -> Double) : this(NotificationPreferenceParts(store, now))

    private val cache = parts.cache

    /** Writes one toggle and invalidates the read cache so the next [snapshot] reloads. */
    suspend fun setContactMessagesEnabled(value: Boolean) =
        cache.write(NotificationPreferenceKeys.CONTACT_MESSAGES, value)

    suspend fun setChannelMessagesEnabled(value: Boolean) =
        cache.write(NotificationPreferenceKeys.CHANNEL_MESSAGES, value)

    suspend fun setRoomMessagesEnabled(value: Boolean) = cache.write(NotificationPreferenceKeys.ROOM_MESSAGES, value)

    suspend fun setReactionsEnabled(value: Boolean) = cache.write(NotificationPreferenceKeys.REACTIONS, value)

    suspend fun setLowBatteryEnabled(value: Boolean) = cache.write(NotificationPreferenceKeys.LOW_BATTERY, value)

    suspend fun setSoundEnabled(value: Boolean) = cache.write(NotificationPreferenceKeys.SOUND, value)

    suspend fun setBadgeEnabled(value: Boolean) = cache.write(NotificationPreferenceKeys.BADGE, value)
}
