package one.meshcore.shared.layout

/**
 * Pure tiling logic for the tablet/desktop list-detail shell, ported verbatim from the iOS
 * `MainSidebarView` static geometry helpers.
 *
 * The iOS shell cannot trust a column's reported size class — on iPad the content column reports
 * `.compact` even on a regular-width device — so the tile-or-collapse decision is derived from the
 * container width measured at the shell against [SIDEBAR_TILEABLE_MIN_WIDTH_DP]. Compose has the same
 * hazard, so the Android shell must likewise branch on a measured width, not a window size class alone.
 *
 * Widths are in density-independent units; iOS points map to Android dp 1:1 by project convention. They
 * are validated on device, not asserted here.
 */
object SidebarLayout {
    /**
     * Pinned navigation-rail width. Both the two-column (Map) and three-column shells pin it so the
     * sidebar does not visibly jump width when the section changes. Held at the Material icon-only rail
     * width; item labels are omitted (icon plus content description) since five tab names don't all fit
     * on one line at this width in every locale.
     */
    const val SIDEBAR_COLUMN_WIDTH_DP: Int = 80

    /** Approximate detail-column floor, validated on device. */
    const val DETAIL_COLUMN_APPROX_MIN_WIDTH_DP: Int = 320

    /**
     * Widest content-column minimum across sections. Its own constant, not a reuse of the Line of Sight
     * panel width, so retuning that panel cannot shift when the sidebar tiles.
     */
    const val CONTENT_COLUMN_TILEABLE_MIN_WIDTH_DP: Int = 380

    /**
     * Tile all three columns only when the container is at least this wide — kept as the computed sum of
     * the three column minimums (matching the Swift), so widening any column moves the breakpoint with
     * it. 80 + 380 + 320 = 780.
     */
    const val SIDEBAR_TILEABLE_MIN_WIDTH_DP: Int =
        SIDEBAR_COLUMN_WIDTH_DP + CONTENT_COLUMN_TILEABLE_MIN_WIDTH_DP + DETAIL_COLUMN_APPROX_MIN_WIDTH_DP

    /**
     * Material medium floor. Below this the phone [NavigationBar] is the destination control; at and
     * above it the shell uses a [NavigationRail]. Three-column tiling still waits for
     * [SIDEBAR_TILEABLE_MIN_WIDTH_DP] — medium windows keep compact push navigation beside the rail.
     */
    const val MATERIAL_MEDIUM_MIN_WIDTH_DP: Int = 600

    /** The Tools content column widens to these bounds only while the Line of Sight panel is open. */
    const val LINE_OF_SIGHT_PANEL_WIDTH_MIN_DP: Int = 380
    const val LINE_OF_SIGHT_PANEL_WIDTH_IDEAL_DP: Int = 440
    const val LINE_OF_SIGHT_PANEL_WIDTH_MAX_DP: Int = 560

    /** Whether the measured container is wide enough to tile all three columns. */
    fun isSidebarWide(containerWidthDp: Float): Boolean = containerWidthDp >= SIDEBAR_TILEABLE_MIN_WIDTH_DP

    /** Whether the measured container is at least the Material medium floor. */
    fun isMaterialMedium(containerWidthDp: Float): Boolean = containerWidthDp >= MATERIAL_MEDIUM_MIN_WIDTH_DP

    /**
     * The column arrangement to show. A tool that prefers a collapsed sidebar (Line of Sight, Trace
     * Path) overrides width and collapses to the section's hidden shape; otherwise a wide container
     * tiles the sidebar and a narrow one collapses to the section's hidden shape.
     */
    fun sidebarVisibility(
        isWide: Boolean,
        toolCollapsesSidebar: Boolean,
        sectionCollapsed: SidebarVisibility,
    ): SidebarVisibility {
        if (toolCollapsesSidebar) return sectionCollapsed
        return if (isWide) SidebarVisibility.Tiled else sectionCollapsed
    }
}
