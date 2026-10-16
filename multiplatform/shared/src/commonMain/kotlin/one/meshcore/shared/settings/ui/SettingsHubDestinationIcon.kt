package one.meshcore.shared.settings.ui

import androidx.compose.ui.graphics.vector.ImageVector
import one.meshcore.shared.connection.ConnectionSettingsSnapshot
import one.meshcore.shared.navigation.SettingsDestination
import one.meshcore.shared.ui.icons.AppIcons

/** Hub glyph for [dest]. Connection follows the live transport; every other row is fixed. */
internal fun settingsHubDestinationIcon(
    dest: SettingsDestination,
    connectionSettingsSnapshot: ConnectionSettingsSnapshot? = null,
): ImageVector = when (dest) {
    SettingsDestination.DEVICE_INFO,
    SettingsDestination.PUBLIC_KEY,
    SettingsDestination.RADIO,
    SettingsDestination.PRESET_LOCATION,
    SettingsDestination.LOCATION,
    SettingsDestination.CONNECTION,
    SettingsDestination.ADVANCED,
    SettingsDestination.CONFIG_EXPORT,
    SettingsDestination.CONFIG_IMPORT,
    -> settingsHubIconThroughImport(dest, connectionSettingsSnapshot)
    SettingsDestination.REGION_MANAGEMENT,
    SettingsDestination.TRUSTED_CONTACTS,
    SettingsDestination.NOTIFICATIONS,
    SettingsDestination.CHATS,
    SettingsDestination.BLOCKED_CONTACTS,
    SettingsDestination.BLOCKED_CHANNEL_SENDERS,
    SettingsDestination.APPEARANCE,
    SettingsDestination.LANGUAGE,
    SettingsDestination.TRANSLATE_INTO,
    -> settingsHubIconThroughAppearance(dest)
    SettingsDestination.MAPS,
    SettingsDestination.OFFLINE_MAPS,
    SettingsDestination.BACKUP,
    SettingsDestination.SUPPORT,
    SettingsDestination.DANGER_ZONE,
    SettingsDestination.FEEDBACK,
    SettingsDestination.DIAGNOSTICS,
    -> settingsHubIconThroughDiagnostics(dest)
}

private fun settingsHubIconThroughImport(
    dest: SettingsDestination,
    connectionSettingsSnapshot: ConnectionSettingsSnapshot?,
): ImageVector = when (dest) {
    SettingsDestination.DEVICE_INFO -> SETTINGS_HUB_DEVICE_INFO_ICON
    SettingsDestination.PUBLIC_KEY -> AppIcons.Key
    SettingsDestination.RADIO, SettingsDestination.PRESET_LOCATION -> SETTINGS_HUB_RADIO_ICON
    SettingsDestination.LOCATION -> AppIcons.LocationOn
    SettingsDestination.CONNECTION -> connectionHubIcon(connectionSettingsSnapshot?.transportType)
    SettingsDestination.ADVANCED -> SETTINGS_HUB_ADVANCED_ICON
    SettingsDestination.CONFIG_EXPORT -> AppIcons.Share
    SettingsDestination.CONFIG_IMPORT -> AppIcons.CloudDownload
    else -> error("Unhandled settings destination icon: $dest")
}

private fun settingsHubIconThroughAppearance(dest: SettingsDestination): ImageVector = when (dest) {
    SettingsDestination.REGION_MANAGEMENT -> AppIcons.ListAlt
    SettingsDestination.BLOCKED_CONTACTS -> BLOCKING_CONTACTS_ICON
    SettingsDestination.BLOCKED_CHANNEL_SENDERS -> BLOCKING_CHANNEL_SENDERS_ICON
    SettingsDestination.TRUSTED_CONTACTS -> AppIcons.Group
    SettingsDestination.NOTIFICATIONS -> AppIcons.Notifications
    SettingsDestination.CHATS -> AppIcons.ChatsSelected
    SettingsDestination.LANGUAGE, SettingsDestination.TRANSLATE_INTO -> AppIcons.Public
    SettingsDestination.APPEARANCE -> SETTINGS_HUB_APPEARANCE_ICON
    else -> error("Unhandled settings destination icon: $dest")
}

private fun settingsHubIconThroughDiagnostics(dest: SettingsDestination): ImageVector = when (dest) {
    SettingsDestination.MAPS -> AppIcons.MapSelected
    SettingsDestination.OFFLINE_MAPS -> AppIcons.CloudDownload
    SettingsDestination.BACKUP -> SETTINGS_HUB_BACKUP_ICON
    SettingsDestination.SUPPORT -> AppIcons.Favorite
    SettingsDestination.DANGER_ZONE -> AppIcons.Warning
    SettingsDestination.FEEDBACK -> ABOUT_FEEDBACK_ICON
    SettingsDestination.DIAGNOSTICS -> AppIcons.BugReport
    else -> error("Unhandled settings destination icon: $dest")
}
