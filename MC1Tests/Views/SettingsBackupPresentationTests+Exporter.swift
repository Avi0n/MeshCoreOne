import Foundation
@testable import MC1
@testable import MC1Services
import Testing

extension SettingsBackupPresentationTests {
  @Test
  func `exporter false then late completion stays cancelled`() throws {
    let fixture = try Fixture()
    let pending = fixture.stageExport()

    fixture.presentation.setExporterPresented(false, requestID: pending.id)
    fixture.presentation.handleExportResult(Completion.success.result, requestID: pending.id)
    fixture.presentation.setExporterPresented(false, requestID: pending.id)

    #expect(fixture.presentation.viewModel.pendingExport == nil)
    #expect(fixture.presentation.viewModel.exportSummary == nil)
    #expect(fixture.presentation.viewModel.errorMessage == nil)
    #expect(fixture.presentation.viewModel.isExporting == false)
  }

  @Test(arguments: Completion.allCases)
  func `completion then false preserves its outcome`(completion: Completion) throws {
    let fixture = try Fixture()
    let pending = fixture.stageExport()
    fixture.presentation.handleExportResult(completion.result, requestID: pending.id)
    let summary = fixture.presentation.viewModel.exportSummary
    let errorMessage = fixture.presentation.viewModel.errorMessage

    fixture.presentation.setExporterPresented(false, requestID: pending.id)
    fixture.presentation.handleExportResult(Completion.success.result, requestID: pending.id)

    #expect(fixture.presentation.viewModel.pendingExport == nil)
    #expect(fixture.presentation.viewModel.exportSummary == summary)
    #expect(fixture.presentation.viewModel.errorMessage == errorMessage)
    switch completion {
    case .success:
      #expect(summary?.filename == "synthetic-backup.mc1backup")
      #expect(summary?.byteCount == 1)
      #expect(errorMessage == nil)
    case .failed:
      #expect(summary == nil)
      #expect(errorMessage != nil)
    }
  }

  @Test
  func `old exporter callbacks cannot resolve A new pending export`() throws {
    let fixture = try Fixture()
    let old = fixture.stageExport()
    fixture.presentation.setExporterPresented(false, requestID: old.id)
    let current = fixture.stageExport()
    try #require(old.id != current.id)

    fixture.presentation.setExporterPresented(false, requestID: old.id)
    fixture.presentation.handleExportResult(Completion.success.result, requestID: old.id)

    #expect(fixture.presentation.viewModel.pendingExport?.id == current.id)
    #expect(fixture.presentation.viewModel.pendingExport?.data == current.data)
    #expect(fixture.presentation.viewModel.exportSummary == nil)
    #expect(fixture.presentation.viewModel.errorMessage == nil)

    fixture.presentation.setExporterPresented(false, requestID: current.id)
    #expect(fixture.presentation.viewModel.pendingExport == nil)
  }
}
