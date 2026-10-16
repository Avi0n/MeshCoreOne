package one.meshcore.shared.layout

import one.meshcore.shared.navigation.ToolSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mirrors the iOS `SidebarNavigationLayoutTests`. */
class SidebarLayoutTest {
    private companion object {
        /** 11-inch iPad portrait: the narrowest device we promise three-column tiling for. */
        const val IPAD_11_INCH_PORTRAIT_WIDTH = 834f

        /** iPad mini portrait: intentionally below the breakpoint, so it collapses rather than tiling. */
        const val IPAD_MINI_PORTRAIT_WIDTH = 744f

        /** Upper bound for an icon-only sidebar; a full text sidebar would exceed this. */
        const val ICON_SIDEBAR_UPPER_BOUND = 115
    }

    @Test
    fun sectionSidebarIsNarrowEnoughThatElevenInchPortraitTilesAllThreeColumns() {
        assertTrue(SidebarLayout.SIDEBAR_TILEABLE_MIN_WIDTH_DP <= IPAD_11_INCH_PORTRAIT_WIDTH)
    }

    @Test
    fun iPadMiniPortraitIntentionallyCollapsesRatherThanTiling() {
        assertTrue(SidebarLayout.SIDEBAR_TILEABLE_MIN_WIDTH_DP > IPAD_MINI_PORTRAIT_WIDTH)
    }

    @Test
    fun sidebarWidthIsInIconOnlyRangeNotAFullSidebar() {
        assertTrue(SidebarLayout.SIDEBAR_COLUMN_WIDTH_DP <= ICON_SIDEBAR_UPPER_BOUND)
    }

    @Test
    fun theTileableMinWidthIsTheSumOfTheThreeColumnMinimums() {
        assertEquals(780, SidebarLayout.SIDEBAR_TILEABLE_MIN_WIDTH_DP)
    }

    @Test
    fun isSidebarWideBranchesAtTheBreakpoint() {
        assertTrue(SidebarLayout.isSidebarWide(IPAD_11_INCH_PORTRAIT_WIDTH))
        assertFalse(SidebarLayout.isSidebarWide(IPAD_MINI_PORTRAIT_WIDTH))
        assertTrue(SidebarLayout.isSidebarWide(780f))
        assertFalse(SidebarLayout.isSidebarWide(779f))
    }

    @Test
    fun navigationRailStartsAtMaterialMedium() {
        assertFalse(SidebarLayout.isMaterialMedium(599f))
        assertTrue(SidebarLayout.isMaterialMedium(600f))
        assertTrue(SidebarLayout.isMaterialMedium(IPAD_MINI_PORTRAIT_WIDTH))
        assertFalse(SidebarLayout.isSidebarWide(600f))
    }

    @Test
    fun aSidebarCollapsingToolCollapsesTheSidebarEvenWhenTheContainerIsWide() {
        val visibility = SidebarLayout.sidebarVisibility(
            isWide = true,
            toolCollapsesSidebar = true,
            sectionCollapsed = SidebarVisibility.ContentDetail,
        )
        assertEquals(SidebarVisibility.ContentDetail, visibility)
    }

    @Test
    fun aWideContainerTilesTheSidebarWhenNoSidebarCollapsingToolIsOpen() {
        val visibility = SidebarLayout.sidebarVisibility(
            isWide = true,
            toolCollapsesSidebar = false,
            sectionCollapsed = SidebarVisibility.ContentDetail,
        )
        assertEquals(SidebarVisibility.Tiled, visibility)
    }

    @Test
    fun aNarrowContainerCollapsesToTheSectionsHiddenShape() {
        assertEquals(
            SidebarVisibility.ContentDetail,
            SidebarLayout.sidebarVisibility(false, toolCollapsesSidebar = false, SidebarVisibility.ContentDetail),
        )
        assertEquals(
            SidebarVisibility.DetailOnly,
            SidebarLayout.sidebarVisibility(false, toolCollapsesSidebar = false, SidebarVisibility.DetailOnly),
        )
    }

    @Test
    fun lineOfSightAndTracePathCollapseTheSidebarOtherToolsKeepIt() {
        assertTrue(ToolSelection.LINE_OF_SIGHT.prefersCollapsedSidebar)
        assertTrue(ToolSelection.TRACE_PATH.prefersCollapsedSidebar)
        val otherTools = ToolSelection.entries.filter {
            it != ToolSelection.LINE_OF_SIGHT && it != ToolSelection.TRACE_PATH
        }
        for (tool in otherTools) {
            assertFalse(tool.prefersCollapsedSidebar)
        }
    }
}
