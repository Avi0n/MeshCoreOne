package one.meshcore.shared.layout

/**
 * The three column arrangements the adaptive shell can show, mirroring SwiftUI's
 * `NavigationSplitViewVisibility` cases the iOS shell actually uses:
 *
 * - [Tiled] — sidebar tiled open alongside content and detail (Swift `.all`).
 * - [ContentDetail] — sidebar hidden, content + detail shown; the collapsed shape of a three-column
 *   section (Swift `.doubleColumn`).
 * - [DetailOnly] — only the detail pane; the collapsed shape of the two-column Map section
 *   (Swift `.detailOnly`).
 *
 * A single value cannot collapse both the three-column and the two-column shapes, which is why the
 * collapsed shape is chosen per section (see `AppTab.collapsedSidebarVisibility`).
 */
enum class SidebarVisibility {
    Tiled,
    ContentDetail,
    DetailOnly,
}
