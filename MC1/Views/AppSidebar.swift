import MC1Services
import SwiftUI

/// The sidebar column of the iPad three-column layout: the five `AppTab` rows, bridging
/// row selection to `NavigationCoordinator.selectedTab`. The radio control lives in each
/// section's own toolbar, since the icon-only sidebar is too narrow to host it.
struct AppSidebar: View {
  @Environment(\.appState) private var appState

  @Binding var columnVisibility: NavigationSplitViewVisibility

  /// List single-selection requires an optional binding; a nil selection falls back to chats.
  /// Selecting a row collapses the sidebar (Mail-style) in the same write as the tab change, so
  /// the section rebuilds once with the right visibility for its column count — collapsing
  /// reactively after the tab change instead would render one frame at the wrong shape (the map
  /// section flashing its sidebar, a list section briefly missing its content column). The collapse
  /// is gated on `isSidebarWide`.
  private var selection: Binding<AppTab?> {
    Binding(
      get: { AppTab(rawValue: appState.navigation.selectedTab) ?? .chats },
      set: { newValue in
        let tab = newValue ?? .chats
        appState.navigation.selectedTab = tab.rawValue
        if !appState.navigation.isSidebarWide {
          columnVisibility = tab.collapsedSidebarVisibility
        }
      }
    )
  }

  private var unreadCount: Int {
    appState.services?.notificationService.badgeCount ?? 0
  }

  var body: some View {
    List(selection: selection) {
      // A row `.badge` reserves a trailing accessory slot that leading-aligns this icon-only
      // label, shifting the glyph off the column's centerline that every other row sits on.
      // The unread count is overlaid on the centered icon instead, leaving the glyph centered.
      Label(L10n.Localizable.Tabs.chats, systemImage: "message.fill")
        .overlay(alignment: .topTrailing) { unreadBadge }
        .accessibilityValue(unreadCount > 0
          ? L10n.Localizable.Tabs.chatsUnreadAccessibilityValue(unreadCount)
          : "")
        .tag(AppTab.chats)

      Label(L10n.Localizable.Tabs.nodes, systemImage: "flipphone")
        .tag(AppTab.nodes)

      Label(L10n.Localizable.Tabs.map, systemImage: "map.fill")
        .tag(AppTab.map)

      Label(L10n.Localizable.Tabs.tools, systemImage: "wrench.and.screwdriver")
        .tag(AppTab.tools)

      Label(L10n.Localizable.Tabs.settings, systemImage: "gear")
        .tag(AppTab.settings)
    }
    // Icon-only sidebar: each Label's title is hidden visually but stays its row's VoiceOver
    // label, so the titles are load-bearing for accessibility and must remain on every row.
    .labelStyle(.iconOnly)
  }

  @ViewBuilder
  private var unreadBadge: some View {
    if unreadCount > 0 {
      Text(unreadCount > Self.unreadBadgeOverflowThreshold
        ? L10n.Chats.Chats.ScrollButton.Badge.overflow
        : "\(unreadCount)")
        .font(.caption2.bold())
        .foregroundStyle(.white)
        .padding(.horizontal, 6)
        .padding(.vertical, 2)
        .background(.red, in: .capsule)
        .fixedSize()
        // The sidebar renders row content through a vibrancy effect that blends the capsule
        // fill with the background; flattening to one layer keeps the red fully opaque.
        .drawingGroup()
        .offset(x: 8, y: -8)
    }
  }

  private static let unreadBadgeOverflowThreshold = 99
}

extension AppTab {
  /// The column visibility that hides the app sidebar for this section's split shape: a
  /// two-column split (Map) hides its sidebar with `.detailOnly`, while a three-column split
  /// (the list sections) hides the sidebar but keeps content + detail with `.doubleColumn`.
  /// A single shared visibility value cannot collapse both shapes, so it is chosen per section.
  var collapsedSidebarVisibility: NavigationSplitViewVisibility {
    self == .map ? .detailOnly : .doubleColumn
  }
}

#Preview {
  NavigationSplitView {
    AppSidebar(columnVisibility: .constant(.all))
      .environment(\.appState, AppState())
  } detail: {
    Text(verbatim: "Detail")
  }
}
