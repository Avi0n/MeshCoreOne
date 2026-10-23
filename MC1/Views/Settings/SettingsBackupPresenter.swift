import SwiftUI

struct SettingsBackupPresenter: ViewModifier {
  @Environment(\.appState) private var appState
  let presentation: SettingsBackupPresentation?

  func body(content: Content) -> some View {
    let appState = appState
    let presentation = presentation
    let viewModel = presentation?.viewModel
    let confirmationID = presentation?.exportConfirmationRequestID
    let pendingExport = viewModel?.pendingExport
    let exportID = pendingExport?.id

    content
      .alert(
        L10n.Settings.Settings.Backup.Export.Alert.title,
        isPresented: Binding(
          get: {
            guard let confirmationID else { return false }
            return presentation?.exportConfirmationRequestID == confirmationID
              && presentation?.showExportConfirmation == true
          },
          set: { proposed in
            guard let confirmationID else { return }
            presentation?.setExportConfirmationPresented(proposed, requestID: confirmationID)
          }
        ),
        presenting: confirmationID
      ) { requestID in
        Button(L10n.Settings.Settings.Backup.Export.Alert.cancel, role: .cancel) {
          presentation?.cancelExportConfirmation(requestID: requestID)
        }
        Button(L10n.Settings.Settings.Backup.Export.Alert.export) {
          presentation?.confirmExport(requestID: requestID, in: Self.scope(appState: appState))
        }
      } message: { _ in
        Text(L10n.Settings.Settings.Backup.Export.Alert.message)
      }
      .fileExporter(
        isPresented: Binding(
          get: {
            guard let exportID else { return false }
            return viewModel?.pendingExport?.id == exportID
          },
          set: { proposed in
            guard let exportID else { return }
            presentation?.setExporterPresented(proposed, requestID: exportID)
          }
        ),
        document: pendingExport.map { AppBackupDocument(data: $0.data) },
        contentType: .mc1Backup,
        defaultFilename: viewModel?.defaultExportFilename
      ) { result in
        guard let exportID else { return }
        presentation?.handleExportResult(result, requestID: exportID)
      }
      .fileImporter(
        isPresented: Binding(
          get: { presentation?.showFileImporter == true },
          set: { presentation?.showFileImporter = $0 }
        ),
        allowedContentTypes: [.mc1Backup]
      ) { result in
        presentation?.handleFileSelected(result, in: Self.scope(appState: appState))
      }
      .sheet(isPresented: Binding(
        get: { viewModel?.isImportSheetActive == true },
        set: { if !$0 { viewModel?.dismissImportSheet() } }
      )) {
        if let viewModel {
          ImportPreviewSheet(viewModel: viewModel)
        }
      }
      .sheet(item: Binding(
        get: { viewModel?.exportSummary },
        set: { if $0 == nil { viewModel?.dismissExportSuccess() } }
      )) { summary in
        if let viewModel {
          NavigationStack {
            ExportSuccessContent(summary: summary, onDismiss: viewModel.dismissExportSuccess)
          }
        }
      }
      .errorAlert(Binding(
        get: { viewModel?.errorMessage },
        set: { viewModel?.errorMessage = $0 }
      ))
  }

  private static func scope(appState: AppState) -> SettingsBackupPresentation.Scope {
    SettingsBackupPresentation.Scope(
      selectedTab: appState.navigation.selectedTab,
      root: appState.navigation.selectedSetting,
      presentationRevision: appState.navigation.settingsPresentationRevision
    )
  }
}
