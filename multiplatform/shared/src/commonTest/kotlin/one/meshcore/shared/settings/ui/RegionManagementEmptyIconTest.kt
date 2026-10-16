package one.meshcore.shared.settings.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegionManagementEmptyIconTest {
    @Test
    fun emptyUsesMapGlyph() {
        assertEquals(AppIcons.Map, REGION_MANAGEMENT_EMPTY_ICON)
    }

    @Test
    fun settingsHubDiscoverCoversHubChrome() {
        assertTrue(regionDiscoveryCoversHubChrome(parentShowsAppBar = false))
    }

    @Test
    fun overlayDiscoverReplacesTheParentWithoutADialog() {
        assertFalse(regionDiscoveryCoversHubChrome(parentShowsAppBar = true))
    }

    @Test
    fun discoveringUsesAListItemTrailingSpinner() {
        assertEquals(20, REGION_DISCOVERING_PROGRESS_SIZE.value.toInt())
    }

    @Test
    fun searchAppearsAtFifteenRegions() {
        assertFalse(regionManagementSearchEnabled(14))
        assertTrue(regionManagementSearchEnabled(15))
    }

    @Test
    fun filterKeepsMatchingNamesIgnoreCase() {
        val regions = listOf("Bay Area", "EU", "Private")
        assertEquals(listOf("Bay Area"), filteredKnownRegions(regions, "bay"))
        assertEquals(regions, filteredKnownRegions(regions, "  "))
    }

    @Test
    fun aPersistedRegionImmediatelyAppearsInTheDisplayedList() {
        assertEquals(
            listOf("Bay Area", "EU"),
            regionManagementRegionsAfterAdd(listOf("EU"), "Bay Area"),
        )
        assertEquals(
            listOf("EU"),
            regionManagementRegionsAfterAdd(listOf("EU"), "EU"),
        )
    }

    @Test
    fun aRemovedRegionImmediatelyLeavesTheDisplayedList() {
        assertEquals(
            listOf("EU"),
            regionManagementRegionsAfterRemove(listOf("Bay Area", "EU"), "Bay Area"),
        )
    }
}
