import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Radio WiFi framed credential values")
@MainActor
struct RadioWiFiContractFramedValueTests {
  @Test(arguments: ["\r", "\n", "\r\n"])
  func `trailing line endings are stored credential bytes`(_ ending: String) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      let initialSSID = "framed-network" + ending
      let savedPassword = "synthetic-framed" + ending
      var firmware = RadioWiFiFirmware()
      firmware.savedSSID = initialSSID
      firmware.savedPassword = savedPassword
      firmware.boot()
      await harness.transport.useFirmware(firmware)
      await harness.viewModel.noteSessionReadiness()
      let exactNetworkObserved = harness.viewModel.networkName == initialSSID
      #expect(exactNetworkObserved)
      harness.viewModel.openEditor()
      await harness.viewModel.loadEditor()
      let exactSSIDKnown = harness.viewModel.ssidField == initialSSID
      let exactPasswordKnown = harness.viewModel.passwordField == savedPassword
      #expect(exactSSIDKnown)
      #expect(exactPasswordKnown)
      let settings = try #require(harness.settings)
      let actualFrameCarriesPasswordBytes = try await settings.runCliCommand("get wifi.pwd") == "> " + savedPassword
      #expect(actualFrameCarriesPasswordBytes)

      let updatedSSID = "after-frame" + ending
      harness.viewModel.ssidField = updatedSSID
      let validDraftCanSave = harness.viewModel.canSave
      #expect(validDraftCanSave)
      await harness.viewModel.requestSave()
      await harness.viewModel.refresh()
      let savedNetworkIsExact = harness.viewModel.networkName == updatedSSID
      let savedPasswordIsExact = try await settings.getRadioCLIValue("wifi.pwd") == savedPassword
      #expect(savedNetworkIsExact)
      #expect(savedPasswordIsExact)
    }
  }
}
