import MC1Services
import SwiftUI

/// Settings section for diagnostic tools including log export and clearing
struct DiagnosticsSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Binding var exportedFile: ExportedLogFile?
  let isSidebar: Bool
  @State private var isExporting = false
  @State private var showingClearLogsAlert = false
  @State private var errorMessage: String?

  var body: some View {
    Section {
      Button {
        exportLogs()
      } label: {
        HStack {
          TintedLabel(L10n.Settings.Diagnostics.exportLogs, systemImage: "square.and.arrow.up")
          Spacer()
          if isExporting {
            ProgressView()
          }
        }
      }
      .disabled(isExporting)

      Button(role: .destructive) {
        showingClearLogsAlert = true
      } label: {
        Label(L10n.Settings.Diagnostics.clearLogs, systemImage: "trash")
      }
    } header: {
      Text(L10n.Settings.Diagnostics.header)
    } footer: {
      Text(L10n.Settings.Diagnostics.footer)
    }
    .themedRowBackground(theme, flatten: isSidebar)
    .alert(L10n.Settings.Diagnostics.Alert.Clear.title, isPresented: $showingClearLogsAlert) {
      Button(L10n.Localizable.Common.cancel, role: .cancel) {}
      Button(L10n.Settings.Diagnostics.Alert.Clear.confirm, role: .destructive) {
        clearDebugLogs()
      }
    } message: {
      Text(L10n.Settings.Diagnostics.Alert.Clear.message)
    }
    .errorAlert($errorMessage)
  }

  private func exportLogs() {
    let dataStore = appState.services?.dataStore ?? appState.connectionManager.createStandalonePersistenceStore()
    isExporting = true

    Task { @MainActor in
      if let url = await LogExportService.createExportFile(
        appState: appState,
        persistenceStore: dataStore
      ) {
        exportedFile = ExportedLogFile(url: url)
      } else {
        errorMessage = L10n.Settings.Diagnostics.Error.exportFailed
      }
      isExporting = false
    }
  }

  private func clearDebugLogs() {
    let dataStore = appState.services?.dataStore ?? appState.connectionManager.createStandalonePersistenceStore()

    Task {
      do {
        try await dataStore.clearDebugLogEntries()
      } catch {
        errorMessage = error.userFacingMessage
      }
    }
  }
}
