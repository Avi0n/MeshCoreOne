import MC1Services
import SwiftUI

/// Main settings screen for the compact (iPhone) tab shell — navigation-link rows for device
/// settings plus always-visible app settings. The iPad regular-width layout renders settings
/// through `MainSidebarView` → `SettingsListContent`, never this view.
struct SettingsView: View {
  @Environment(\.appState) private var appState
  @State private var showingDeviceSelection = false
  @State private var navigationPath = NavigationPath()
  private var demoModeManager = DemoModeManager.shared

  var body: some View {
    NavigationStack(path: $navigationPath) {
      SettingsListContent(
        showingDeviceSelection: $showingDeviceSelection,
        demoModeManager: demoModeManager,
        isSidebar: false
      )
      .navigationDestination(for: SettingsDetail.self) { detail in
        SettingsDetailView(detail: detail)
      }
    }
    .onChange(of: appState.navigation.selectedSetting) { _, detail in
      applySelectedSetting(detail)
    }
    .onChange(of: navigationPath.count) { _, count in
      // User popped back to the root; clear the programmatic selection so a later
      // tab revisit does not re-push the same detail.
      if count == 0, appState.navigation.selectedSetting != nil {
        appState.navigation.selectedSetting = nil
      }
    }
    .onAppear {
      applySelectedSetting(appState.navigation.selectedSetting)
    }
  }

  /// Replaces the compact stack with a single push of `detail`, or no-ops when nil.
  private func applySelectedSetting(_ detail: SettingsDetail?) {
    guard let detail else { return }
    var path = NavigationPath()
    path.append(detail)
    navigationPath = path
  }
}

#Preview {
  SettingsView()
    .environment(\.appState, AppState())
}
