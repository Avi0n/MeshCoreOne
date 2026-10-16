package one.meshcore.shared.channel.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.channel.ChannelActionError
import one.meshcore.shared.channel.RegionLabelKind
import one.meshcore.shared.channel.ScanChannelError
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_info_channel_type_hashtag
import one.meshcore.shared.resources.chats_channel_info_channel_type_private
import one.meshcore.shared.resources.chats_channel_info_channel_type_public
import one.meshcore.shared.resources.chats_channel_info_region_all_regions
import one.meshcore.shared.resources.chats_channel_info_region_use_default_format
import one.meshcore.shared.resources.chats_error_no_device_connected
import one.meshcore.shared.resources.chats_error_pin_save_failed
import one.meshcore.shared.resources.chats_error_services_unavailable
import one.meshcore.shared.resources.chats_join_from_message_error_invalid_name
import one.meshcore.shared.resources.chats_join_from_message_error_load_failed
import one.meshcore.shared.resources.chats_join_from_message_error_no_slots
import one.meshcore.shared.resources.chats_join_private_error_invalid_format
import one.meshcore.shared.resources.chats_notification_level_accessibility_all
import one.meshcore.shared.resources.chats_notification_level_accessibility_mentions_only
import one.meshcore.shared.resources.chats_notification_level_accessibility_muted
import one.meshcore.shared.resources.chats_notification_level_all
import one.meshcore.shared.resources.chats_notification_level_hint
import one.meshcore.shared.resources.chats_notification_level_mentions
import one.meshcore.shared.resources.chats_notification_level_muted
import one.meshcore.shared.resources.chats_scan_qr_error_invalid_format
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The user-facing message for a channel create/join/info error. */
@Composable
fun ChannelActionError.message(): String = when (this) {
    ChannelActionError.NoDeviceConnected -> stringResource(Res.string.chats_error_no_device_connected)
    ChannelActionError.ServicesUnavailable -> stringResource(Res.string.chats_error_services_unavailable)
    ChannelActionError.NoSlots -> stringResource(Res.string.chats_join_from_message_error_no_slots)
    ChannelActionError.InvalidName -> stringResource(Res.string.chats_join_from_message_error_invalid_name)
    ChannelActionError.InvalidSecretFormat -> stringResource(Res.string.chats_join_private_error_invalid_format)
    ChannelActionError.LoadFailed -> stringResource(Res.string.chats_join_from_message_error_load_failed)
    ChannelActionError.PinSaveFailed -> stringResource(Res.string.chats_error_pin_save_failed)
    is ChannelActionError.Service -> error.userFacingMessage()
}

/** The user-facing message for a channel-QR scan error. */
@Composable
fun ScanChannelError.message(): String = when (this) {
    ScanChannelError.InvalidQrFormat -> stringResource(Res.string.chats_scan_qr_error_invalid_format)
    is ScanChannelError.Join -> error.message()
}

/** The localized picker-row label for a flood-scope option. */
@Composable
fun RegionLabelKind.label(): String = when (this) {
    is RegionLabelKind.UseDefault ->
        formattedStringResource(Res.string.chats_channel_info_region_use_default_format, defaultName)
    RegionLabelKind.AllRegions -> stringResource(Res.string.chats_channel_info_region_all_regions)
    is RegionLabelKind.Named -> name
}

/** The localized notification-level label for the channel picker. */
@Composable
fun NotificationLevel.channelLabel(): String = stringResource(
    when (this) {
        NotificationLevel.MUTED -> Res.string.chats_notification_level_muted
        NotificationLevel.MENTIONS_ONLY -> Res.string.chats_notification_level_mentions
        NotificationLevel.ALL -> Res.string.chats_notification_level_all
    },
)

/**
 * The spoken form of a notification level: what it does to notifications, not the one-word chip
 * label. Mirrors iOS `NotificationLevel.localizedAccessibilityDescription`.
 */
internal fun NotificationLevel.notificationAccessibilityDescriptionRes(): StringResource = when (this) {
    NotificationLevel.MUTED -> Res.string.chats_notification_level_accessibility_muted
    NotificationLevel.MENTIONS_ONLY -> Res.string.chats_notification_level_accessibility_mentions_only
    NotificationLevel.ALL -> Res.string.chats_notification_level_accessibility_all
}

/** Group hint for the notification-level picker, matching iOS `NotificationLevel.hint`. */
internal fun notificationLevelPickerHintRes(): StringResource = Res.string.chats_notification_level_hint

@Composable
fun NotificationLevel.notificationAccessibilityDescription(): String =
    stringResource(notificationAccessibilityDescriptionRes())

/** The localized "Public / Hashtag / Private" type label for a channel. */
@Composable
fun ChannelDto.typeLabel(): String = stringResource(
    when {
        index == 0 -> Res.string.chats_channel_info_channel_type_public
        name.startsWith("#") -> Res.string.chats_channel_info_channel_type_hashtag
        else -> Res.string.chats_channel_info_channel_type_private
    },
)
