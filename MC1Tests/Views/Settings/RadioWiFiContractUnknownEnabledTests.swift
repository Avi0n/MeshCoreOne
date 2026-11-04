import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Radio WiFi unknown enabled knowledge")
@MainActor
struct RadioWiFiContractUnknownEnabledTests {
  @Test
  func `failed readback after uncertain enabled write withdraws commands until retry`() async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      await harness.viewModel.noteSessionReadiness()
      await harness.transport.holdNext("set wifi.enabled 0")
      await harness.transport.rejectNext("get wifi.enabled")
      let write = harness.start { await harness.viewModel.setWiFiEnabled(false) }
      try await waitUntil("the submitted enabled change owns a real exchange timeout") {
        await harness.transport.heldCount("set wifi.enabled 0") == 1 && harness.clock.sleeperCount > 0
      }
      harness.clock.advance(by: .seconds(31))
      await write.value
      let readbackFailed = harness.viewModel.errorMessage != nil
      let commandsWithdrawn = !harness.viewModel.canRunCommands
      let editorWithdrawn = !harness.viewModel.canEditNetwork
      let pendingRetained = harness.viewModel.rebootPending
      #expect(readbackFailed)
      #expect(commandsWithdrawn)
      #expect(editorWithdrawn)
      #expect(pendingRetained)
      let before = await harness.transport.sentData.count
      harness.viewModel.openEditor()
      let editorStayedClosed = !harness.viewModel.editorOpen
      #expect(editorStayedClosed)
      harness.viewModel.requestReboot()
      let rebootNotAdmitted = !harness.viewModel.showingRebootConfirm
      #expect(rebootNotAdmitted)
      await harness.viewModel.confirmFromAlert(.reboot).value
      await harness.viewModel.setWiFiEnabled(true)
      #expect(await harness.transport.sentData.count == before)
      await harness.viewModel.closeEditor()

      await harness.viewModel.refresh()
      let freshKnowledgeRestoresCommands = harness.viewModel.canRunCommands
      #expect(freshKnowledgeRestoresCommands)
      harness.viewModel.openEditor()
      await harness.viewModel.loadEditor()
      harness.viewModel.ssidField = "after-failed-enabled-read"
      let freshPairCanSave = harness.viewModel.canSave
      #expect(freshPairCanSave)
      await harness.viewModel.requestSave()
      let settings = try #require(harness.settings)
      #expect(try await settings.getRadioCLIValue("wifi.ssid") == "after-failed-enabled-read")
      await harness.viewModel.setWiFiEnabled(true)
      #expect(try await settings.getRadioCLIValue("wifi.enabled") == "1")
    }
  }
}
