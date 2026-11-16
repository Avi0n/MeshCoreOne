import MC1Services
import SwiftUI

/// Settings section for diagnostic tools including log export and clearing
struct DiagnosticsSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Binding var exportedFile: ExportedLogFile?
  let isSidebar: Bool
  @AppStorage(AppStorageKey.collectDebugLogs.rawValue) private var collectLogs = false
  @State private var isExporting = false
  @State private var showingClearLogsAlert = false
  @State private var showingDisableCollectionAlert = false
  @State private var presentedErrorMessage: String?
  /// Same text as `presentedErrorMessage`, kept here so it stays readable after `disableCollection(using:)` suspends.
  private let errorRecord = ErrorRecord()

  var errorMessage: String? {
    errorRecord.text
  }

  @MainActor
  private final class ErrorRecord {
    var text: String?
  }

  var body: some View {
    Section {
      Toggle(isOn: collectionBinding) {
        TintedLabel(L10n.Settings.Diagnostics.collectLogs, systemImage: "doc.text.magnifyingglass")
      }

      if collectLogs {
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
      }
    } header: {
      Text(L10n.Settings.Diagnostics.header)
    } footer: {
      if collectLogs {
        Text(L10n.Settings.Diagnostics.footer)
      } else {
        Text(L10n.Settings.Diagnostics.Footer.disabled)
      }
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
    .alert(L10n.Settings.Diagnostics.Alert.Disable.title, isPresented: $showingDisableCollectionAlert) {
      Button(L10n.Localizable.Common.cancel, role: .cancel) {}
      Button(L10n.Settings.Diagnostics.Alert.Disable.confirm, role: .destructive) {
        let dataStore = appState.connectionManager.persistenceStore
        Task { await disableCollection(using: dataStore) }
      }
    } message: {
      Text(L10n.Settings.Diagnostics.Alert.Disable.message)
    }
    .errorAlert($presentedErrorMessage)
  }

  /// Off raises the confirm alert and does not write the key. On calls `enableCollection`, which leaves the key false while a disable is in progress.
  private var collectionBinding: Binding<Bool> {
    Binding(
      get: { collectLogs },
      set: { setCollectionEnabled($0) }
    )
  }

  func setCollectionEnabled(_ isOn: Bool) {
    if isOn {
      DebugLogBuffer.enableCollection()
    } else {
      showingDisableCollectionAlert = true
    }
  }

  /// On success the key is already false. On throw, turns it back on and sets `errorMessage`.
  func disableCollection(using dataStore: any DebugLogPersisting) async {
    let record = errorRecord
    do {
      try await DebugLogBuffer.disableCollection(persistenceStore: dataStore)
    } catch {
      collectLogs = true
      setErrorMessage(error.userFacingMessage, record: record)
    }
  }

  private func setErrorMessage(_ message: String, record: ErrorRecord) {
    record.text = message
    presentedErrorMessage = message
  }

  private func exportLogs() {
    let dataStore = appState.connectionManager.persistenceStore
    isExporting = true

    Task { @MainActor in
      if let url = await LogExportService.createExportFile(
        appState: appState,
        persistenceStore: dataStore
      ) {
        exportedFile = ExportedLogFile(url: url)
      } else {
        setErrorMessage(L10n.Settings.Diagnostics.Error.exportFailed, record: errorRecord)
      }
      isExporting = false
    }
  }

  private func clearDebugLogs() {
    let dataStore = appState.connectionManager.persistenceStore

    Task {
      do {
        try await dataStore.clearDebugLogEntries()
      } catch {
        setErrorMessage(error.userFacingMessage, record: errorRecord)
      }
    }
  }
}
