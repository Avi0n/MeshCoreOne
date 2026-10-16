package one.meshcore.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsDestinationTest {
    @Test
    fun nestedPagesReturnToTheirIosParent() {
        assertEquals(SettingsDestination.DEVICE_INFO, SettingsDestination.PUBLIC_KEY.backParent)
        assertEquals(SettingsDestination.RADIO, SettingsDestination.PRESET_LOCATION.backParent)
        assertEquals(SettingsDestination.ADVANCED, SettingsDestination.REGION_MANAGEMENT.backParent)
        assertEquals(SettingsDestination.ADVANCED, SettingsDestination.CONFIG_EXPORT.backParent)
        assertEquals(SettingsDestination.ADVANCED, SettingsDestination.CONFIG_IMPORT.backParent)
        assertEquals(SettingsDestination.ADVANCED, SettingsDestination.TRUSTED_CONTACTS.backParent)
        assertEquals(SettingsDestination.ADVANCED, SettingsDestination.DANGER_ZONE.backParent)
        assertTrue(SettingsDestination.DANGER_ZONE.requiresDevice)
        assertEquals(SettingsDestination.CHATS, SettingsDestination.BLOCKED_CONTACTS.backParent)
        assertEquals(SettingsDestination.CHATS, SettingsDestination.BLOCKED_CHANNEL_SENDERS.backParent)
        assertEquals(SettingsDestination.MAPS, SettingsDestination.OFFLINE_MAPS.backParent)
        assertEquals(SettingsDestination.LANGUAGE, SettingsDestination.TRANSLATE_INTO.backParent)
        assertEquals(SettingsDestination.FEEDBACK, SettingsDestination.DIAGNOSTICS.backParent)
    }

    @Test
    fun hubPagesHaveNoParent() {
        assertNull(SettingsDestination.ADVANCED.backParent)
        assertNull(SettingsDestination.RADIO.backParent)
        assertNull(SettingsDestination.LOCATION.backParent)
        assertNull(SettingsDestination.DEVICE_INFO.backParent)
        assertNull(SettingsDestination.BACKUP.backParent)
        assertNull(SettingsDestination.FEEDBACK.backParent)
        assertNull(SettingsDestination.CHATS.backParent)
    }
}
