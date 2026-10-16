package one.meshcore.shared.map.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.map.MapDuration
import one.meshcore.shared.map.MapDurationUnit
import one.meshcore.shared.map.MapHopRange
import one.meshcore.shared.map.MapLastHeardRange
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_relative_age_days
import one.meshcore.shared.resources.android_relative_age_hours
import one.meshcore.shared.resources.android_relative_age_minutes
import one.meshcore.shared.resources.map_filters_hops_any
import one.meshcore.shared.resources.map_filters_hops_chip_at_least
import one.meshcore.shared.resources.map_filters_hops_chip_at_most
import one.meshcore.shared.resources.map_filters_hops_chip_between
import one.meshcore.shared.resources.map_filters_hops_chip_direct
import one.meshcore.shared.resources.map_filters_hops_chip_exactly
import one.meshcore.shared.resources.map_filters_hops_direct
import one.meshcore.shared.resources.map_filters_hops_summary_any
import one.meshcore.shared.resources.map_filters_hops_summary_at_least
import one.meshcore.shared.resources.map_filters_hops_summary_at_most
import one.meshcore.shared.resources.map_filters_hops_summary_between
import one.meshcore.shared.resources.map_filters_hops_summary_direct
import one.meshcore.shared.resources.map_filters_hops_summary_exactly
import one.meshcore.shared.resources.map_filters_hops_up_to
import one.meshcore.shared.resources.map_filters_last_heard_any_time
import one.meshcore.shared.resources.map_filters_last_heard_chip_between
import one.meshcore.shared.resources.map_filters_last_heard_chip_older_than
import one.meshcore.shared.resources.map_filters_last_heard_chip_within
import one.meshcore.shared.resources.map_filters_last_heard_summary_any
import one.meshcore.shared.resources.map_filters_last_heard_summary_between
import one.meshcore.shared.resources.map_filters_last_heard_summary_older_than
import one.meshcore.shared.resources.map_filters_last_heard_summary_within
import one.meshcore.shared.resources.map_filters_last_heard_unit_days
import one.meshcore.shared.resources.map_filters_last_heard_unit_hours
import one.meshcore.shared.resources.map_filters_last_heard_unit_minutes
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MapDurationUnit.label(): String = stringResource(
    when (this) {
        MapDurationUnit.MINUTES -> Res.string.map_filters_last_heard_unit_minutes
        MapDurationUnit.HOURS -> Res.string.map_filters_last_heard_unit_hours
        MapDurationUnit.DAYS -> Res.string.map_filters_last_heard_unit_days
    },
)

@Composable
internal fun MapDuration.compactLabel(): String = formattedStringResource(
    when (unit) {
        MapDurationUnit.MINUTES -> Res.string.android_relative_age_minutes
        MapDurationUnit.HOURS -> Res.string.android_relative_age_hours
        MapDurationUnit.DAYS -> Res.string.android_relative_age_days
    },
    value,
)

@Composable
internal fun MapLastHeardRange.label(summary: Boolean = false): String {
    val lower = minAge
    val upper = maxAge
    return when {
        lower != null && upper != null -> formattedStringResource(
            if (summary) {
                Res.string.map_filters_last_heard_summary_between
            } else {
                Res.string.map_filters_last_heard_chip_between
            },
            lower.compactLabel(),
            upper.compactLabel(),
        )
        lower != null -> formattedStringResource(
            if (summary) {
                Res.string.map_filters_last_heard_summary_older_than
            } else {
                Res.string.map_filters_last_heard_chip_older_than
            },
            lower.compactLabel(),
        )
        upper != null -> formattedStringResource(
            if (summary) {
                Res.string.map_filters_last_heard_summary_within
            } else {
                Res.string.map_filters_last_heard_chip_within
            },
            upper.compactLabel(),
        )
        else -> stringResource(
            if (summary) Res.string.map_filters_last_heard_summary_any else Res.string.map_filters_last_heard_any_time,
        )
    }
}

@Composable
internal fun MapHopRange.label(summary: Boolean = false): String {
    val upper = max
    return when {
        this == MapHopRange.DIRECT -> stringResource(
            if (summary) Res.string.map_filters_hops_summary_direct else Res.string.map_filters_hops_chip_direct,
        )
        upper == min -> formattedStringResource(
            if (summary) Res.string.map_filters_hops_summary_exactly else Res.string.map_filters_hops_chip_exactly,
            min,
        )
        min == MapHopRange.HOP_DOMAIN.first && upper != null -> formattedStringResource(
            if (summary) Res.string.map_filters_hops_summary_at_most else Res.string.map_filters_hops_chip_at_most,
            upper,
        )
        upper != null -> formattedStringResource(
            if (summary) Res.string.map_filters_hops_summary_between else Res.string.map_filters_hops_chip_between,
            min,
            upper,
        )
        isActive -> formattedStringResource(
            if (summary) Res.string.map_filters_hops_summary_at_least else Res.string.map_filters_hops_chip_at_least,
            min,
        )
        else -> stringResource(
            if (summary) Res.string.map_filters_hops_summary_any else Res.string.map_filters_hops_any,
        )
    }
}

@Composable
internal fun MapHopRange.presetLabel(): String = when {
    this == MapHopRange.DIRECT -> stringResource(Res.string.map_filters_hops_direct)
    max != null -> formattedStringResource(Res.string.map_filters_hops_up_to, requireNotNull(max))
    else -> stringResource(Res.string.map_filters_hops_any)
}
