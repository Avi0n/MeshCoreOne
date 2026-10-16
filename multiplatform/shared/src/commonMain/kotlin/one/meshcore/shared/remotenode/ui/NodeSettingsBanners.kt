package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_error_settings_send_failed
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_advert_sent
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_clock_ahead_error
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_no_service
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_password_change_failed
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_password_changed_success
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_password_empty
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_password_mismatch
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_radio_applied_success
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_radio_apply_failed
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_radio_not_loaded
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_reboot_sent
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_add_failed
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_not_empty
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_remove_failed
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_save_failed
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_save_success
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_unknown_region
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_settings_applied
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_some_settings_failed_to_apply
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_sync_time_failed
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_time_synced
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_unexpected_response
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ContactInfoApplyError(banner: ApplyBanner?) {
    if (banner == null || isSuccessBanner(banner)) return
    ApplyErrorCaption(bannerLabel(banner))
}

/** Apply-fault caption uses the theme error color, matching other form validation. */
@Composable
internal fun ApplyErrorCaption(text: String, modifier: Modifier = Modifier) {
    ApplyOutcomeCaption(text = text, success = false, modifier = modifier)
}

/** Screen-level apply result. A tap clears it. */
@Composable
internal fun DismissibleApplyBanner(banner: ApplyBanner?, onClear: () -> Unit) {
    banner?.let { current ->
        ApplyOutcomeCaption(
            text = bannerLabel(current),
            success = isSuccessBanner(current),
            modifier = Modifier
                .padding(horizontal = PreferenceHubHorizontalPadding)
                .clickable(onClick = onClear),
        )
    }
}

/** Success and failure apply results share this caption so neither interrupts with a Success+OK dialog. */
@Composable
internal fun ApplyOutcomeCaption(text: String, success: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (success) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** True when [banner] reports a success, so the inline caption tints it as such rather than as an error. */
internal fun isSuccessBanner(banner: ApplyBanner): Boolean = when (banner) {
    ApplyBanner.Success, ApplyBanner.RadioApplied, ApplyBanner.AdvertSent, ApplyBanner.TimeSynced,
    ApplyBanner.RegionsSaved, ApplyBanner.PasswordChanged, ApplyBanner.RebootSent,
    -> true
    else -> false
}

@Composable
internal fun bannerLabel(banner: ApplyBanner): String = when (banner) {
    // The node's own words, shown as it said them; a shipped sentence would only be vaguer.
    is ApplyBanner.FirmwareError -> banner.message
    is ApplyBanner.TransportError -> banner.message
    is ApplyBanner.UnexpectedResponse -> formatTemplate(
        stringResource(Res.string.remotenodes_remote_nodes_settings_unexpected_response),
        listOf(banner.response),
    )
    else -> stringResource(
        regionBannerResource(banner) ?: passwordBannerResource(banner) ?: generalBannerResource(banner),
    )
}

private fun regionBannerResource(banner: ApplyBanner): StringResource? = when (banner) {
    ApplyBanner.RegionsSaved -> Res.string.remotenodes_remote_nodes_settings_regions_save_success
    ApplyBanner.RegionsSaveFailed -> Res.string.remotenodes_remote_nodes_settings_regions_save_failed
    ApplyBanner.RegionUnknown -> Res.string.remotenodes_remote_nodes_settings_regions_unknown_region
    ApplyBanner.RegionAddFailed, ApplyBanner.RegionAddValidationFailed ->
        Res.string.remotenodes_remote_nodes_settings_regions_add_failed
    ApplyBanner.RegionRemoveFailed -> Res.string.remotenodes_remote_nodes_settings_regions_remove_failed
    ApplyBanner.RegionNotEmpty -> Res.string.remotenodes_remote_nodes_settings_regions_not_empty
    else -> null
}

private fun passwordBannerResource(banner: ApplyBanner): StringResource? = when (banner) {
    ApplyBanner.PasswordEmpty -> Res.string.remotenodes_remote_nodes_settings_password_empty
    ApplyBanner.PasswordMismatch -> Res.string.remotenodes_remote_nodes_settings_password_mismatch
    ApplyBanner.PasswordChanged -> Res.string.remotenodes_remote_nodes_settings_password_changed_success
    ApplyBanner.PasswordFailed -> Res.string.remotenodes_remote_nodes_settings_password_change_failed
    else -> null
}

private fun generalBannerResource(banner: ApplyBanner): StringResource = when (banner) {
    ApplyBanner.Success -> Res.string.remotenodes_remote_nodes_settings_settings_applied
    ApplyBanner.RadioApplied -> Res.string.remotenodes_remote_nodes_settings_radio_applied_success
    ApplyBanner.PartialFailure -> Res.string.remotenodes_remote_nodes_settings_some_settings_failed_to_apply
    ApplyBanner.NotLoaded -> Res.string.remotenodes_remote_nodes_settings_radio_not_loaded
    ApplyBanner.RebootSent -> Res.string.remotenodes_remote_nodes_settings_reboot_sent
    ApplyBanner.AdvertSent -> Res.string.remotenodes_remote_nodes_settings_advert_sent
    ApplyBanner.TimeSynced -> Res.string.remotenodes_remote_nodes_settings_time_synced
    ApplyBanner.ClockAhead -> Res.string.remotenodes_remote_nodes_settings_clock_ahead_error
    ApplyBanner.SyncTimeFailed -> Res.string.remotenodes_remote_nodes_settings_sync_time_failed
    ApplyBanner.RadioApplyFailed -> Res.string.remotenodes_remote_nodes_settings_radio_apply_failed
    ApplyBanner.NoService -> Res.string.remotenodes_remote_nodes_settings_no_service
    else -> Res.string.localizable_error_settings_send_failed
}
