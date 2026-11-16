import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Radio WiFi shared read ownership contract")
@MainActor
struct RadioWiFiContractOwnershipTests {
  @Test
  func `raw mutation during real editor read requires fresh pair`() async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      await harness.viewModel.noteSessionReadiness()
      harness.viewModel.openEditor()
      await harness.transport.holdNext("get wifi.ssid")
      let read = harness.start { await harness.viewModel.loadEditor() }
      try await waitUntil("editor SSID read is physically submitted") {
        await harness.transport.heldCount("get wifi.ssid") == 1
      }
      let tools = harness.makeTools()
      tools.executeCommand("set wifi.enabled 0")
      try await waitUntil("Tools mutation invalidates the read before its own send") {
        harness.settings?.radioWiFiState.stableRevision() == nil
      }
      await harness.transport.release("get wifi.ssid")
      await read.value
      try await waitUntil("the queued Tools command settles") { !tools.isWaitingForResponse }

      #expect(await harness.transport.count("get wifi.pwd") == 0)
      let credentialKnowledgeMatchesContract1 = harness.viewModel.needsEditorRead
      #expect(credentialKnowledgeMatchesContract1)
      let saveEligibilityMatchesContract1 = !harness.viewModel.canSave
      #expect(saveEligibilityMatchesContract1)
      await harness.viewModel.loadEditor()
      let credentialKnowledgeMatchesContract2 = !harness.viewModel.needsEditorRead
      #expect(credentialKnowledgeMatchesContract2)
      harness.viewModel.ssidField = "after-mutation"
      let saveEligibilityMatchesContract2 = harness.viewModel.canSave
      #expect(saveEligibilityMatchesContract2)
      await harness.viewModel.requestSave()
      let settings = try #require(harness.settings)
      #expect(try await settings.getRadioCLIValue("wifi.ssid") == "after-mutation")
    }
  }

  @Test
  func `unsupported probe admits no followup and retry recovers`() async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      await harness.transport.markUnsupported("get wifi.enabled")

      await harness.viewModel.noteSessionReadiness()

      let viewStateMatchesContract1 = harness.viewModel.wifiSettingsSupport == .unavailable
      #expect(viewStateMatchesContract1)
      let viewStateMatchesContract2 = !harness.viewModel.canRunCommands
      #expect(viewStateMatchesContract2)
      #expect(harness.viewModel.networkValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      #expect(await harness.transport.count("get wifi.ssid") == 0)
      #expect(await harness.transport.count("get wifi.pwd") == 0)
      harness.viewModel.openEditor()
      let editorPresentationMatchesContract1 = !harness.viewModel.editorOpen
      #expect(editorPresentationMatchesContract1)
      await harness.transport.support("get wifi.enabled")
      await harness.viewModel.refresh()
      let viewStateMatchesContract3 = harness.viewModel.canRunCommands
      #expect(viewStateMatchesContract3)
      let networkObservationMatchesContract1 = harness.viewModel.networkName == "initial"
      #expect(networkObservationMatchesContract1)
      let settings = try #require(harness.settings)
      #expect(try await settings.getRadioCLIValue("wifi.enabled") == "1")
    }
  }
}
