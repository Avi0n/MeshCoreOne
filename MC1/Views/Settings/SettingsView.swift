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
  @State private var nestedPath: [SettingsSubpage] = []
  @State private var editorPresentation = SettingsEditorPresentation()
  @State private var backupPresentation: SettingsBackupPresentation?
  private var demoModeManager = DemoModeManager.shared

  private var tabBarVisibility: Visibility {
    sizeClass == .compact ? .visible : .automatic
  }

  private var descendantPath: Binding<[SettingsSubpage]> {
    Binding(
      get: { nestedPath },
      set: { proposed in
        let admitted = SettingsSubpage.admitted(
          proposed,
          for: appState.navigation.selectedSetting,
          device: appState.connectedDevice
        )
        if admitted != nestedPath {
          nestedPath = admitted
        }
      }
    )
  }

  private var routeCapabilityKey: String {
    let device = appState.connectedDevice
    let floodScope = device?.supportsDefaultFloodScope == true
    let clientRepeat = device?.clientRepeat == true
    return "\(floodScope)-\(clientRepeat)"
  }

  private var currentBackupPresentation: SettingsBackupPresentation? {
    guard appState.navigation.selectedSetting == .backup,
          backupPresentation?.rootRevision == appState.navigation.settingsPresentationRevision else { return nil }
    return backupPresentation
  }

  private var currentEditorScope: SettingsEditorPresentation.Scope {
    Self.editorScope(appState: appState, descendants: nestedPath)
  }

  private static func editorScope(
    appState: AppState,
    descendants: [SettingsSubpage]
  ) -> SettingsEditorPresentation.Scope {
    SettingsEditorPresentation.Scope(
      selectedTab: appState.navigation.selectedTab,
      root: appState.navigation.selectedSetting,
      descendants: descendants,
      radioID: appState.connectedDevice?.radioID,
      supportsRegions: appState.connectedDevice?.supportsDefaultFloodScope == true,
      rootNavigationGeneration: appState.navigation.settingsRootNavigationGeneration
    )
  }

  var body: some View {
    let appState = appState
    let pathBinding = $nestedPath
    let radioID = appState.connectedDevice?.radioID
    let rootGeneration = appState.navigation.settingsRootNavigationGeneration
    let scopeProvider: SettingsEditorPresentation.ScopeProvider = {
      Self.editorScope(appState: appState, descendants: pathBinding.wrappedValue)
    }
    let requestNameEdit: SettingsEditorPresentation.NameRequest = { [weak editorPresentation] name, submit in
      editorPresentation?.presentDeviceName(name, radioID: radioID, scope: scopeProvider, onSubmit: submit)
    }
    let requestRegionAdd: SettingsEditorPresentation.RegionRequest = { [weak editorPresentation] knownRegions, submit in
      editorPresentation?.presentRegionAdd(
        radioID: radioID,
        rootNavigationGeneration: rootGeneration,
        scope: scopeProvider,
        knownRegions: knownRegions,
        onSubmit: submit
      )
    }

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
      NavigationStack(path: descendantPath) {
        Group {
          if let setting = appState.navigation.selectedSetting {
            SettingsDetailView(
              detail: setting,
              descendantPath: nestedPath,
              requestNameEdit: requestNameEdit,
              requestRegionAdd: requestRegionAdd,
              backupPresentation: currentBackupPresentation
            )
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
    .sectionSplitState(
      columnVisibility: $columnVisibility,
      preferredCompactColumn: $preferredCompactColumn,
      nestedPathIsEmpty: nestedPath.isEmpty,
      hasSelection: appState.navigation.selectedSetting != nil,
      onClearRootSelection: {
        // The settings list selection removes this root. A column change leaves the selection in place.
      }
    )
    .onChange(of: appState.navigation.selectedSetting) { _, _ in
      nestedPath = []
    }
    .onChange(of: appState.navigation.settingsRootNavigationGeneration) { _, _ in
      nestedPath = []
    }
    .onChange(of: routeCapabilityKey) { _, _ in
      nestedPath = SettingsSubpage.admitted(
        nestedPath,
        for: appState.navigation.selectedSetting,
        device: appState.connectedDevice
      )
    }
    .onChange(of: appState.navigation.settingsPresentationRevision, initial: true) { _, _ in
      prepareBackupPresentation()
    }
    .onChange(of: currentEditorScope) { _, _ in
      editorPresentation.reconcile()
    }
    .modifier(SettingsEditorPresenter(presentation: editorPresentation))
    .modifier(SettingsBackupPresenter(presentation: currentBackupPresentation))
  }

  private func prepareBackupPresentation() {
    let appState = appState
    let navigation = appState.navigation
    guard navigation.selectedSetting == .backup else {
      backupPresentation = nil
      return
    }
    guard backupPresentation?.rootRevision != navigation.settingsPresentationRevision else { return }
    backupPresentation = SettingsBackupPresentation(
      rootRevision: navigation.settingsPresentationRevision,
      connectionManager: appState.connectionManager,
      onImportRestoredData: { appState.notifyDataRestored() },
      onChannelDraftSlotsAffected: { slotsByRadio in
        appState.resetChannelDrafts(slotsByRadio: slotsByRadio)
      }
    )
  }
}

#Preview {
  SettingsView()
    .environment(\.appState, AppState())
}
