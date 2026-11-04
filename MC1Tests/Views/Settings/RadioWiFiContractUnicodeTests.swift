import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Radio WiFi credential UTF8 identity")
@MainActor
struct RadioWiFiContractUnicodeTests {
  @Test(arguments: [false, true])
  func `canonically equivalent credential edit changes stored bytes`(_ editsPassword: Bool) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      let initialSSID = "unicode-network-\u{00E9}"
      let initialPassword = "synthetic-unicode-\u{00E9}"
      var firmware = RadioWiFiFirmware()
      firmware.savedSSID = initialSSID
      firmware.savedPassword = initialPassword
      firmware.boot()
      await harness.transport.useFirmware(firmware)
      await harness.viewModel.noteSessionReadiness()
      harness.viewModel.openEditor()
      await harness.viewModel.loadEditor()
      let initialPairMatchesWireBytes = harness.viewModel.ssidField.utf8.elementsEqual(initialSSID.utf8)
        && harness.viewModel.passwordField.utf8.elementsEqual(initialPassword.utf8)
      #expect(initialPairMatchesWireBytes)
      let settings = try #require(harness.settings)
      let capturedBaseline = harness.viewModel.editor?.baseline
      let baseline = try #require(capturedBaseline)
      let changedSSID = editsPassword ? initialSSID : "unicode-network-e\u{0301}"
      let changedPassword = editsPassword ? "synthetic-unicode-e\u{0301}" : initialPassword
      if editsPassword {
        harness.viewModel.passwordField = changedPassword
      } else {
        harness.viewModel.ssidField = changedSSID
      }
      let byteChangeIsEligible = harness.viewModel.canSave
      #expect(byteChangeIsEligible)
      let before = await harness.transport.commands.count
      let receipt = try await settings.saveRadioWiFiCredentials(
        ssid: changedSSID, password: changedPassword, baseline: baseline,
        editorID: baseline.editorID, ownerIsCurrent: { true }
      )
      let ssidWasAccepted = receipt.ssidAccepted
      let passwordOutcomeMatchesEdit = receipt.passwordAccepted == editsPassword
      let noFailureReported = receipt.error == nil
      #expect(ssidWasAccepted)
      #expect(passwordOutcomeMatchesEdit)
      #expect(noFailureReported)
      let actualCommands = await harness.transport.effectiveCommands().dropFirst(before)
      let submittedSSIDHasExactBytes = actualCommands.contains {
        $0.utf8.elementsEqual(("set wifi.ssid " + changedSSID).utf8)
      }
      let submittedPasswordHasExactBytes = actualCommands.contains {
        $0.utf8.elementsEqual(("set wifi.pwd " + changedPassword).utf8)
      }
      #expect(submittedSSIDHasExactBytes)
      #expect(submittedPasswordHasExactBytes == editsPassword)
      let storedSSID = try await settings.getRadioCLIValue("wifi.ssid")
      let storedPassword = try await settings.getRadioCLIValue("wifi.pwd")
      let changedPairMatchesWireBytes = storedSSID?.utf8.elementsEqual(changedSSID.utf8) == true
        && storedPassword?.utf8.elementsEqual(changedPassword.utf8) == true
      #expect(changedPairMatchesWireBytes)

      await harness.viewModel.closeEditor()
      await harness.viewModel.refresh()
      harness.viewModel.openEditor()
      await harness.viewModel.loadEditor()
      harness.viewModel.ssidField = "after-unicode-edit"
      harness.viewModel.passwordField = "synthetic-later-valid"
      let laterDraftCanSave = harness.viewModel.canSave
      #expect(laterDraftCanSave)
      await harness.viewModel.requestSave()
      #expect(try await settings.getRadioCLIValue("wifi.ssid") == "after-unicode-edit")
      let laterPassword = try await settings.getRadioCLIValue("wifi.pwd")
      let laterPasswordMatchesWireBytes = laterPassword?.utf8.elementsEqual("synthetic-later-valid".utf8) == true
      #expect(laterPasswordMatchesWireBytes)
    }
  }
}
