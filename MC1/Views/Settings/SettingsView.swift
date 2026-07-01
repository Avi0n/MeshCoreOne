import MC1Services
import SwiftUI

/// Main settings screen for the compact (iPhone) tab shell — navigation-link rows for device
/// settings plus always-visible app settings. The iPad regular-width layout renders settings
/// through `MainSidebarView` → `SettingsListContent`, never this view.
struct SettingsView: View {
  @State private var showingDeviceSelection = false
  private var demoModeManager = DemoModeManager.shared

  var body: some View {
    NavigationStack {
      SettingsListContent(
        showingDeviceSelection: $showingDeviceSelection,
        demoModeManager: demoModeManager,
        isSidebar: false
      )
      .navigationDestination(for: SettingsDetail.self) { detail in
        SettingsDetailView(detail: detail)
      }
    }
  }
}

#Preview {
  SettingsView()
    .environment(\.appState, AppState())
}
