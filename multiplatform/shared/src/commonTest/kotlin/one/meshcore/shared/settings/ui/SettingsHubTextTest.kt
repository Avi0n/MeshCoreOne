package one.meshcore.shared.settings.ui

import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.backup.RegionSource
import one.meshcore.shared.connection.TransportType
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
import one.meshcore.shared.resources.chats_channel_info_region_manage
import one.meshcore.shared.resources.contacts_blocked_title
import one.meshcore.shared.resources.settings_advanced_settings_title
import one.meshcore.shared.resources.settings_appearance_title
import one.meshcore.shared.resources.settings_backup_title
import one.meshcore.shared.resources.settings_battery_curve_custom
import one.meshcore.shared.resources.settings_blocking_channel_senders_title
import one.meshcore.shared.resources.settings_blocking_header
import one.meshcore.shared.resources.settings_bluetooth_header
import one.meshcore.shared.resources.settings_chat_settings_title
import one.meshcore.shared.resources.settings_config_export_section_footer
import one.meshcore.shared.resources.settings_config_export_title
import one.meshcore.shared.resources.settings_config_import_title
import one.meshcore.shared.resources.settings_danger_zone_header
import one.meshcore.shared.resources.settings_default_flood_scope_footer
import one.meshcore.shared.resources.settings_device_info_connection_header
import one.meshcore.shared.resources.settings_device_info_title
import one.meshcore.shared.resources.settings_diagnostics_header
import one.meshcore.shared.resources.settings_feedback_header_body
import one.meshcore.shared.resources.settings_feedback_title
import one.meshcore.shared.resources.settings_language_title
import one.meshcore.shared.resources.settings_language_translate_into_header
import one.meshcore.shared.resources.settings_location_header
import one.meshcore.shared.resources.settings_location_not_sharing
import one.meshcore.shared.resources.settings_location_sharing_publicly
import one.meshcore.shared.resources.settings_maps_title
import one.meshcore.shared.resources.settings_notifications_header
import one.meshcore.shared.resources.settings_offline_maps_title
import one.meshcore.shared.resources.settings_public_key_title
import one.meshcore.shared.resources.settings_radio_header
import one.meshcore.shared.resources.settings_radio_preset_location
import one.meshcore.shared.resources.settings_radio_preset_location_not_set
import one.meshcore.shared.resources.settings_support_title
import one.meshcore.shared.resources.settings_trusted_contacts_title
import one.meshcore.shared.resources.settings_wifi_header
import one.meshcore.shared.settings.AdvertLocationPolicy
import one.meshcore.shared.settings.LocationSettingsSnapshot
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.settings.RadioPresets
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsHubTextTest {
    @Test
    fun everyDestinationUsesItsHubTitle() {
        val expected = listOf(
            SettingsDestination.DEVICE_INFO to Res.string.settings_device_info_title,
            SettingsDestination.PUBLIC_KEY to Res.string.settings_public_key_title,
            SettingsDestination.RADIO to Res.string.settings_radio_header,
            SettingsDestination.PRESET_LOCATION to Res.string.settings_radio_preset_location,
            SettingsDestination.LOCATION to Res.string.settings_location_header,
            SettingsDestination.CONNECTION to Res.string.settings_device_info_connection_header,
            SettingsDestination.ADVANCED to Res.string.settings_advanced_settings_title,
            SettingsDestination.CONFIG_EXPORT to Res.string.settings_config_export_title,
            SettingsDestination.CONFIG_IMPORT to Res.string.settings_config_import_title,
            SettingsDestination.REGION_MANAGEMENT to Res.string.chats_channel_info_region_manage,
            SettingsDestination.TRUSTED_CONTACTS to Res.string.settings_trusted_contacts_title,
            SettingsDestination.NOTIFICATIONS to Res.string.settings_notifications_header,
            SettingsDestination.CHATS to Res.string.settings_chat_settings_title,
            SettingsDestination.BLOCKED_CONTACTS to Res.string.contacts_blocked_title,
            SettingsDestination.BLOCKED_CHANNEL_SENDERS to Res.string.settings_blocking_channel_senders_title,
            SettingsDestination.APPEARANCE to Res.string.settings_appearance_title,
            SettingsDestination.MAPS to Res.string.settings_maps_title,
            SettingsDestination.OFFLINE_MAPS to Res.string.settings_offline_maps_title,
            SettingsDestination.LANGUAGE to Res.string.settings_language_title,
            SettingsDestination.TRANSLATE_INTO to Res.string.settings_language_translate_into_header,
            SettingsDestination.BACKUP to Res.string.settings_backup_title,
            SettingsDestination.SUPPORT to Res.string.settings_support_title,
            SettingsDestination.DANGER_ZONE to Res.string.settings_danger_zone_header,
            SettingsDestination.FEEDBACK to Res.string.settings_feedback_title,
            SettingsDestination.DIAGNOSTICS to Res.string.settings_diagnostics_header,
        )
        assertEquals(SettingsDestination.entries.size, expected.size)
        assertEquals(expected.map { it.second }, expected.map { settingsHubTitleResource(it.first) })
        assertEquals(
            Res.string.settings_wifi_header,
            settingsHubTitleResource(SettingsDestination.CONNECTION, TransportType.WIFI),
        )
        assertEquals(
            Res.string.settings_bluetooth_header,
            settingsHubTitleResource(SettingsDestination.CONNECTION, TransportType.BLUETOOTH),
        )
    }

    @Test
    fun everyDestinationUsesItsDefaultSubtitle() {
        val expected = listOf(
            SettingsDestination.DEVICE_INFO to hubResource(Res.string.android_settings_hub_device_info_subtitle),
            SettingsDestination.PUBLIC_KEY to hubResource(Res.string.settings_public_key_title),
            SettingsDestination.RADIO to hubResource(Res.string.android_settings_hub_radio_subtitle),
            SettingsDestination.PRESET_LOCATION to hubResource(Res.string.settings_radio_preset_location_not_set),
            SettingsDestination.LOCATION to hubResource(Res.string.android_settings_hub_location_subtitle),
            SettingsDestination.CONNECTION to hubResource(Res.string.android_settings_hub_connection_subtitle),
            SettingsDestination.ADVANCED to hubResource(Res.string.android_settings_hub_advanced_subtitle),
            SettingsDestination.CONFIG_EXPORT to hubResource(Res.string.settings_config_export_section_footer),
            SettingsDestination.CONFIG_IMPORT to hubResource(Res.string.settings_config_export_section_footer),
            SettingsDestination.REGION_MANAGEMENT to hubResource(Res.string.settings_default_flood_scope_footer),
            SettingsDestination.BLOCKED_CONTACTS to hubResource(Res.string.settings_blocking_header),
            SettingsDestination.BLOCKED_CHANNEL_SENDERS to hubResource(Res.string.settings_blocking_header),
            SettingsDestination.TRUSTED_CONTACTS to
                hubResource(Res.string.android_settings_hub_trusted_contacts_subtitle),
            SettingsDestination.NOTIFICATIONS to hubResource(Res.string.android_settings_hub_notifications_subtitle),
            SettingsDestination.CHATS to hubResource(Res.string.android_settings_hub_chats_subtitle),
            SettingsDestination.LANGUAGE to SettingsHubSupporting.Text(""),
            SettingsDestination.TRANSLATE_INTO to SettingsHubSupporting.Text(""),
            SettingsDestination.APPEARANCE to SettingsHubSupporting.Text(""),
            SettingsDestination.MAPS to hubResource(Res.string.android_settings_hub_maps_subtitle),
            SettingsDestination.OFFLINE_MAPS to hubResource(Res.string.android_settings_hub_offline_maps_subtitle),
            SettingsDestination.BACKUP to hubResource(Res.string.android_settings_hub_backup_subtitle),
            SettingsDestination.SUPPORT to hubResource(Res.string.android_settings_hub_support_subtitle),
            SettingsDestination.DANGER_ZONE to hubResource(Res.string.android_settings_hub_danger_subtitle),
            SettingsDestination.FEEDBACK to hubResource(Res.string.settings_feedback_header_body),
            SettingsDestination.DIAGNOSTICS to hubResource(Res.string.android_settings_hub_diagnostics_subtitle),
        )
        assertEquals(SettingsDestination.entries.size, expected.size)
        val actual = expected.map { settingsHubSupportingLine(it.first, SettingsHubSupportingContext(enabled = true)) }
        assertEquals(expected.map { it.second }, actual)
    }

    @Test
    fun disabledRowUsesTheSharedRadioSubtitle() {
        val line = settingsHubSupportingLine(
            SettingsDestination.DEVICE_INFO,
            SettingsHubSupportingContext(enabled = false, nodeName = "Relay"),
        )
        assertEquals(hubResource(Res.string.android_settings_hub_requires_radio), line)
    }

    @Test
    fun deviceInfoUsesANonBlankNodeName() {
        assertEquals(
            SettingsHubSupporting.Text("Relay"),
            settingsHubSupportingLine(
                SettingsDestination.DEVICE_INFO,
                SettingsHubSupportingContext(enabled = true, nodeName = "Relay"),
            ),
        )
        assertEquals(
            hubResource(Res.string.android_settings_hub_device_info_subtitle),
            settingsHubSupportingLine(
                SettingsDestination.DEVICE_INFO,
                SettingsHubSupportingContext(enabled = true, nodeName = " "),
            ),
        )
    }

    @Test
    fun radioSubtitleFollowsTheDisplayedPreset() {
        val repeat = RadioPresets.repeatPresets.first { it.id == "repeat-918" }
        val repeating = RadioDeviceSnapshot(
            frequencyKHz = repeat.frequencyKHz,
            bandwidthHz = repeat.bandwidthHz,
            spreadingFactor = repeat.spreadingFactor,
            codingRate = repeat.codingRate,
            clientRepeat = true,
        )
        assertEquals(
            SettingsHubSupporting.Text(repeat.name),
            settingsHubSupportingLine(
                SettingsDestination.RADIO,
                SettingsHubSupportingContext(enabled = true, radioSnapshot = repeating),
            ),
        )

        val us = RadioPresets.all.first { it.id == "us-ca" }
        val regional = RadioDeviceSnapshot(
            frequencyKHz = us.frequencyKHz,
            bandwidthHz = us.bandwidthHz,
            spreadingFactor = us.spreadingFactor,
            codingRate = us.codingRate,
        )
        val region = RegionSelection(countryCode = "US", source = RegionSource.MANUAL)
        assertEquals(
            SettingsHubSupporting.Text(us.name),
            settingsHubSupportingLine(
                SettingsDestination.RADIO,
                SettingsHubSupportingContext(enabled = true, radioSnapshot = regional, region = region),
            ),
        )
        assertEquals(
            hubResource(Res.string.settings_battery_curve_custom),
            settingsHubSupportingLine(
                SettingsDestination.RADIO,
                SettingsHubSupportingContext(enabled = true, radioSnapshot = regional),
            ),
        )
    }

    @Test
    fun presetLocationAndLocationFollowTheResolvedValues() {
        assertEquals(
            SettingsHubSupporting.Text("California"),
            settingsHubSupportingLine(
                SettingsDestination.PRESET_LOCATION,
                SettingsHubSupportingContext(enabled = true, regionName = "California"),
            ),
        )
        val sharing = LocationSettingsSnapshot(
            latitude = 47.0,
            longitude = -122.0,
            advertLocationPolicyRaw = AdvertLocationPolicy.PREFS.rawValue,
        )
        val hidden = sharing.copy(advertLocationPolicyRaw = AdvertLocationPolicy.NONE.rawValue)
        assertEquals(
            hubResource(Res.string.settings_location_sharing_publicly),
            settingsHubSupportingLine(
                SettingsDestination.LOCATION,
                SettingsHubSupportingContext(enabled = true, locationSettingsSnapshot = sharing),
            ),
        )
        assertEquals(
            hubResource(Res.string.settings_location_not_sharing),
            settingsHubSupportingLine(
                SettingsDestination.LOCATION,
                SettingsHubSupportingContext(enabled = true, locationSettingsSnapshot = hidden),
            ),
        )
    }

    @Test
    fun languageAndAppearanceUseTheCallerResolvedNames() {
        val context = SettingsHubSupportingContext(
            enabled = true,
            appearanceName = "Ember",
            languageName = "English",
        )
        assertEquals(
            SettingsHubSupporting.Text("English"),
            settingsHubSupportingLine(SettingsDestination.LANGUAGE, context),
        )
        assertEquals(
            SettingsHubSupporting.Text("English"),
            settingsHubSupportingLine(SettingsDestination.TRANSLATE_INTO, context),
        )
        assertEquals(
            SettingsHubSupporting.Text("Ember"),
            settingsHubSupportingLine(SettingsDestination.APPEARANCE, context),
        )
    }
}

private fun hubResource(resource: StringResource): SettingsHubSupporting = SettingsHubSupporting.Resource(resource)
