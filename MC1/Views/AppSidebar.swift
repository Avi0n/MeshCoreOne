import SwiftUI
import MC1Services

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

    var body: some View {
        List(selection: selection) {
            Label(L10n.Localizable.Tabs.chats, systemImage: "message.fill")
                .badge(appState.services?.notificationService.badgeCount ?? 0)
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
