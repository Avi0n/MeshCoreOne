package one.meshcore.shared.channel

import one.meshcore.shared.model.ChannelFloodScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelFloodScopePickerTest {
    @Test
    fun a_device_default_adds_a_use_default_row_and_all_regions_writes_all_regions() {
        val items = ChannelFloodScopePicker.items(
            selected = ChannelFloodScope.Inherit,
            deviceDefaultFloodScopeName = "US",
            knownRegions = listOf("US", "EU"),
        )
        assertEquals(RegionLabelKind.UseDefault("US"), items[0].labelKind)
        assertEquals(ChannelFloodScope.Inherit, items[0].scope)
        assertTrue(items[0].isSelected)

        assertEquals(RegionLabelKind.AllRegions, items[1].labelKind)
        assertEquals(ChannelFloodScope.AllRegions, items[1].scope)

        // Name-sorted, not device order: "EU" precedes "US" however the radio listed them.
        assertEquals(ChannelFloodScope.Region("EU"), items[2].scope)
        assertEquals(ChannelFloodScope.Region("US"), items[3].scope)
    }

    /** iOS sorts the regions before partitioning them; a discovery-order picker is unsearchable. */
    @Test
    fun regions_are_name_sorted_within_each_group() {
        val items = ChannelFloodScopePicker.items(
            selected = ChannelFloodScope.Inherit,
            deviceDefaultFloodScopeName = null,
            knownRegions = listOf("Zulu", "alpha", "Mike", "\$zeta", "\$ops"),
        )

        assertEquals(
            listOf(
                RegionLabelKind.AllRegions,
                RegionLabelKind.Named("Mike"),
                RegionLabelKind.Named("Zulu"),
                RegionLabelKind.Named("alpha"),
                RegionLabelKind.Named("\$ops"),
                RegionLabelKind.Named("\$zeta"),
            ),
            items.map { it.labelKind },
        )
    }

    @Test
    fun private_regions_follow_the_public_ones_and_cannot_be_picked() {
        val items = ChannelFloodScopePicker.items(
            selected = ChannelFloodScope.Inherit,
            deviceDefaultFloodScopeName = null,
            knownRegions = listOf("\$ops", "EU"),
        )

        // A channel cannot scope to a private region, so its row carries no scope to write.
        assertEquals(ChannelFloodScope.Region("EU"), items[1].scope)
        assertEquals(RegionLabelKind.Named("\$ops"), items[2].labelKind)
        assertNull(items[2].scope)
    }

    @Test
    fun without_a_default_all_regions_collapses_to_inherit_and_there_is_no_use_default_row() {
        val items = ChannelFloodScopePicker.items(
            selected = ChannelFloodScope.Inherit,
            deviceDefaultFloodScopeName = "",
            knownRegions = emptyList(),
        )
        assertEquals(RegionLabelKind.AllRegions, items.single().labelKind)
        assertEquals(ChannelFloodScope.Inherit, items.single().scope)
        assertTrue(items.single().isSelected)
    }

    /**
     * The collapsed value label iOS shows on the section header. A radio that knows no regions has no
     * scope to name, which iOS renders as "Not configured".
     */
    @Test
    fun the_current_label_names_the_scope_in_force() {
        assertNull(
            ChannelFloodScopePicker.currentLabel(
                selected = ChannelFloodScope.Inherit,
                deviceDefaultFloodScopeName = null,
                knownRegions = emptyList(),
            ),
        )
        assertEquals(
            RegionLabelKind.UseDefault("US"),
            ChannelFloodScopePicker.currentLabel(
                selected = ChannelFloodScope.Inherit,
                deviceDefaultFloodScopeName = "US",
                knownRegions = listOf("US"),
            ),
        )
        assertEquals(
            RegionLabelKind.AllRegions,
            ChannelFloodScopePicker.currentLabel(
                selected = ChannelFloodScope.Inherit,
                deviceDefaultFloodScopeName = "",
                knownRegions = listOf("US"),
            ),
        )
        assertEquals(
            RegionLabelKind.Named("EU"),
            ChannelFloodScopePicker.currentLabel(
                selected = ChannelFloodScope.Region("EU"),
                deviceDefaultFloodScopeName = "US",
                knownRegions = listOf("US", "EU"),
            ),
        )
    }
}
