import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Radio WiFi credential effect contract")
@MainActor
struct RadioWiFiContractCredentialTests {
  @Test(arguments: ["", "  synthetic-key  ", "(not set)"])
  func `real credential pair keeps verbatim password`(_ savedPassword: String) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      var firmware = RadioWiFiFirmware(defaultSSID: "configured")
      firmware.savedPassword = savedPassword
      await harness.transport.useFirmware(firmware)
      await harness.viewModel.noteSessionReadiness()
      harness.viewModel.openEditor()

      await harness.viewModel.loadEditor()

      let exactPasswordKnown = harness.viewModel.passwordField == savedPassword
      #expect(exactPasswordKnown)
      let credentialKnowledgeMatchesContract1 = !harness.viewModel.needsEditorRead
      #expect(credentialKnowledgeMatchesContract1)
      harness.viewModel.ssidField = "verbatim-network"
      let saveEligibilityMatchesContract1 = harness.viewModel.canSave
      #expect(saveEligibilityMatchesContract1)
      await harness.viewModel.requestSave()
      let editorPresentationMatchesContract1 = !harness.viewModel.editorOpen
      #expect(editorPresentationMatchesContract1)
      let settings = try #require(harness.settings)
      let passwordUnchanged = try await settings.getRadioCLIValue("wifi.pwd") == savedPassword
      #expect(passwordUnchanged)
      #expect(try await settings.getRadioCLIValue("wifi.ssid") == "verbatim-network")
    }
  }

  @Test(arguments: [false, true])
  func `actual setter timeout retains uncertainty until fresh read`(_ applied: Bool) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      await harness.viewModel.noteSessionReadiness()
      harness.viewModel.openEditor()
      await harness.viewModel.loadEditor()
      harness.viewModel.ssidField = "submitted"
      let command = "set wifi.ssid submitted"
      if applied {
        await harness.transport.holdNext(command)
      } else {
        await harness.transport.dropNextBeforeApply(command)
      }
      let save = harness.start { await harness.viewModel.requestSave() }
      try await waitUntil("the real setter exchange is waiting for its reply") {
        await harness.transport.count(command) == 1 && harness.clock.sleeperCount > 0
      }
      harness.clock.advance(by: .seconds(31))
      await save.value

      let errorPresentationMatchesContract1 = harness.viewModel.errorMessage != nil
      #expect(errorPresentationMatchesContract1)
      let credentialKnowledgeMatchesContract2 = harness.viewModel.needsEditorRead
      #expect(credentialKnowledgeMatchesContract2)
      let saveEligibilityMatchesContract2 = !harness.viewModel.canSave
      #expect(saveEligibilityMatchesContract2)
      #expect(harness.settings?.radioWiFiState.rebootPending == true)
      let uncertainNetworkCannotClear = !harness.viewModel.canClear
      let uncertainNetworkUsesGenericRebootCopy = harness.viewModel.rebootMessage
        == L10n.Settings.RadioWiFi.Alert.Reboot.restart
      #expect(uncertainNetworkCannotClear)
      #expect(uncertainNetworkUsesGenericRebootCopy)
      await harness.viewModel.loadEditor()
      let capturedBaseline = harness.viewModel.editor?.baseline
      let baseline = try #require(capturedBaseline)
      let networkKnowledgeMatchesContract1 = baseline.ssid == (applied ? "submitted" : "initial")
      #expect(networkKnowledgeMatchesContract1)
      let credentialKnowledgeMatchesContract3 = !harness.viewModel.needsEditorRead
      #expect(credentialKnowledgeMatchesContract3)
      harness.viewModel.ssidField = "recovered"
      let saveEligibilityMatchesContract3 = harness.viewModel.canSave
      #expect(saveEligibilityMatchesContract3)
      await harness.viewModel.requestSave()
      let settings = try #require(harness.settings)
      #expect(try await settings.getRadioCLIValue("wifi.ssid") == "recovered")
    }
  }

  @Test(arguments: [false, true])
  func `accepted SSID survives later password uncertainty`(_ passwordApplied: Bool) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      await harness.viewModel.noteSessionReadiness()
      harness.viewModel.openEditor()
      await harness.viewModel.loadEditor()
      harness.viewModel.ssidField = "accepted-network"
      let submittedPassword = "synthetic-partial-secret"
      harness.viewModel.passwordField = submittedPassword
      let command = "set wifi.pwd " + submittedPassword
      if passwordApplied {
        await harness.transport.holdNext(command)
      } else {
        await harness.transport.dropNextBeforeApply(command)
      }
      let save = harness.start { await harness.viewModel.requestSave() }
      try await waitUntil("the accepted SSID is followed by a real password timeout") {
        await harness.transport.count(command) == 1 && harness.clock.sleeperCount > 0
      }
      harness.clock.advance(by: .seconds(31))
      await save.value

      let confirmedSSIDRemainsVisible = harness.viewModel.networkName == "accepted-network"
      #expect(harness.viewModel.networkValue == "accepted-network")
      let confirmedSSIDCanClear = harness.viewModel.canClear
      let confirmedSSIDNamesReboot = harness.viewModel.rebootMessage
        == L10n.Settings.RadioWiFi.Alert.Reboot.join("accepted-network")
      let uncertainPasswordCannotSave = !harness.viewModel.canSave && harness.viewModel.needsEditorRead
      #expect(confirmedSSIDRemainsVisible)
      #expect(confirmedSSIDCanClear)
      #expect(confirmedSSIDNamesReboot)
      #expect(uncertainPasswordCannotSave)
      #expect(harness.settings?.radioWiFiState.rebootPending == true)

      await harness.viewModel.loadEditor()
      let capturedBaseline = harness.viewModel.editor?.baseline
      let baseline = try #require(capturedBaseline)
      let passwordMatchesStoredEffect = baseline.password == (passwordApplied ? submittedPassword : "")
      #expect(passwordMatchesStoredEffect)
      harness.viewModel.ssidField = "after-partial-uncertainty"
      let recoveredDraftCanSave = harness.viewModel.canSave
      #expect(recoveredDraftCanSave)
      await harness.viewModel.requestSave()
      let settings = try #require(harness.settings)
      #expect(try await settings.getRadioCLIValue("wifi.ssid") == "after-partial-uncertainty")
      let storedPasswordMatchesValidSave = try await settings.getRadioCLIValue("wifi.pwd") == submittedPassword
      #expect(storedPasswordMatchesValidSave)
    }
  }
}
