import MC1Services
import SwiftUI

/// Settings tab host. The list selection is `selectedSetting` at every width;
/// nested subpages live on the detail stack.
struct SettingsView: View {
  @Environment(\.appState) private var appState
  @Environment(\.horizontalSizeClass) private var sizeClass

  @State private var showingDeviceSelection = false
  @State private var columnVisibility = NavigationSplitViewVisibility.all
  @State private var preferredCompactColumn = NavigationSplitViewColumn.sidebar
  @State private var nestedPath = NavigationPath()
  private var demoModeManager = DemoModeManager.shared

  private var tabBarVisibility: Visibility {
    ChatsSplitPresentation.tabBarVisibility(
      sizeClass: sizeClass,
      preferredColumn: preferredCompactColumn,
      hasSelection: appState.navigation.selectedSetting != nil
    )
  }

  var body: some View {
    NavigationSplitView(
      columnVisibility: $columnVisibility,
      preferredCompactColumn: $preferredCompactColumn
    ) {
      SettingsListContent(
        showingDeviceSelection: $showingDeviceSelection,
        demoModeManager: demoModeManager,
        isSidebar: sizeClass == .regular
      )
      .sectionSplitColumnChrome()
    } detail: {
      NavigationStack(path: $nestedPath) {
        Group {
          if let setting = appState.navigation.selectedSetting {
            SettingsDetailView(detail: setting)
          } else {
            ContentUnavailableView(L10n.Settings.selectSetting, systemImage: "gear")
          }
        }
        .sectionSplitColumnChrome()
      }
      .id(appState.navigation.selectedSetting)
    }
    .navigationSplitViewStyle(.balanced)
    .sectionSplitChrome(tabBarVisibility: tabBarVisibility)
    .onChange(of: sizeClass) { old, new in
      applySizeClassChange(from: old, to: new)
    }
    .onChange(of: preferredCompactColumn) { _, _ in
      applyPreferredColumnRecipe()
    }
    .onChange(of: nestedPath.count) { _, _ in
      applyPreferredColumnRecipe()
    }
    .onChange(of: appState.navigation.selectedSetting) { _, newSetting in
      nestedPath = NavigationPath()
      if newSetting != nil {
        if sizeClass == .compact {
          preferredCompactColumn = .detail
        }
      } else if sizeClass == .compact {
        preferredCompactColumn = .sidebar
      }
    }
    .onChange(of: appState.navigation.settingsRootNavigationGeneration) { _, _ in
      nestedPath = NavigationPath()
    }
    .task {
      if appState.navigation.selectedSetting != nil, sizeClass == .compact {
        preferredCompactColumn = .detail
      }
    }
  }

  private func applySizeClassChange(
    from old: UserInterfaceSizeClass?,
    to new: UserInterfaceSizeClass?
  ) {
    let presentation = ChatsSplitPresentation.presentationForSizeClassChange(
      from: old,
      to: new,
      hasSelection: appState.navigation.selectedSetting != nil
    )
    if let visibility = presentation.columnVisibility {
      columnVisibility = visibility
    }
    if let column = presentation.preferredColumn {
      preferredCompactColumn = column
    }
  }

  private func applyPreferredColumnRecipe() {
    switch ChatsSplitPresentation.preferredColumnAction(
      preferredColumn: preferredCompactColumn,
      sizeClass: sizeClass,
      nestedPathIsEmpty: nestedPath.isEmpty,
      hasSelection: appState.navigation.selectedSetting != nil
    ) {
    case .clearRootSelection:
      appState.navigation.selectedSetting = nil
    case .none:
      break
    }
  }
}

#Preview {
  SettingsView()
    .environment(\.appState, AppState())
}
