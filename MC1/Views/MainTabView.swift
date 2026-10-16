import SwiftUI

/// Stable app tab host after onboarding. Chats, Nodes, and Settings own
/// native splits; Tools owns a stack bound to `selectedTool`.
struct MainTabView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @State private var showingDeviceSelection = false

  var body: some View {
    @Bindable var navigation = appState.navigation

    TabView(selection: $navigation.selectedTab) {
      Tab(L10n.Localizable.Tabs.chats, systemImage: "message.fill", value: AppTab.chats.rawValue) {
        ChatsView()
      }
      .badge(appState.services?.notificationService.badgeCount ?? 0)

      Tab(L10n.Localizable.Tabs.nodes, systemImage: "flipphone", value: AppTab.nodes.rawValue) {
        ContactsListView()
      }

      Tab(L10n.Localizable.Tabs.map, systemImage: "map.fill", value: AppTab.map.rawValue) {
        MapView()
      }

      Tab(L10n.Localizable.Tabs.tools, systemImage: "wrench.and.screwdriver", value: AppTab.tools.rawValue) {
        ToolsView()
      }

      Tab(L10n.Localizable.Tabs.settings, systemImage: "gear", value: AppTab.settings.rawValue) {
        SettingsView()
      }
    }
    .themedChrome(theme)
    .syncingPillOverlay(onDisconnectedTap: { showingDeviceSelection = true })
    .onChange(of: appState.navigation.selectedTab) { _, _ in
      if appState.navigation.pendingDeviceMenuTipDonation, appState.navigation.isOnValidTabForDeviceMenuTip {
        Task {
          await appState.donateDeviceMenuTipIfOnValidTab()
        }
      }
    }
    .onChange(of: appState.connectedDevice) { _, newDevice in
      // Status-menu disconnect does not fire onConnectionLost; radio-to-radio
      // switches use clearPerRadioSelection instead.
      if newDevice == nil {
        appState.navigation.clearPerDeviceSelection()
      }
    }
    .sheet(isPresented: $showingDeviceSelection, onDismiss: {
      appState.handleDeviceSelectionSheetDismissed()
    }) {
      DeviceSelectionSheet()
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
  }
}
