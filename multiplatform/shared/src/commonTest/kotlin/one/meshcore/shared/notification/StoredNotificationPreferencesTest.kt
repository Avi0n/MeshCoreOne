package one.meshcore.shared.notification

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoredNotificationPreferencesTest {
    @Test
    fun defaultsEnabledWhenAbsent() = runTest {
        val prefs = StoredNotificationPreferences(InMemoryPreferencesStore()) { 0.0 }
        val snap = prefs.snapshot()
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.channelMessagesEnabled)
        assertTrue(snap.roomMessagesEnabled)
    }

    @Test
    fun firstParentActivationAutoEnablesChildren() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }
        // Parent off, no child ever written.
        prefs.setNewContactDiscoveredEnabled(false)
        prefs.setNewContactDiscoveredEnabled(true)
        val snap = prefs.snapshot()
        assertTrue(snap.discoveryContactEnabled)
        assertTrue(snap.discoveryRepeaterEnabled)
        assertTrue(snap.discoveryRoomEnabled)
    }

    @Test
    fun parentActivationLeavesExistingChildChoicesUntouched() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }
        prefs.setDiscoveryContactEnabled(false)
        prefs.setNewContactDiscoveredEnabled(false)
        prefs.setNewContactDiscoveredEnabled(true)
        // A child the user already chose stays as chosen; the parent flip must not overwrite it.
        assertFalse(prefs.snapshot().discoveryContactEnabled)
    }

    @Test
    fun freshInstallDefaultParentDoesNotAutoWriteChildren() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }
        // Parent defaults on (never written); setting it on again is not an off -> on activation.
        prefs.setNewContactDiscoveredEnabled(true)
        assertTrue(store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT) == null)
    }

    @Test
    fun writeInvalidatesCache() = runTest {
        var clock = 0.0
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { clock }
        assertTrue(prefs.snapshot().contactMessagesEnabled)
        prefs.setContactMessagesEnabled(false)
        clock += 0.1
        assertFalse(prefs.snapshot().contactMessagesEnabled)
        prefs.setChannelMessagesEnabled(false)
        clock += 0.1
        assertFalse(prefs.snapshot().channelMessagesEnabled)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun snapshotWaitsForAnInFlightWrite() = runTest {
        val store = GatedNotificationWriteStore(NotificationPreferenceKeys.CONTACT_MESSAGES)
        val prefs = StoredNotificationPreferences(store) { 0.0 }
        assertTrue(prefs.snapshot().contactMessagesEnabled)

        val write = async { prefs.setContactMessagesEnabled(false) }
        store.writeStarted.await()
        val read = async { prefs.snapshot() }
        runCurrent()
        assertFalse(read.isCompleted)

        store.releaseWrite.complete(Unit)
        write.await()
        assertFalse(read.await().contactMessagesEnabled)
    }

    @Test
    fun keepingNotificationSoundOnDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }
        val before = prefs.snapshot()
        assertTrue(before.soundEnabled)
        assertTrue(before.badgeEnabled)
        assertTrue(before.contactMessagesEnabled)
        assertTrue(before.reactionsEnabled)
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertTrue(store.snapshot().isEmpty())

        prefs.setSoundEnabled(true)

        val snap = prefs.snapshot()
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.lowBatteryEnabled)
        assertEquals(true, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(
            mapOf(NotificationPreferenceKeys.SOUND to true),
            store.snapshot(),
        )
    }

    @Test
    fun turningOffNotificationSoundDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }

        prefs.setSoundEnabled(false)

        val snap = prefs.snapshot()
        assertFalse(snap.soundEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.channelMessagesEnabled)
        assertTrue(snap.roomMessagesEnabled)
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.lowBatteryEnabled)
        assertTrue(snap.newContactDiscoveredEnabled)
        assertTrue(snap.discoveryContactEnabled)
        assertTrue(snap.discoveryRepeaterEnabled)
        assertTrue(snap.discoveryRoomEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(false, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.LOW_BATTERY))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
    }

    @Test
    fun keepingContactMessageAlertsOnDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }
        val before = prefs.snapshot()
        assertTrue(before.contactMessagesEnabled)
        assertTrue(before.channelMessagesEnabled)
        assertTrue(before.roomMessagesEnabled)
        assertTrue(before.reactionsEnabled)
        assertTrue(before.lowBatteryEnabled)
        assertTrue(before.soundEnabled)
        assertTrue(before.badgeEnabled)
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertTrue(store.snapshot().isEmpty())

        prefs.setContactMessagesEnabled(true)

        val snap = prefs.snapshot()
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.channelMessagesEnabled)
        assertTrue(snap.roomMessagesEnabled)
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.lowBatteryEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(true, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.LOW_BATTERY))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
        assertEquals(
            mapOf(NotificationPreferenceKeys.CONTACT_MESSAGES to true),
            store.snapshot(),
        )
    }

    @Test
    fun turningOffContactMessageNotificationsDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }

        prefs.setContactMessagesEnabled(false)

        val snap = prefs.snapshot()
        assertFalse(snap.contactMessagesEnabled)
        assertTrue(snap.channelMessagesEnabled)
        assertTrue(snap.roomMessagesEnabled)
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.lowBatteryEnabled)
        assertTrue(snap.newContactDiscoveredEnabled)
        assertTrue(snap.discoveryContactEnabled)
        assertTrue(snap.discoveryRepeaterEnabled)
        assertTrue(snap.discoveryRoomEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(false, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.LOW_BATTERY))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
    }

    @Test
    fun keepingChannelMessageAlertsOnDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }
        val before = prefs.snapshot()
        assertTrue(before.channelMessagesEnabled)
        assertTrue(before.contactMessagesEnabled)
        assertTrue(before.roomMessagesEnabled)
        assertTrue(before.reactionsEnabled)
        assertTrue(before.soundEnabled)
        assertTrue(before.badgeEnabled)
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertTrue(store.snapshot().isEmpty())

        prefs.setChannelMessagesEnabled(true)

        val snap = prefs.snapshot()
        assertTrue(snap.channelMessagesEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.roomMessagesEnabled)
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.lowBatteryEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(true, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
        assertEquals(
            mapOf(NotificationPreferenceKeys.CHANNEL_MESSAGES to true),
            store.snapshot(),
        )
    }

    @Test
    fun turningOffChannelMessageNotificationsDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }

        prefs.setChannelMessagesEnabled(false)

        val snap = prefs.snapshot()
        assertFalse(snap.channelMessagesEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.roomMessagesEnabled)
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.lowBatteryEnabled)
        assertTrue(snap.newContactDiscoveredEnabled)
        assertTrue(snap.discoveryContactEnabled)
        assertTrue(snap.discoveryRepeaterEnabled)
        assertTrue(snap.discoveryRoomEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(false, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.LOW_BATTERY))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
    }

    @Test
    fun keepingRoomMessageAlertsOnDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }
        val before = prefs.snapshot()
        assertTrue(before.roomMessagesEnabled)
        assertTrue(before.channelMessagesEnabled)
        assertTrue(before.contactMessagesEnabled)
        assertTrue(before.reactionsEnabled)
        assertTrue(before.soundEnabled)
        assertTrue(before.badgeEnabled)
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertTrue(store.snapshot().isEmpty())

        prefs.setRoomMessagesEnabled(true)

        val snap = prefs.snapshot()
        assertTrue(snap.roomMessagesEnabled)
        assertTrue(snap.channelMessagesEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.lowBatteryEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(true, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
        assertEquals(
            mapOf(NotificationPreferenceKeys.ROOM_MESSAGES to true),
            store.snapshot(),
        )
    }

    @Test
    fun turningOffRoomMessageNotificationsDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }

        prefs.setRoomMessagesEnabled(false)

        val snap = prefs.snapshot()
        assertFalse(snap.roomMessagesEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.channelMessagesEnabled)
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.lowBatteryEnabled)
        assertTrue(snap.newContactDiscoveredEnabled)
        assertTrue(snap.discoveryContactEnabled)
        assertTrue(snap.discoveryRepeaterEnabled)
        assertTrue(snap.discoveryRoomEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(false, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.LOW_BATTERY))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
    }
}

