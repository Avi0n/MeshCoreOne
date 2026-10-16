package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_results_distance_error
import one.meshcore.shared.resources.contacts_results_distance_info_title
import one.meshcore.shared.resources.contacts_results_distance_info_title_partial
import one.meshcore.shared.resources.contacts_results_full_path_header
import one.meshcore.shared.resources.contacts_results_full_path_tip
import one.meshcore.shared.resources.contacts_results_missing_locations
import one.meshcore.shared.resources.contacts_results_needs_repeaters
import one.meshcore.shared.resources.contacts_results_partial_distance_explanation
import one.meshcore.shared.resources.contacts_results_partial_distance_header
import one.meshcore.shared.resources.contacts_results_repeaters_without_locations
import one.meshcore.shared.trace.TraceResult
import one.meshcore.shared.trace.repeatersWithoutLocation
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubBulletRow
import one.meshcore.shared.ui.PreferenceHubBulletRowGap
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubStatusRow
import one.meshcore.shared.ui.preferenceHubInsetScroll
import org.jetbrains.compose.resources.stringResource

/** Explains why the distance is partial or unavailable. Mirrors iOS `DistanceInfoSheetView`. */
@Composable
internal fun DistanceInfoDialog(result: TraceResult, isFallback: Boolean, onDismiss: () -> Unit) {
    val intermediateCount = result.hops.count { !it.isStartNode && !it.isEndNode }
    val missing = remember(result) { repeatersWithoutLocation(result.hops) }
    val title = if (isFallback) {
        stringResource(Res.string.contacts_results_distance_info_title_partial)
    } else {
        stringResource(Res.string.contacts_results_distance_info_title)
    }
    FullScreenOverlay(
        onDismiss = onDismiss,
        title = title,
    ) { innerPadding ->
        Column(modifier = Modifier.preferenceHubInsetScroll(innerPadding)) {
            when {
                isFallback -> {
                    PreferenceHubSection(
                        title = stringResource(Res.string.contacts_results_partial_distance_header),
                    ) {
                        Text(
                            stringResource(Res.string.contacts_results_partial_distance_explanation),
                            modifier = Modifier.padding(PreferenceHubCardPadding),
                        )
                    }
                    PreferenceHubSection(
                        title = stringResource(Res.string.contacts_results_full_path_header),
                    ) {
                        Text(
                            stringResource(Res.string.contacts_results_full_path_tip),
                            modifier = Modifier.padding(PreferenceHubCardPadding),
                        )
                    }
                }

                intermediateCount < MIN_REPEATERS_FOR_DISTANCE -> {
                    PreferenceHubSection(applyHorizontalPadding = true) {
                        PreferenceHubStatusRow(
                            stringResource(Res.string.contacts_results_needs_repeaters),
                        )
                    }
                }

                missing.isEmpty() -> {
                    PreferenceHubSection(applyHorizontalPadding = true) {
                        PreferenceHubStatusRow(
                            stringResource(Res.string.contacts_results_distance_error),
                        )
                    }
                }

                else -> {
                    PreferenceHubSection(applyHorizontalPadding = true) {
                        PreferenceHubStatusRow(
                            stringResource(Res.string.contacts_results_missing_locations),
                        )
                    }
                    PreferenceHubSection(
                        title = stringResource(Res.string.contacts_results_repeaters_without_locations),
                    ) {
                        Column(
                            Modifier.padding(PreferenceHubCardPadding),
                            verticalArrangement = Arrangement.spacedBy(PreferenceHubBulletRowGap),
                        ) {
                            missing.forEach { PreferenceHubBulletRow(it) }
                        }
                    }
                }
            }
        }
    }
}

private const val MIN_REPEATERS_FOR_DISTANCE = 2
