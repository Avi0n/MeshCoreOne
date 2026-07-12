@testable import MC1
import SwiftUI
import Testing

@Suite("SidebarNavigationLayout")
struct SidebarNavigationLayoutTests {
  /// Point width of an 11-inch iPad in portrait. The narrowest device we promise
  /// three-column tiling for (iPad mini at 744pt intentionally collapses, matching Mail).
  static let iPad11InchPortraitWidth: CGFloat = 834

  /// Point width of an iPad mini in portrait. Intentionally below the tiling breakpoint
  /// so it collapses to the section's hidden shape rather than tiling three columns.
  static let iPadMiniPortraitWidth: CGFloat = 744

  /// Upper bound for an icon-only sidebar width; a full text sidebar would exceed this.
  static let iconSidebarUpperBound: CGFloat = 115

  @Test
  func `Section sidebar is narrow enough that 11-inch portrait tiles all three columns`() {
    #expect(MainSidebarView.sidebarTileableMinWidth <= Self.iPad11InchPortraitWidth)
  }

  @Test
  func `iPad mini portrait intentionally collapses rather than tiling`() {
    #expect(MainSidebarView.sidebarTileableMinWidth > Self.iPadMiniPortraitWidth)
  }

  @Test
  func `Sidebar width is in icon-only range, not a full sidebar`() {
    // Guards the configured constant. That iPadOS actually renders the sidebar this narrow and
    // tiles three columns is validated by manual layout testing on device, not asserted here.
    #expect(MainSidebarView.sidebarColumnWidth <= Self.iconSidebarUpperBound)
  }

  @Test
  func `A sidebar-collapsing tool collapses the sidebar even when the container is wide`() {
    let visibility = MainSidebarView.sidebarVisibility(
      isWide: true,
      toolCollapsesSidebar: true,
      sectionCollapsed: .doubleColumn
    )
    #expect(visibility == .doubleColumn)
  }

  @Test
  func `A wide container tiles the sidebar when no sidebar-collapsing tool is open`() {
    let visibility = MainSidebarView.sidebarVisibility(
      isWide: true,
      toolCollapsesSidebar: false,
      sectionCollapsed: .doubleColumn
    )
    #expect(visibility == .all)
  }

  @Test
  func `A narrow container collapses to the section's hidden shape`() {
    #expect(
      MainSidebarView.sidebarVisibility(
        isWide: false, toolCollapsesSidebar: false, sectionCollapsed: .doubleColumn
      ) == .doubleColumn
    )
    #expect(
      MainSidebarView.sidebarVisibility(
        isWide: false, toolCollapsesSidebar: false, sectionCollapsed: .detailOnly
      ) == .detailOnly
    )
  }

  @Test
  func `Line of Sight and Trace Path collapse the sidebar; other tools keep it`() {
    #expect(ToolSelection.lineOfSight.prefersCollapsedSidebar)
    #expect(ToolSelection.tracePath.prefersCollapsedSidebar)
    for tool in ToolSelection.allCases where tool != .lineOfSight && tool != .tracePath {
      #expect(!tool.prefersCollapsedSidebar)
    }
  }
}
