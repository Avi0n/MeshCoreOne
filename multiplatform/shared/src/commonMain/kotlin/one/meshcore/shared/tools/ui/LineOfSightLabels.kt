package one.meshcore.shared.tools.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_error_api_error
import one.meshcore.shared.resources.localizable_common_error_invalid_response
import one.meshcore.shared.resources.localizable_common_error_network_error
import one.meshcore.shared.resources.localizable_common_error_no_elevation_data
import one.meshcore.shared.resources.localizable_common_error_rate_limited
import one.meshcore.shared.tools.LosAnalysisFailure
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The resource for a failure whose text takes no argument, or `null` for the two that carry a detail
 * and for [LosAnalysisFailure.Unknown]. Pure so the mapping is asserted without a composition.
 */
fun LosAnalysisFailure.messageResource(): StringResource? = when (this) {
    LosAnalysisFailure.InvalidResponse -> Res.string.localizable_common_error_invalid_response
    LosAnalysisFailure.NoData -> Res.string.localizable_common_error_no_elevation_data
    LosAnalysisFailure.RateLimited -> Res.string.localizable_common_error_rate_limited
    is LosAnalysisFailure.NetworkError, is LosAnalysisFailure.ApiError, is LosAnalysisFailure.Unknown -> null
}

/** The reason an analysis produced no clearance verdict, matching the iOS error text case for case. */
@Composable
fun LosAnalysisFailure.message(): String = when (this) {
    is LosAnalysisFailure.NetworkError ->
        formattedStringResource(Res.string.localizable_common_error_network_error, reason)
    is LosAnalysisFailure.ApiError -> formattedStringResource(Res.string.localizable_common_error_api_error, detail)
    is LosAnalysisFailure.Unknown -> detail
    // Every remaining case is exhaustive in messageResource, so the resource is always present here.
    else -> stringResource(checkNotNull(messageResource()))
}