private class GatedNotificationWriteStore(
    private val gatedKey: String,
    private val backing: InMemoryPreferencesStore = InMemoryPreferencesStore(),
) : PreferencesStore by backing {
    val writeStarted = CompletableDeferred<Unit>()
    val releaseWrite = CompletableDeferred<Unit>()

    override suspend fun getBoolean(key: String): Boolean? = backing.getBoolean(key)

    override suspend fun putBoolean(key: String, value: Boolean) {
        if (key == gatedKey) {
            writeStarted.complete(Unit)
            releaseWrite.await()
        }
        backing.putBoolean(key, value)
    }
}

/**
 * Discovery, badge, reaction, and battery alerts stay on until that one switch is turned off.
 */
class StoredNotificationAlertTest {
    @Test
    fun keepingContactDiscoveryAlertsOnDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }
        val before = prefs.snapshot()
        assertTrue(before.discoveryContactEnabled)
        assertTrue(before.discoveryRepeaterEnabled)
        assertTrue(before.discoveryRoomEnabled)
        assertTrue(before.newContactDiscoveredEnabled)
        assertTrue(before.contactMessagesEnabled)
        assertTrue(before.soundEnabled)
        assertTrue(before.badgeEnabled)
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT))
        assertTrue(store.snapshot().isEmpty())

        prefs.setDiscoveryContactEnabled(true)

        val snap = prefs.snapshot()
        assertTrue(snap.discoveryContactEnabled)
        assertTrue(snap.discoveryRepeaterEnabled)
        assertTrue(snap.discoveryRoomEnabled)
        assertTrue(snap.newContactDiscoveredEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(true, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
        assertEquals(
            mapOf(NotificationPreferenceKeys.DISCOVERY_CONTACT to true),
            store.snapshot(),
        )
    }

    @Test
    fun turningOffContactDiscoveryNotificationsDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }

        prefs.setDiscoveryContactEnabled(false)

        val snap = prefs.snapshot()
        assertFalse(snap.discoveryContactEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.channelMessagesEnabled)
        assertTrue(snap.roomMessagesEnabled)
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.lowBatteryEnabled)
        assertTrue(snap.newContactDiscoveredEnabled)
        assertTrue(snap.discoveryRepeaterEnabled)
        assertTrue(snap.discoveryRoomEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(false, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.LOW_BATTERY))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
    }

    @Test
    fun keepingRepeaterDiscoveryAlertsOnDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }
        val before = prefs.snapshot()
        assertTrue(before.discoveryRepeaterEnabled)
        assertTrue(before.discoveryContactEnabled)
        assertTrue(before.discoveryRoomEnabled)
        assertTrue(before.newContactDiscoveredEnabled)
        assertTrue(before.contactMessagesEnabled)
        assertTrue(before.soundEnabled)
        assertTrue(before.badgeEnabled)
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER))
        assertTrue(store.snapshot().isEmpty())

        prefs.setDiscoveryRepeaterEnabled(true)

        val snap = prefs.snapshot()
        assertTrue(snap.discoveryRepeaterEnabled)
        assertTrue(snap.discoveryContactEnabled)
        assertTrue(snap.discoveryRoomEnabled)
        assertTrue(snap.newContactDiscoveredEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(true, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
        assertEquals(
            mapOf(NotificationPreferenceKeys.DISCOVERY_REPEATER to true),
            store.snapshot(),
        )
    }

    @Test
    fun turningOffRepeaterDiscoveryNotificationsDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }

        prefs.setDiscoveryRepeaterEnabled(false)

        val snap = prefs.snapshot()
        assertFalse(snap.discoveryRepeaterEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.channelMessagesEnabled)
        assertTrue(snap.roomMessagesEnabled)
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.lowBatteryEnabled)
        assertTrue(snap.newContactDiscoveredEnabled)
        assertTrue(snap.discoveryContactEnabled)
        assertTrue(snap.discoveryRoomEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(false, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.LOW_BATTERY))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
    }

    @Test
    fun keepingRoomDiscoveryAlertsOnDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }
        val before = prefs.snapshot()
        assertTrue(before.discoveryRoomEnabled)
        assertTrue(before.discoveryContactEnabled)
        assertTrue(before.discoveryRepeaterEnabled)
        assertTrue(before.newContactDiscoveredEnabled)
        assertTrue(before.roomMessagesEnabled)
        assertTrue(before.soundEnabled)
        assertTrue(before.badgeEnabled)
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM))
        assertTrue(store.snapshot().isEmpty())

        prefs.setDiscoveryRoomEnabled(true)

        val snap = prefs.snapshot()
        assertTrue(snap.discoveryRoomEnabled)
        assertTrue(snap.discoveryContactEnabled)
        assertTrue(snap.discoveryRepeaterEnabled)
        assertTrue(snap.newContactDiscoveredEnabled)
        assertTrue(snap.roomMessagesEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(true, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
        assertEquals(
            mapOf(NotificationPreferenceKeys.DISCOVERY_ROOM to true),
            store.snapshot(),
        )
    }

    @Test
    fun turningOffRoomDiscoveryNotificationsDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }

        prefs.setDiscoveryRoomEnabled(false)

        val snap = prefs.snapshot()
        assertFalse(snap.discoveryRoomEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.channelMessagesEnabled)
        assertTrue(snap.roomMessagesEnabled)
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.lowBatteryEnabled)
        assertTrue(snap.newContactDiscoveredEnabled)
        assertTrue(snap.discoveryContactEnabled)
        assertTrue(snap.discoveryRepeaterEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(false, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.LOW_BATTERY))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
    }

    @Test
    fun keepingTheNotificationBadgeOnDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }
        val before = prefs.snapshot()
        assertTrue(before.badgeEnabled)
        assertTrue(before.soundEnabled)
        assertTrue(before.contactMessagesEnabled)
        assertTrue(before.reactionsEnabled)
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
        assertTrue(store.snapshot().isEmpty())

        prefs.setBadgeEnabled(true)

        val snap = prefs.snapshot()
        assertTrue(snap.badgeEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.lowBatteryEnabled)
        assertEquals(true, store.getBoolean(NotificationPreferenceKeys.BADGE))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(
            mapOf(NotificationPreferenceKeys.BADGE to true),
            store.snapshot(),
        )
    }

    @Test
    fun turningOffTheNotificationBadgeDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }

        prefs.setBadgeEnabled(false)

        val snap = prefs.snapshot()
        assertFalse(snap.badgeEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.channelMessagesEnabled)
        assertTrue(snap.roomMessagesEnabled)
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.lowBatteryEnabled)
        assertTrue(snap.newContactDiscoveredEnabled)
        assertTrue(snap.discoveryContactEnabled)
        assertTrue(snap.discoveryRepeaterEnabled)
        assertTrue(snap.discoveryRoomEnabled)
        assertTrue(snap.soundEnabled)
        assertEquals(false, store.getBoolean(NotificationPreferenceKeys.BADGE))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.LOW_BATTERY))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
    }

    @Test
    fun keepingReactionAlertsOnDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }
        val before = prefs.snapshot()
        assertTrue(before.reactionsEnabled)
        assertTrue(before.roomMessagesEnabled)
        assertTrue(before.channelMessagesEnabled)
        assertTrue(before.contactMessagesEnabled)
        assertTrue(before.soundEnabled)
        assertTrue(before.badgeEnabled)
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertTrue(store.snapshot().isEmpty())

        prefs.setReactionsEnabled(true)

        val snap = prefs.snapshot()
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.roomMessagesEnabled)
        assertTrue(snap.channelMessagesEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.lowBatteryEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(true, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
        assertEquals(
            mapOf(NotificationPreferenceKeys.REACTIONS to true),
            store.snapshot(),
        )
    }

    @Test
    fun turningOffReactionNotificationsDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }

        prefs.setReactionsEnabled(false)

        val snap = prefs.snapshot()
        assertFalse(snap.reactionsEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.channelMessagesEnabled)
        assertTrue(snap.roomMessagesEnabled)
        assertTrue(snap.lowBatteryEnabled)
        assertTrue(snap.newContactDiscoveredEnabled)
        assertTrue(snap.discoveryContactEnabled)
        assertTrue(snap.discoveryRepeaterEnabled)
        assertTrue(snap.discoveryRoomEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(false, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.LOW_BATTERY))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
    }

    @Test
    fun keepingLowBatteryAlertsOnDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }
        val before = prefs.snapshot()
        assertTrue(before.lowBatteryEnabled)
        assertTrue(before.reactionsEnabled)
        assertTrue(before.roomMessagesEnabled)
        assertTrue(before.channelMessagesEnabled)
        assertTrue(before.soundEnabled)
        assertTrue(before.badgeEnabled)
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.LOW_BATTERY))
        assertTrue(store.snapshot().isEmpty())

        prefs.setLowBatteryEnabled(true)

        val snap = prefs.snapshot()
        assertTrue(snap.lowBatteryEnabled)
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.roomMessagesEnabled)
        assertTrue(snap.channelMessagesEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(true, store.getBoolean(NotificationPreferenceKeys.LOW_BATTERY))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
        assertEquals(
            mapOf(NotificationPreferenceKeys.LOW_BATTERY to true),
            store.snapshot(),
        )
    }

    @Test
    fun turningOffTheLowBatteryNotificationDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }

        prefs.setLowBatteryEnabled(false)

        val snap = prefs.snapshot()
        assertFalse(snap.lowBatteryEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.channelMessagesEnabled)
        assertTrue(snap.roomMessagesEnabled)
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.newContactDiscoveredEnabled)
        assertTrue(snap.discoveryContactEnabled)
        assertTrue(snap.discoveryRepeaterEnabled)
        assertTrue(snap.discoveryRoomEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(false, store.getBoolean(NotificationPreferenceKeys.LOW_BATTERY))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
    }

    @Test
    fun keepingNewContactDiscoveryAlertsOnDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }
        val before = prefs.snapshot()
        assertTrue(before.newContactDiscoveredEnabled)
        assertTrue(before.discoveryContactEnabled)
        assertTrue(before.discoveryRepeaterEnabled)
        assertTrue(before.discoveryRoomEnabled)
        assertTrue(before.contactMessagesEnabled)
        assertTrue(before.soundEnabled)
        assertTrue(before.badgeEnabled)
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED))
        assertTrue(store.snapshot().isEmpty())

        prefs.setNewContactDiscoveredEnabled(true)

        val snap = prefs.snapshot()
        assertTrue(snap.newContactDiscoveredEnabled)
        assertTrue(snap.discoveryContactEnabled)
        assertTrue(snap.discoveryRepeaterEnabled)
        assertTrue(snap.discoveryRoomEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(true, store.getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
        assertEquals(
            mapOf(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED to true),
            store.snapshot(),
        )
    }

    @Test
    fun turningOffNewContactDiscoveryNotificationsDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = StoredNotificationPreferences(store) { 0.0 }

        prefs.setNewContactDiscoveredEnabled(false)

        val snap = prefs.snapshot()
        assertFalse(snap.newContactDiscoveredEnabled)
        assertTrue(snap.discoveryContactEnabled)
        assertTrue(snap.discoveryRepeaterEnabled)
        assertTrue(snap.discoveryRoomEnabled)
        assertTrue(snap.contactMessagesEnabled)
        assertTrue(snap.channelMessagesEnabled)
        assertTrue(snap.roomMessagesEnabled)
        assertTrue(snap.reactionsEnabled)
        assertTrue(snap.lowBatteryEnabled)
        assertTrue(snap.soundEnabled)
        assertTrue(snap.badgeEnabled)
        assertEquals(false, store.getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.REACTIONS))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.LOW_BATTERY))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.SOUND))
        assertEquals(null, store.getBoolean(NotificationPreferenceKeys.BADGE))
    }
}
