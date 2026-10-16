package one.meshcore.shared.pathedit.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.pathedit.AddHopFilter
import one.meshcore.shared.pathedit.HopCodeClassification
import one.meshcore.shared.pathedit.HopCodeStatus
import one.meshcore.shared.pathedit.PathDiscoveryResult
import one.meshcore.shared.pathedit.PathEditError
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_code_input_error_already_in_path
import one.meshcore.shared.resources.contacts_code_input_error_invalid_format
import one.meshcore.shared.resources.contacts_code_input_error_not_found
import one.meshcore.shared.resources.contacts_path_discovery_direct
import one.meshcore.shared.resources.contacts_path_discovery_failed
import one.meshcore.shared.resources.contacts_path_discovery_hops_plural
import one.meshcore.shared.resources.contacts_path_discovery_hops_singular
import one.meshcore.shared.resources.contacts_path_discovery_no_response
import one.meshcore.shared.resources.contacts_path_edit_bulk_add_path_full
import one.meshcore.shared.resources.contacts_path_edit_bulk_add_will_add
import one.meshcore.shared.resources.contacts_path_edit_filter_all
import one.meshcore.shared.resources.contacts_path_edit_filter_discovered
import one.meshcore.shared.resources.contacts_path_edit_filter_favorites
import one.meshcore.shared.resources.contacts_path_edit_filter_recent
import one.meshcore.shared.resources.contacts_path_management_error_hop_resize_required
import one.meshcore.shared.resources.contacts_path_management_error_reset_failed
import one.meshcore.shared.resources.contacts_path_management_error_save_failed
import one.meshcore.shared.resources.contacts_path_management_error_set_failed
import one.meshcore.shared.resources.contacts_path_management_error_too_many_hops
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource

/** The localized segmented-control label for an Add-Hop filter. */
@Composable
internal fun AddHopFilter.label(): String = stringResource(
    when (this) {
        AddHopFilter.ALL -> Res.string.contacts_path_edit_filter_all
        AddHopFilter.FAVORITES -> Res.string.contacts_path_edit_filter_favorites
        AddHopFilter.RECENT -> Res.string.contacts_path_edit_filter_recent
        AddHopFilter.DISCOVERED -> Res.string.contacts_path_edit_filter_discovered
    },
)

/**
 * The localized discovery-result text. A [PathDiscoveryResult.Success] with `hopCount == 0` renders as
 * "Direct" — a genuinely learned 0-hop route, the one place a 0 count and the "Direct" mode label coincide.
 */
@Composable
internal fun PathDiscoveryResult.text(): String = when (this) {
    is PathDiscoveryResult.Success -> when {
        hopCount == 0 -> stringResource(Res.string.contacts_path_discovery_direct)
        hopCount == 1 -> stringResource(Res.string.contacts_path_discovery_hops_singular)
        else -> formattedStringResource(Res.string.contacts_path_discovery_hops_plural, hopCount)
    }
    PathDiscoveryResult.NoPathFound -> stringResource(Res.string.contacts_path_discovery_no_response)
    is PathDiscoveryResult.Failed -> formattedStringResource(Res.string.contacts_path_discovery_failed, message)
}

/** The localized surfaced error for a path-editing failure, mapping the save-guard and service errors. */
@Composable
internal fun PathEditError.text(): String = when (this) {
    PathEditError.HopResizeRequired -> stringResource(Res.string.contacts_path_management_error_hop_resize_required)
    is PathEditError.TooManyHops ->
        formattedStringResource(Res.string.contacts_path_management_error_too_many_hops, maxHopCount)
    is PathEditError.SaveFailed ->
        formattedStringResource(Res.string.contacts_path_management_error_save_failed, message)
    is PathEditError.ResetFailed ->
        formattedStringResource(Res.string.contacts_path_management_error_reset_failed, message)
    is PathEditError.SetFailed ->
        formattedStringResource(Res.string.contacts_path_management_error_set_failed, message)
}

/** The per-code preview text for a bulk-add row, keyed by the code's classified status. */
@Composable
internal fun HopCodeClassification.bulkText(): String = when (status) {
    is HopCodeStatus.WillAdd -> formattedStringResource(Res.string.contacts_path_edit_bulk_add_will_add, code)
    HopCodeStatus.AlreadyInPath -> formattedStringResource(Res.string.contacts_code_input_error_already_in_path, code)
    HopCodeStatus.NotFound -> formattedStringResource(Res.string.contacts_code_input_error_not_found, code)
    HopCodeStatus.InvalidFormat -> formattedStringResource(Res.string.contacts_code_input_error_invalid_format, code)
    HopCodeStatus.PathFull -> formattedStringResource(Res.string.contacts_path_edit_bulk_add_path_full, code)
}
