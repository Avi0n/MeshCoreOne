import Foundation
import MC1Services
import Observation

@Observable
@MainActor
final class SettingsBackupPresentation {
  struct Scope: Equatable {
    let selectedTab: Int
    let root: SettingsDetail?
    let presentationRevision: Int
  }

  let rootRevision: Int
  let viewModel: AppBackupViewModel
  private(set) var exportConfirmationRequestID: UUID?
  private(set) var showExportConfirmation = false
  var showFileImporter = false

  init(
    rootRevision: Int,
    connectionManager: ConnectionManager,
    onImportRestoredData: @escaping @MainActor () -> Void,
    onChannelDraftSlotsAffected: @escaping @MainActor ([UUID: Set<UInt8>]) -> Void
  ) {
    self.rootRevision = rootRevision
    viewModel = AppBackupViewModel(
      connectionManager: connectionManager,
      onImportRestoredData: onImportRestoredData,
      onChannelDraftSlotsAffected: onChannelDraftSlotsAffected
    )
  }

  func requestExportConfirmation(in scope: Scope) {
    guard admits(scope) else { return }
    exportConfirmationRequestID = UUID()
    showExportConfirmation = true
  }

  func setExportConfirmationPresented(_ proposed: Bool, requestID: UUID) {
    guard exportConfirmationRequestID == requestID else { return }
    // The system's false write can precede the action that consumes this request.
    showExportConfirmation = proposed
  }

  func cancelExportConfirmation(requestID: UUID) {
    guard exportConfirmationRequestID == requestID else { return }
    finishExportConfirmation()
  }

  func confirmExport(requestID: UUID, in scope: Scope) {
    guard exportConfirmationRequestID == requestID, admits(scope) else { return }
    finishExportConfirmation()
    viewModel.performExport()
  }

  func requestFileImporter(in scope: Scope) {
    guard admits(scope) else { return }
    showFileImporter = true
  }

  func handleFileSelected(_ result: Result<URL, Error>, in scope: Scope) {
    if case .success = result, !admits(scope) { return }
    viewModel.handleFileSelected(result: result)
  }

  func setExporterPresented(_ proposed: Bool, requestID: UUID) {
    guard !proposed, viewModel.pendingExport?.id == requestID else { return }
    viewModel.handleExportResult(.failure(CocoaError(.userCancelled)))
  }

  func handleExportResult(_ result: Result<URL, Error>, requestID: UUID) {
    guard viewModel.pendingExport?.id == requestID else { return }
    viewModel.handleExportResult(result)
  }

  private func admits(_ scope: Scope) -> Bool {
    scope.selectedTab == AppTab.settings.rawValue
      && scope.root == .backup
      && scope.presentationRevision == rootRevision
  }

  private func finishExportConfirmation() {
    showExportConfirmation = false
    exportConfirmationRequestID = nil
  }
}
