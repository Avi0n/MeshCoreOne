package one.meshcore.shared.settings.ui

import one.meshcore.shared.connection.TransportType
import one.meshcore.shared.navigation.SettingsDestination
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_info_region_manage
import one.meshcore.shared.resources.contacts_blocked_title
import one.meshcore.shared.resources.settings_advanced_settings_title
import one.meshcore.shared.resources.settings_appearance_title
import one.meshcore.shared.resources.settings_backup_title
import one.meshcore.shared.resources.settings_blocking_channel_senders_title
import one.meshcore.shared.resources.settings_bluetooth_header
import one.meshcore.shared.resources.settings_chat_settings_title
import one.meshcore.shared.resources.settings_config_export_title
import one.meshcore.shared.resources.settings_config_import_title
import one.meshcore.shared.resources.settings_danger_zone_header
import one.meshcore.shared.resources.settings_device_info_connection_header
import one.meshcore.shared.resources.settings_device_info_title
import one.meshcore.shared.resources.settings_diagnostics_header
import one.meshcore.shared.resources.settings_feedback_title
import one.meshcore.shared.resources.settings_language_title
import one.meshcore.shared.resources.settings_language_translate_into_header
import one.meshcore.shared.resources.settings_location_header
import one.meshcore.shared.resources.settings_maps_title
import one.meshcore.shared.resources.settings_notifications_header
import one.meshcore.shared.resources.settings_offline_maps_title
import one.meshcore.shared.resources.settings_public_key_title
import one.meshcore.shared.resources.settings_radio_header
import one.meshcore.shared.resources.settings_radio_preset_location
import one.meshcore.shared.resources.settings_support_title
import one.meshcore.shared.resources.settings_trusted_contacts_title
import one.meshcore.shared.resources.settings_wifi_header
import org.jetbrains.compose.resources.StringResource

/** Hub row title for [dest]. Connection follows the live transport; every other row is fixed. */
internal fun settingsHubTitleResource(
    dest: SettingsDestination,
    transportType: TransportType? = null,
): StringResource = when (dest) {
    SettingsDestination.DEVICE_INFO,
    SettingsDestination.PUBLIC_KEY,
    SettingsDestination.RADIO,
    SettingsDestination.PRESET_LOCATION,
    SettingsDestination.LOCATION,
    SettingsDestination.CONNECTION,
    SettingsDestination.ADVANCED,
    SettingsDestination.CONFIG_EXPORT,
    SettingsDestination.CONFIG_IMPORT,
    -> settingsHubTitleThroughImport(dest, transportType)
    SettingsDestination.REGION_MANAGEMENT,
    SettingsDestination.TRUSTED_CONTACTS,
    SettingsDestination.NOTIFICATIONS,
    SettingsDestination.CHATS,
    SettingsDestination.BLOCKED_CONTACTS,
    SettingsDestination.BLOCKED_CHANNEL_SENDERS,
    SettingsDestination.APPEARANCE,
    SettingsDestination.LANGUAGE,
    SettingsDestination.TRANSLATE_INTO,
    -> settingsHubTitleThroughAppearance(dest)
    SettingsDestination.MAPS,
    SettingsDestination.OFFLINE_MAPS,
    SettingsDestination.BACKUP,
    SettingsDestination.SUPPORT,
    SettingsDestination.DANGER_ZONE,
    SettingsDestination.FEEDBACK,
    SettingsDestination.DIAGNOSTICS,
    -> settingsHubTitleThroughDiagnostics(dest)
}

private fun settingsHubTitleThroughImport(dest: SettingsDestination, transportType: TransportType?): StringResource =
    when (dest) {
        SettingsDestination.DEVICE_INFO -> Res.string.settings_device_info_title
        SettingsDestination.PUBLIC_KEY -> Res.string.settings_public_key_title
        SettingsDestination.RADIO -> Res.string.settings_radio_header
        SettingsDestination.PRESET_LOCATION -> Res.string.settings_radio_preset_location
        SettingsDestination.LOCATION -> Res.string.settings_location_header
        SettingsDestination.CONNECTION -> when (transportType) {
            TransportType.WIFI -> Res.string.settings_wifi_header
            TransportType.BLUETOOTH -> Res.string.settings_bluetooth_header
            null -> Res.string.settings_device_info_connection_header
        }
        SettingsDestination.ADVANCED -> Res.string.settings_advanced_settings_title
        SettingsDestination.CONFIG_EXPORT -> Res.string.settings_config_export_title
        SettingsDestination.CONFIG_IMPORT -> Res.string.settings_config_import_title
        else -> error("Unhandled settings destination title: $dest")
    }

private fun settingsHubTitleThroughAppearance(dest: SettingsDestination): StringResource = when (dest) {
    SettingsDestination.REGION_MANAGEMENT -> Res.string.chats_channel_info_region_manage
    SettingsDestination.BLOCKED_CONTACTS -> Res.string.contacts_blocked_title
    SettingsDestination.BLOCKED_CHANNEL_SENDERS -> Res.string.settings_blocking_channel_senders_title
    SettingsDestination.TRUSTED_CONTACTS -> Res.string.settings_trusted_contacts_title
    SettingsDestination.NOTIFICATIONS -> Res.string.settings_notifications_header
    SettingsDestination.CHATS -> Res.string.settings_chat_settings_title
    SettingsDestination.LANGUAGE -> Res.string.settings_language_title
    SettingsDestination.TRANSLATE_INTO -> Res.string.settings_language_translate_into_header
    SettingsDestination.APPEARANCE -> Res.string.settings_appearance_title
    else -> error("Unhandled settings destination title: $dest")
}

private fun settingsHubTitleThroughDiagnostics(dest: SettingsDestination): StringResource = when (dest) {
    SettingsDestination.MAPS -> Res.string.settings_maps_title
    SettingsDestination.OFFLINE_MAPS -> Res.string.settings_offline_maps_title
    SettingsDestination.BACKUP -> Res.string.settings_backup_title
    SettingsDestination.SUPPORT -> Res.string.settings_support_title
    SettingsDestination.DANGER_ZONE -> Res.string.settings_danger_zone_header
    SettingsDestination.FEEDBACK -> Res.string.settings_feedback_title
    SettingsDestination.DIAGNOSTICS -> Res.string.settings_diagnostics_header
    else -> error("Unhandled settings destination title: $dest")
}
