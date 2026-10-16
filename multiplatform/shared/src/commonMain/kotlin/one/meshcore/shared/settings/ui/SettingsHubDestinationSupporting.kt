package one.meshcore.shared.settings.ui

import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.navigation.SettingsDestination
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_settings_hub_advanced_subtitle
import one.meshcore.shared.resources.android_settings_hub_backup_subtitle
import one.meshcore.shared.resources.android_settings_hub_chats_subtitle
import one.meshcore.shared.resources.android_settings_hub_connection_subtitle
import one.meshcore.shared.resources.android_settings_hub_danger_subtitle
import one.meshcore.shared.resources.android_settings_hub_device_info_subtitle
import one.meshcore.shared.resources.android_settings_hub_diagnostics_subtitle
import one.meshcore.shared.resources.android_settings_hub_location_subtitle
import one.meshcore.shared.resources.android_settings_hub_maps_subtitle
import one.meshcore.shared.resources.android_settings_hub_notifications_subtitle
import one.meshcore.shared.resources.android_settings_hub_offline_maps_subtitle
import one.meshcore.shared.resources.android_settings_hub_radio_subtitle
import one.meshcore.shared.resources.android_settings_hub_requires_radio
import one.meshcore.shared.resources.android_settings_hub_support_subtitle
import one.meshcore.shared.resources.android_settings_hub_trusted_contacts_subtitle
import one.meshcore.shared.resources.settings_battery_curve_custom
import one.meshcore.shared.resources.settings_blocking_header
import one.meshcore.shared.resources.settings_config_export_section_footer
import one.meshcore.shared.resources.settings_default_flood_scope_footer
import one.meshcore.shared.resources.settings_feedback_header_body
import one.meshcore.shared.resources.settings_location_not_sharing
import one.meshcore.shared.resources.settings_location_sharing_publicly
import one.meshcore.shared.resources.settings_public_key_title
import one.meshcore.shared.resources.settings_radio_preset_location_not_set
import one.meshcore.shared.settings.LocationSettingsSnapshot
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.settings.RadioPresets
import org.jetbrains.compose.resources.StringResource

/** Resolved hub subtitle. [Text] is already user-facing; [Resource] still needs `stringResource`. */
internal sealed interface SettingsHubSupporting {
    data class Resource(val resource: StringResource) : SettingsHubSupporting
    data class Text(val value: String) : SettingsHubSupporting
}

/**
 * Inputs for [settingsHubSupportingLine]. Theme, region, and language names are resolved by the
 * composable caller because those formatters read string resources.
 */
internal data class SettingsHubSupportingContext(
    val enabled: Boolean,
    val nodeName: String? = null,
    val radioSnapshot: RadioDeviceSnapshot? = null,
    val locationSettingsSnapshot: LocationSettingsSnapshot? = null,
    val region: RegionSelection? = null,
    val regionName: String? = null,
    val appearanceName: String = "",
    val languageName: String = "",
)

/** Hub row subtitle for [dest]. A disconnected device row uses one shared line. */
internal fun settingsHubSupportingLine(
    dest: SettingsDestination,
    context: SettingsHubSupportingContext,
): SettingsHubSupporting {
    if (!context.enabled) {
        return SettingsHubSupporting.Resource(Res.string.android_settings_hub_requires_radio)
    }
    return when (dest) {
        SettingsDestination.DEVICE_INFO,
        SettingsDestination.PUBLIC_KEY,
        SettingsDestination.RADIO,
        SettingsDestination.PRESET_LOCATION,
        SettingsDestination.LOCATION,
        SettingsDestination.CONNECTION,
        SettingsDestination.ADVANCED,
        SettingsDestination.CONFIG_EXPORT,
        SettingsDestination.CONFIG_IMPORT,
        -> settingsHubSupportingThroughImport(dest, context)
        SettingsDestination.REGION_MANAGEMENT,
        SettingsDestination.TRUSTED_CONTACTS,
        SettingsDestination.NOTIFICATIONS,
        SettingsDestination.CHATS,
        SettingsDestination.BLOCKED_CONTACTS,
        SettingsDestination.BLOCKED_CHANNEL_SENDERS,
        SettingsDestination.APPEARANCE,
        SettingsDestination.LANGUAGE,
        SettingsDestination.TRANSLATE_INTO,
        -> settingsHubSupportingThroughAppearance(dest, context)
        SettingsDestination.MAPS,
        SettingsDestination.OFFLINE_MAPS,
        SettingsDestination.BACKUP,
        SettingsDestination.SUPPORT,
        SettingsDestination.DANGER_ZONE,
        SettingsDestination.FEEDBACK,
        SettingsDestination.DIAGNOSTICS,
        -> settingsHubSupportingThroughDiagnostics(dest)
    }
}

