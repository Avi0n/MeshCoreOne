import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Radio CLI request provenance contract")
@MainActor
struct CLIProvenanceContractTests {
  @Test
  func `unrelated CLI rejection cannot reject applied typed mutation`() async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      await harness.viewModel.noteSessionReadiness()
      await harness.transport.holdNext("set wifi.enabled 0")
      let write = harness.start { await harness.viewModel.setWiFiEnabled(false) }
      try await waitUntil("typed mutation physically applied but reply held") {
        await harness.transport.heldCount("set wifi.enabled 0") == 1
      }

      await harness.transport.injectCLIReply("Error: rejected")
      await harness.transport.release("set wifi.enabled 0")
      await write.value

      let errorPresentationMatchesContract1 = harness.viewModel.errorMessage == nil
      #expect(errorPresentationMatchesContract1)
      let enabledObservationMatchesContract1 = !harness.viewModel.wifiEnabled
      #expect(enabledObservationMatchesContract1)
      let viewStateMatchesContract1 = harness.viewModel.rebootPending
      #expect(viewStateMatchesContract1)
      let settings = try #require(harness.settings)
      #expect(try await settings.getRadioCLIValue("wifi.enabled") == "0")
    }
  }

  @Test
  func `timed out password cannot become retry SSID`() async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      let secret = "synthetic-private-value"
      var firmware = RadioWiFiFirmware(defaultSSID: "configured")
      firmware.savedPassword = secret
      await harness.transport.useFirmware(firmware)
      await harness.viewModel.noteSessionReadiness()
      harness.viewModel.openEditor()
      await harness.transport.holdNext("get wifi.pwd")
      let first = harness.start { await harness.viewModel.loadEditor() }
      try await waitUntil("the first password frame is held after physical send") {
        await harness.transport.heldCount("get wifi.pwd") == 1 && harness.clock.sleeperCount > 0
      }
      harness.clock.advance(by: .seconds(31))
      await first.value
      let errorPresentationMatchesContract2 = harness.viewModel.errorMessage != nil
      #expect(errorPresentationMatchesContract2)
      let credentialKnowledgeMatchesContract1 = harness.viewModel.needsEditorRead
      #expect(credentialKnowledgeMatchesContract1)
      let saveEligibilityMatchesContract1 = !harness.viewModel.canSave
      #expect(saveEligibilityMatchesContract1)
      await harness.transport.holdNext("get wifi.ssid")
      let retry = harness.start { await harness.viewModel.loadEditor() }
      try await waitUntil("Retry owns a fresh SSID exchange") {
        await harness.transport.heldCount("get wifi.ssid") == 1
      }

      await harness.transport.release("get wifi.pwd")
      await harness.transport.release("get wifi.ssid")
      await retry.value

      let correctSSID = harness.viewModel.ssidField == "configured"
      let correctPassword = harness.viewModel.passwordField == secret
      let secretAbsentFromNonsecretFields = harness.viewModel.ssidField != secret
        && harness.viewModel.networkName != secret && !harness.viewModel.rebootMessage.contains(secret)
      #expect(correctSSID)
      #expect(correctPassword)
      #expect(secretAbsentFromNonsecretFields)
      let errorPresentationMatchesContract3 = harness.viewModel.errorMessage == nil
      #expect(errorPresentationMatchesContract3)
      let credentialKnowledgeMatchesContract2 = !harness.viewModel.needsEditorRead
      #expect(credentialKnowledgeMatchesContract2)
      harness.viewModel.ssidField = "replacement"
      let saveEligibilityMatchesContract2 = harness.viewModel.canSave
      #expect(saveEligibilityMatchesContract2)
      await harness.viewModel.requestSave()
      let editorPresentationMatchesContract1 = !harness.viewModel.editorOpen
      #expect(editorPresentationMatchesContract1)
      let settings = try #require(harness.settings)
      #expect(try await settings.getRadioCLIValue("wifi.ssid") == "replacement")
    }
  }

  @Test
  func `raw tools retains bytes and reflected prefix between typed reads`() async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      await harness.viewModel.noteSessionReadiness()
      let tools = harness.makeTools()
      let command = "  z9|set wifi.enabled 0"

      tools.executeCommand(command)
      try await waitUntil("raw Tools command settles") { !tools.isWaitingForResponse }

      #expect(await harness.transport.commands.last == command)
      #expect(tools.outputLines.contains { $0.text == "z9|> wifi.enabled updated (reboot to apply)" })
      await harness.viewModel.refresh()
      let enabledObservationMatchesContract2 = !harness.viewModel.wifiEnabled
      #expect(enabledObservationMatchesContract2)
      let viewStateMatchesContract2 = harness.viewModel.rebootPending
      #expect(viewStateMatchesContract2)
      let settings = try #require(harness.settings)
      #expect(try await settings.getRadioCLIValue("wifi.enabled") == "0")
    }
  }
}
