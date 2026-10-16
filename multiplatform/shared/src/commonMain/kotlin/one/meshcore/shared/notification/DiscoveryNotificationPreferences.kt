package one.meshcore.shared.notification

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.prefs.PreferencesStore

/** Per-type discovery toggles on [StoredNotificationPreferences]. */
interface DiscoveryNotificationPreferences {
    suspend fun setNewContactDiscoveredEnabled(value: Boolean)

    suspend fun setDiscoveryContactEnabled(value: Boolean)

    suspend fun setDiscoveryRepeaterEnabled(value: Boolean)

    suspend fun setDiscoveryRoomEnabled(value: Boolean)
}

internal class NotificationPreferenceParts(store: PreferencesStore, now: () -> Double) {
    val cache = NotificationPreferenceCache(store, now)
    val discovery = DiscoveryNotificationPreferenceWrites(cache)
}

internal class NotificationPreferenceCache(private val store: PreferencesStore, private val now: () -> Double) :
    NotificationPreferencesProvider {
    private val mutex = Mutex()
    private var cached: NotificationPreferencesSnapshot? = null
    private var refreshedAt = Double.NEGATIVE_INFINITY

    override suspend fun snapshot(): NotificationPreferencesSnapshot = mutex.withLock {
        val current = now()
        cached?.let { if (current - refreshedAt <= CACHE_TTL_SECONDS) return it }
        val fresh = loadFresh()
        cached = fresh
        refreshedAt = current
        fresh
    }

    suspend fun write(key: String, value: Boolean) {
        mutex.withLock {
            try {
                store.putBoolean(key, value)
            } finally {
                invalidate()
            }
        }
    }

    suspend fun writeDiscoveryParent(value: Boolean) = mutex.withLock {
        try {
            val wasEnabled = enabled(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED)
            store.putBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED, value)
            if (value && !wasEnabled && !hasExistingDiscoveryChoices()) {
                store.putBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT, true)
                store.putBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER, true)
                store.putBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM, true)
            }
        } finally {
            invalidate()
        }
    }

    private suspend fun loadFresh(): NotificationPreferencesSnapshot = NotificationPreferencesSnapshot(
        contactMessagesEnabled = enabled(NotificationPreferenceKeys.CONTACT_MESSAGES),
        channelMessagesEnabled = enabled(NotificationPreferenceKeys.CHANNEL_MESSAGES),
        roomMessagesEnabled = enabled(NotificationPreferenceKeys.ROOM_MESSAGES),
        reactionsEnabled = enabled(NotificationPreferenceKeys.REACTIONS),
        lowBatteryEnabled = enabled(NotificationPreferenceKeys.LOW_BATTERY),
        newContactDiscoveredEnabled = enabled(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED),
        discoveryContactEnabled = enabled(NotificationPreferenceKeys.DISCOVERY_CONTACT),
        discoveryRepeaterEnabled = enabled(NotificationPreferenceKeys.DISCOVERY_REPEATER),
        discoveryRoomEnabled = enabled(NotificationPreferenceKeys.DISCOVERY_ROOM),
        soundEnabled = enabled(NotificationPreferenceKeys.SOUND),
        badgeEnabled = enabled(NotificationPreferenceKeys.BADGE),
    )

    private suspend fun enabled(key: String): Boolean = store.getBoolean(key) ?: DEFAULT_ENABLED

    private suspend fun hasExistingDiscoveryChoices(): Boolean =
        store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT) != null ||
            store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER) != null ||
            store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM) != null

    private fun invalidate() {
        cached = null
        refreshedAt = Double.NEGATIVE_INFINITY
    }

    private companion object {
        const val CACHE_TTL_SECONDS = 5.0
        const val DEFAULT_ENABLED = true
    }
}

internal class DiscoveryNotificationPreferenceWrites(private val cache: NotificationPreferenceCache) :
    DiscoveryNotificationPreferences {
    // Parent and first-activation child defaults form one cache transaction, so readers cannot
    // observe the parent flip between its child writes.
    override suspend fun setNewContactDiscoveredEnabled(value: Boolean) = cache.writeDiscoveryParent(value)

    override suspend fun setDiscoveryContactEnabled(value: Boolean) =
        cache.write(NotificationPreferenceKeys.DISCOVERY_CONTACT, value)

    override suspend fun setDiscoveryRepeaterEnabled(value: Boolean) =
        cache.write(NotificationPreferenceKeys.DISCOVERY_REPEATER, value)

    override suspend fun setDiscoveryRoomEnabled(value: Boolean) =
        cache.write(NotificationPreferenceKeys.DISCOVERY_ROOM, value)
}
