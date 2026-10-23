import Foundation
@testable import MC1
@testable import MC1Services
import Testing

extension SettingsBackupPresentationTests {
  enum Exit: CaseIterable {
    case tabLeft
    case rootRemoved
    case rootReplaced
    case returnedToSameRoot

    @MainActor
    func apply(to navigation: NavigationCoordinator) {
      switch self {
      case .tabLeft:
        navigation.selectedTab = AppTab.tools.rawValue
      case .rootRemoved:
        navigation.selectedSetting = nil
      case .rootReplaced:
        navigation.selectedSetting = .maps
      case .returnedToSameRoot:
        navigation.selectedSetting = .maps
        navigation.selectedSetting = .backup
      }
    }
  }

  enum Completion: CaseIterable {
    case success
    case failed

    var result: Result<URL, Error> {
      switch self {
      case .success:
        .success(URL(fileURLWithPath: "/synthetic-backup.mc1backup"))
      case .failed:
        .failure(CocoaError(.fileWriteOutOfSpace))
      }
    }
  }

  @MainActor
  struct Fixture {
    let navigation: NavigationCoordinator
    let presentation: SettingsBackupPresentation

    init() throws {
      let navigation = NavigationCoordinator()
      navigation.navigateToSetting(.backup)
      self.navigation = navigation
      let manager = try ConnectionManager(modelContainer: PersistenceStore.createContainer(inMemory: true))
      presentation = SettingsBackupPresentation(
        rootRevision: navigation.settingsPresentationRevision,
        connectionManager: manager,
        onImportRestoredData: {},
        onChannelDraftSlotsAffected: { _ in }
      )
    }

    var scope: SettingsBackupPresentation.Scope {
      SettingsBackupPresentation.Scope(
        selectedTab: navigation.selectedTab,
        root: navigation.selectedSetting,
        presentationRevision: navigation.settingsPresentationRevision
      )
    }

    func requestConfirmation(
      sourceLocation: SourceLocation = #_sourceLocation
    ) throws -> UUID {
      presentation.requestExportConfirmation(in: scope)
      return try #require(presentation.exportConfirmationRequestID, sourceLocation: sourceLocation)
    }

    func stageExport() -> AppBackupViewModel.PendingExport {
      let pending = AppBackupViewModel.PendingExport(
        data: Data([0x01]),
        manifest: BackupManifest()
      )
      presentation.viewModel.exportState = .pending(pending)
      return pending
    }
  }

  func readableBackupURL() throws -> URL {
    let envelope = AppBackupEnvelope(
      appVersion: "synthetic",
      appBuild: "1",
      manifest: BackupManifest()
    )
    let data = try makeBackupJSONEncoder().encode(envelope).zlibCompressed()
    return try #require(URL(string: "data:application/octet-stream;base64,\(data.base64EncodedString())"))
  }
}