private fun settingsHubSupportingThroughImport(
    dest: SettingsDestination,
    context: SettingsHubSupportingContext,
): SettingsHubSupporting = when (dest) {
    SettingsDestination.DEVICE_INFO -> deviceInfoSupporting(context.nodeName)
    SettingsDestination.PUBLIC_KEY ->
        SettingsHubSupporting.Resource(Res.string.settings_public_key_title)
    SettingsDestination.RADIO -> radioSupporting(context)
    SettingsDestination.PRESET_LOCATION -> presetLocationSupporting(context.regionName)
    SettingsDestination.LOCATION -> locationSupporting(context.locationSettingsSnapshot)
    SettingsDestination.CONNECTION ->
        SettingsHubSupporting.Resource(Res.string.android_settings_hub_connection_subtitle)
    SettingsDestination.ADVANCED ->
        SettingsHubSupporting.Resource(Res.string.android_settings_hub_advanced_subtitle)
    // Export and import stay off the hub list; the footer is still used when the page is selected.
    SettingsDestination.CONFIG_EXPORT,
    SettingsDestination.CONFIG_IMPORT,
    -> SettingsHubSupporting.Resource(Res.string.settings_config_export_section_footer)
    else -> error("Unhandled settings destination subtitle: $dest")
}

private fun settingsHubSupportingThroughAppearance(
    dest: SettingsDestination,
    context: SettingsHubSupportingContext,
): SettingsHubSupporting = when (dest) {
    SettingsDestination.REGION_MANAGEMENT ->
        SettingsHubSupporting.Resource(Res.string.settings_default_flood_scope_footer)
    SettingsDestination.BLOCKED_CONTACTS,
    SettingsDestination.BLOCKED_CHANNEL_SENDERS,
    -> SettingsHubSupporting.Resource(Res.string.settings_blocking_header)
    SettingsDestination.TRUSTED_CONTACTS ->
        SettingsHubSupporting.Resource(Res.string.android_settings_hub_trusted_contacts_subtitle)
    SettingsDestination.NOTIFICATIONS ->
        SettingsHubSupporting.Resource(Res.string.android_settings_hub_notifications_subtitle)
    SettingsDestination.CHATS ->
        SettingsHubSupporting.Resource(Res.string.android_settings_hub_chats_subtitle)
    SettingsDestination.LANGUAGE,
    SettingsDestination.TRANSLATE_INTO,
    -> SettingsHubSupporting.Text(context.languageName)
    SettingsDestination.APPEARANCE -> SettingsHubSupporting.Text(context.appearanceName)
    else -> error("Unhandled settings destination subtitle: $dest")
}

private fun settingsHubSupportingThroughDiagnostics(dest: SettingsDestination): SettingsHubSupporting = when (dest) {
    SettingsDestination.MAPS ->
        SettingsHubSupporting.Resource(Res.string.android_settings_hub_maps_subtitle)
    SettingsDestination.OFFLINE_MAPS ->
        SettingsHubSupporting.Resource(Res.string.android_settings_hub_offline_maps_subtitle)
    SettingsDestination.BACKUP ->
        SettingsHubSupporting.Resource(Res.string.android_settings_hub_backup_subtitle)
    SettingsDestination.SUPPORT ->
        SettingsHubSupporting.Resource(Res.string.android_settings_hub_support_subtitle)
    SettingsDestination.DANGER_ZONE ->
        SettingsHubSupporting.Resource(Res.string.android_settings_hub_danger_subtitle)
    SettingsDestination.FEEDBACK ->
        SettingsHubSupporting.Resource(Res.string.settings_feedback_header_body)
    SettingsDestination.DIAGNOSTICS ->
        SettingsHubSupporting.Resource(Res.string.android_settings_hub_diagnostics_subtitle)
    else -> error("Unhandled settings destination subtitle: $dest")
}

private fun deviceInfoSupporting(nodeName: String?): SettingsHubSupporting {
    val name = nodeName?.takeIf { it.isNotBlank() }
    return if (name == null) {
        SettingsHubSupporting.Resource(Res.string.android_settings_hub_device_info_subtitle)
    } else {
        SettingsHubSupporting.Text(name)
    }
}

private fun radioSupporting(context: SettingsHubSupportingContext): SettingsHubSupporting {
    val snapshot = context.radioSnapshot
    val name = snapshot?.let { RadioPresets.matchingDisplayedPreset(it, region = context.region)?.name }
    return when {
        snapshot == null ->
            SettingsHubSupporting.Resource(Res.string.android_settings_hub_radio_subtitle)
        name == null -> SettingsHubSupporting.Resource(Res.string.settings_battery_curve_custom)
        else -> SettingsHubSupporting.Text(name)
    }
}

private fun presetLocationSupporting(regionName: String?): SettingsHubSupporting = if (regionName == null) {
    SettingsHubSupporting.Resource(Res.string.settings_radio_preset_location_not_set)
} else {
    SettingsHubSupporting.Text(regionName)
}

private fun locationSupporting(snapshot: LocationSettingsSnapshot?): SettingsHubSupporting = when {
    snapshot == null ->
        SettingsHubSupporting.Resource(Res.string.android_settings_hub_location_subtitle)
    snapshot.shareLocationPublicly ->
        SettingsHubSupporting.Resource(Res.string.settings_location_sharing_publicly)
    else -> SettingsHubSupporting.Resource(Res.string.settings_location_not_sharing)
}
