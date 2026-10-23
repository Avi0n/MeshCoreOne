import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@MainActor
struct SettingsBackupPresentationTests {
  @Test(arguments: Exit.allCases)
  func `stale scope cannot begin A presentation`(exit: Exit) throws {
    let fixture = try Fixture()
    exit.apply(to: fixture.navigation)

    fixture.presentation.requestExportConfirmation(in: fixture.scope)
    fixture.presentation.requestFileImporter(in: fixture.scope)

    #expect(fixture.presentation.exportConfirmationRequestID == nil)
    #expect(fixture.presentation.showExportConfirmation == false)
    #expect(fixture.presentation.showFileImporter == false)
    #expect(fixture.presentation.viewModel.isExporting == false)
  }

  @Test(arguments: Exit.allCases)
  func `confirm reads current scope before any observer`(exit: Exit) throws {
    let fixture = try Fixture()
    let requestID = try fixture.requestConfirmation()
    exit.apply(to: fixture.navigation)

    fixture.presentation.confirmExport(requestID: requestID, in: fixture.scope)

    #expect(fixture.presentation.viewModel.isExporting == false)
    #expect(fixture.presentation.viewModel.pendingExport == nil)
  }

  @Test
  func `current raw false before confirm still starts the export`() async throws {
    let fixture = try Fixture()
    let requestID = try fixture.requestConfirmation()
    fixture.presentation.setExportConfirmationPresented(false, requestID: requestID)
    #expect(fixture.presentation.showExportConfirmation == false)

    fixture.presentation.confirmExport(requestID: requestID, in: fixture.scope)
    #expect(fixture.presentation.viewModel.isExporting)

    try await waitUntil("Synthetic backup export did not finish") {
      fixture.presentation.viewModel.pendingExport != nil || fixture.presentation.viewModel.errorMessage != nil
    }
    let pending = try #require(fixture.presentation.viewModel.pendingExport)
    fixture.presentation.setExporterPresented(false, requestID: pending.id)
    #expect(fixture.presentation.viewModel.errorMessage == nil)
  }

  @Test
  func `old confirmation writes and actions leave the current warning`() throws {
    let fixture = try Fixture()
    let old = try fixture.requestConfirmation()
    let current = try fixture.requestConfirmation()
    try #require(old != current)

    fixture.presentation.setExportConfirmationPresented(false, requestID: old)
    fixture.presentation.cancelExportConfirmation(requestID: old)
    fixture.presentation.confirmExport(requestID: old, in: fixture.scope)

    #expect(fixture.presentation.exportConfirmationRequestID == current)
    #expect(fixture.presentation.showExportConfirmation)
    #expect(fixture.presentation.viewModel.isExporting == false)

    fixture.presentation.cancelExportConfirmation(requestID: current)
    #expect(fixture.presentation.showExportConfirmation == false)
    #expect(fixture.presentation.exportConfirmationRequestID == nil)
  }

  @Test
  func `current raw false then cancel retires the warning`() throws {
    let fixture = try Fixture()
    let requestID = try fixture.requestConfirmation()
    fixture.presentation.setExportConfirmationPresented(false, requestID: requestID)
    fixture.presentation.cancelExportConfirmation(requestID: requestID)
    fixture.presentation.confirmExport(requestID: requestID, in: fixture.scope)
    #expect(fixture.presentation.exportConfirmationRequestID == nil)
    #expect(fixture.presentation.showExportConfirmation == false)
    #expect(fixture.presentation.viewModel.isExporting == false)
  }

  @Test
  func `same root request preserves current owner admission and warning`() throws {
    let fixture = try Fixture()
    let requestID = try fixture.requestConfirmation()
    let originalRevision = fixture.navigation.settingsPresentationRevision
    let originalGeneration = fixture.navigation.settingsRootNavigationGeneration

    fixture.navigation.navigateToSetting(.backup)

    #expect(fixture.navigation.settingsPresentationRevision == originalRevision)
    #expect(fixture.navigation.settingsRootNavigationGeneration > originalGeneration)
    #expect(fixture.presentation.exportConfirmationRequestID == requestID)
    #expect(fixture.presentation.showExportConfirmation)
    fixture.presentation.cancelExportConfirmation(requestID: requestID)
    fixture.presentation.requestFileImporter(in: fixture.scope)
    #expect(fixture.presentation.showFileImporter)
  }
}
