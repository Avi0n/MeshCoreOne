import Foundation
@testable import MC1
@testable import MC1Services
import Testing

extension SettingsBackupPresentationTests {
  @Test
  func `admitted file selection uses the existing parser`() async throws {
    let fixture = try Fixture()
    let url = try readableBackupURL()
    fixture.presentation.requestFileImporter(in: fixture.scope)

    fixture.presentation.handleFileSelected(.success(url), in: fixture.scope)

    try await waitUntil("Synthetic backup preview did not finish parsing") {
      fixture.presentation.viewModel.previewEnvelope != nil || fixture.presentation.viewModel.errorMessage != nil
    }
    let preview = try #require(fixture.presentation.viewModel.previewEnvelope)
    #expect(preview.appVersion == "synthetic")
    #expect(preview.manifest == BackupManifest())
    #expect(fixture.presentation.viewModel.errorMessage == nil)
    fixture.presentation.viewModel.dismissImportSheet()
    #expect(fixture.presentation.viewModel.isImportSheetActive == false)
  }

  @Test(arguments: Exit.allCases)
  func `stale successful file result cannot start or replace parsing`(exit: Exit) throws {
    let fixture = try Fixture()
    let url = try readableBackupURL()
    fixture.presentation.requestFileImporter(in: fixture.scope)
    fixture.presentation.viewModel.importState = .preview(
      AppBackupEnvelope(appVersion: "existing", appBuild: "1", manifest: BackupManifest())
    )
    exit.apply(to: fixture.navigation)

    fixture.presentation.handleFileSelected(.success(url), in: fixture.scope)

    #expect(fixture.presentation.viewModel.isParsing == false)
    #expect(fixture.presentation.viewModel.previewEnvelope?.appVersion == "existing")
    #expect(fixture.presentation.viewModel.errorMessage == nil)
  }

  @Test(arguments: [false, true])
  func `picker failure settles its captured owner after exit`(cancelled: Bool) throws {
    let fixture = try Fixture()
    fixture.navigation.selectedSetting = .maps
    let error = CocoaError(cancelled ? .userCancelled : .fileReadNoSuchFile)

    fixture.presentation.handleFileSelected(.failure(error), in: fixture.scope)

    #expect(fixture.presentation.viewModel.isParsing == false)
    #expect(fixture.presentation.viewModel.previewEnvelope == nil)
    #expect((fixture.presentation.viewModel.errorMessage == nil) == cancelled)
  }
}
