package one.meshcore.shared.channel

import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.remotenode.isPrivateRegion

/**
 * One row in the channel-info flood-scope picker. [labelKind] tells the UI which localized label to render
 * ([RegionLabelKind.Named] carries the region name verbatim); [scope] is the value written when the row is
 * tapped, or null for an inert row the UI must not let the user pick; [isSelected] marks the current
 * selection.
 */
data class FloodScopePickerItem(val labelKind: RegionLabelKind, val scope: ChannelFloodScope?, val isSelected: Boolean)

/** Which localized label a [FloodScopePickerItem] renders. */
sealed interface RegionLabelKind {
    /** "Use default (name)" — the device default; carries the default region name. */
    data class UseDefault(val defaultName: String) : RegionLabelKind

    /** "All Regions". */
    data object AllRegions : RegionLabelKind

    /** A concrete region, shown by its verbatim name. */
    data class Named(val name: String) : RegionLabelKind
}

/**
 * Builds the channel-info flood-scope picker rows, a pure port of the iOS `ChannelInfoRegionPickerContent`.
 * A non-empty device default adds a "Use default" row (writing [ChannelFloodScope.Inherit]); the "All
 * Regions" row writes [ChannelFloodScope.AllRegions] when a default exists, otherwise [ChannelFloodScope.Inherit]
 * (the two collapse when there is no default, so the conservative `inherit` is written); each public known
 * region adds a [ChannelFloodScope.Region] row.
 *
 * A private ("$"-prefixed) region cannot be a channel's flood scope — the firmware will not flood on it — so
 * private regions follow the public ones as inert rows with a null [FloodScopePickerItem.scope], the same
 * partition iOS renders disabled. Both groups are name-sorted, matching the iOS picker.
 */
object ChannelFloodScopePicker {
    /**
     * The section's current-value label, mirroring the iOS `regionValueLabel`: null means the radio knows
     * no regions at all, which iOS renders as "Not configured". Without it the only way to read a
     * channel's scope is to scan the rows for the selected mark.
     */
    fun currentLabel(
        selected: ChannelFloodScope,
        deviceDefaultFloodScopeName: String?,
        knownRegions: List<String>,
    ): RegionLabelKind? {
        if (knownRegions.isEmpty()) return null
        val defaultName = deviceDefaultFloodScopeName?.takeIf { it.isNotEmpty() }
        return when (selected) {
            ChannelFloodScope.Inherit ->
                if (defaultName != null) RegionLabelKind.UseDefault(defaultName) else RegionLabelKind.AllRegions
            ChannelFloodScope.AllRegions -> RegionLabelKind.AllRegions
            is ChannelFloodScope.Region -> RegionLabelKind.Named(selected.name)
        }
    }

    fun items(
        selected: ChannelFloodScope,
        deviceDefaultFloodScopeName: String?,
        knownRegions: List<String>,
    ): List<FloodScopePickerItem> {
        val defaultName = deviceDefaultFloodScopeName?.takeIf { it.isNotEmpty() }
        val allRegionsScope: ChannelFloodScope =
            if (defaultName != null) ChannelFloodScope.AllRegions else ChannelFloodScope.Inherit

        val items = mutableListOf<FloodScopePickerItem>()
        if (defaultName != null) {
            items += FloodScopePickerItem(
                labelKind = RegionLabelKind.UseDefault(defaultName),
                scope = ChannelFloodScope.Inherit,
                isSelected = selected == ChannelFloodScope.Inherit,
            )
        }
        items += FloodScopePickerItem(
            labelKind = RegionLabelKind.AllRegions,
            scope = allRegionsScope,
            isSelected = selected == allRegionsScope,
        )
        // Sorted before partitioning, as iOS sorts with `localizedStandardCompare`: a picker in device
        // discovery order is unsearchable once a radio knows more than a handful of regions.
        val (privateRegions, publicRegions) = knownRegions.sorted().partition { it.isPrivateRegion() }
        for (region in publicRegions) {
            items += FloodScopePickerItem(
                labelKind = RegionLabelKind.Named(region),
                scope = ChannelFloodScope.Region(region),
                isSelected = selected == ChannelFloodScope.Region(region),
            )
        }
        for (region in privateRegions) {
            items += FloodScopePickerItem(
                labelKind = RegionLabelKind.Named(region),
                scope = null,
                isSelected = false,
            )
        }
        return items
    }
}
