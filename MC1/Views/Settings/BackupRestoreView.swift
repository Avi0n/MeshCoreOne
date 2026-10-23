import SwiftUI

struct BackupRestoreView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Bindable var presentation: SettingsBackupPresentation

  private var viewModel: AppBackupViewModel {
    presentation.viewModel
  }

  private var scope: SettingsBackupPresentation.Scope {
    SettingsBackupPresentation.Scope(
      selectedTab: appState.navigation.selectedTab,
      root: appState.navigation.selectedSetting,
      presentationRevision: appState.navigation.settingsPresentationRevision
    )
  }

  var body: some View {
    List {
      Section {
        exportRow
        importRow
      } header: {
        Text(L10n.Settings.Settings.Backup.FileBackup.header)
      } footer: {
        VStack(alignment: .leading, spacing: 8) {
          if appState.connectionState.isConnected {
            Text(L10n.Settings.Settings.Backup.Import.disabledWhenConnected)
          }
          Text(L10n.Settings.Settings.Backup.FileBackup.footer)
          Text(L10n.Settings.Settings.Backup.FileBackup.radioConfig)
        }
      }
      .themedRowBackground(theme)
    }
    .themedCanvas(theme)
    .navigationTitle(L10n.Settings.Settings.Backup.title)
  }

  // MARK: - Rows

  private var exportRow: some View {
    Button {
      presentation.requestExportConfirmation(in: scope)
    } label: {
      HStack {
        if viewModel.isExporting {
          ProgressView()
          Text(L10n.Settings.Settings.Backup.Export.progress)
        } else {
          Label(L10n.Settings.Settings.Backup.Export.title, systemImage: "square.and.arrow.up")
        }
        Spacer()
        Text(L10n.Settings.Settings.Backup.Export.subtitle)
          .font(.footnote)
          .foregroundStyle(.secondary)
      }
    }
    .disabled(viewModel.isExporting || viewModel.isImporting || viewModel.isParsing)
  }

  @ViewBuilder
  private var importRow: some View {
    let isRadioConnected = appState.connectionState.isConnected
    let isBusy = viewModel.isExporting || viewModel.isImporting || viewModel.isParsing
    let content = Button {
      presentation.requestFileImporter(in: scope)
    } label: {
      HStack {
        if viewModel.isParsing {
          ProgressView()
          Text(L10n.Settings.Settings.Backup.Import.parsing)
        } else {
          Label(L10n.Settings.Settings.Backup.Import.title, systemImage: "square.and.arrow.down")
        }
        Spacer()
        Text(L10n.Settings.Settings.Backup.Import.subtitle)
          .font(.footnote)
          .foregroundStyle(.secondary)
      }
    }
    if isRadioConnected {
      content
        .disabled(true)
        .foregroundStyle(.secondary)
        .accessibilityHint(L10n.Settings.Settings.Backup.Import.disabledWhenConnected)
    } else {
      content.disabled(isBusy)
    }
  }
}
