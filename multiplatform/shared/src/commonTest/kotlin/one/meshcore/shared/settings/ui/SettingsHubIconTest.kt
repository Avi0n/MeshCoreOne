package one.meshcore.shared.settings.ui

import one.meshcore.shared.connection.ConnectionSettingsSnapshot
import one.meshcore.shared.connection.TransportType
import one.meshcore.shared.navigation.SettingsDestination
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsHubIconTest {
    @Test
    fun deviceInfoUsesCpuAnalog() {
        assertEquals(AppIcons.Memory, SETTINGS_HUB_DEVICE_INFO_ICON)
    }

    @Test
    fun radioUsesAntenna() {
        assertEquals(AppIcons.CellTower, SETTINGS_HUB_RADIO_ICON)
    }

    @Test
    fun advancedUsesGearshapeAnalog() {
        assertEquals(AppIcons.SettingsSelected, SETTINGS_HUB_ADVANCED_ICON)
    }

    @Test
    fun backupUsesArchiveBox() {
        assertEquals(AppIcons.Archive, SETTINGS_HUB_BACKUP_ICON)
    }

    @Test
    fun appearanceUsesPaintPalette() {
        assertEquals(AppIcons.Palette, SETTINGS_HUB_APPEARANCE_ICON)
    }

    @Test
    fun everyDestinationUsesItsHubIcon() {
        val expected = listOf(
            SettingsDestination.DEVICE_INFO to SETTINGS_HUB_DEVICE_INFO_ICON,
            SettingsDestination.PUBLIC_KEY to AppIcons.Key,
            SettingsDestination.RADIO to SETTINGS_HUB_RADIO_ICON,
            SettingsDestination.PRESET_LOCATION to SETTINGS_HUB_RADIO_ICON,
            SettingsDestination.LOCATION to AppIcons.LocationOn,
            SettingsDestination.CONNECTION to connectionHubIcon(null),
            SettingsDestination.ADVANCED to SETTINGS_HUB_ADVANCED_ICON,
            SettingsDestination.CONFIG_EXPORT to AppIcons.Share,
            SettingsDestination.CONFIG_IMPORT to AppIcons.CloudDownload,
            SettingsDestination.REGION_MANAGEMENT to AppIcons.ListAlt,
            SettingsDestination.TRUSTED_CONTACTS to AppIcons.Group,
            SettingsDestination.NOTIFICATIONS to AppIcons.Notifications,
            SettingsDestination.CHATS to AppIcons.ChatsSelected,
            SettingsDestination.BLOCKED_CONTACTS to BLOCKING_CONTACTS_ICON,
            SettingsDestination.BLOCKED_CHANNEL_SENDERS to BLOCKING_CHANNEL_SENDERS_ICON,
            SettingsDestination.APPEARANCE to SETTINGS_HUB_APPEARANCE_ICON,
            SettingsDestination.MAPS to AppIcons.MapSelected,
            SettingsDestination.OFFLINE_MAPS to AppIcons.CloudDownload,
            SettingsDestination.LANGUAGE to AppIcons.Public,
            SettingsDestination.TRANSLATE_INTO to AppIcons.Public,
            SettingsDestination.BACKUP to SETTINGS_HUB_BACKUP_ICON,
            SettingsDestination.SUPPORT to AppIcons.Favorite,
            SettingsDestination.DANGER_ZONE to AppIcons.Warning,
            SettingsDestination.FEEDBACK to ABOUT_FEEDBACK_ICON,
            SettingsDestination.DIAGNOSTICS to AppIcons.BugReport,
        )
        assertEquals(expected.map { it.second }, expected.map { settingsHubDestinationIcon(it.first) })
        val wifi = ConnectionSettingsSnapshot(
            transportType = TransportType.WIFI,
            blePin = ConnectionSettingsSnapshot.BLE_PIN_UNSET,
            wifiHost = null,
            wifiPort = null,
            hasBluetoothMethod = false,
            hasWifiMethod = true,
        )
        assertEquals(AppIcons.Wifi, settingsHubDestinationIcon(SettingsDestination.CONNECTION, wifi))
    }
}
