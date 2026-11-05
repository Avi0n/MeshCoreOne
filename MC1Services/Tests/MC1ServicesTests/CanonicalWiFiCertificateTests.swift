import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Canonical WiFi certificates")
@MainActor
struct CanonicalWiFiCertificateTests {
  @Test
  func `equal revision counters cannot preserve old probe or credential authority`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let services = try #require(harness.manager.services)
      let settings = services.settingsService
      await harness.bluetooth.setCLIResponse("get wifi.ssid", response: "> synthetic-network")
      await harness.bluetooth.setCLIResponse("get wifi.pwd", response: "> synthetic-password")
      _ = try await settings.runCliCommand("set wifi.enabled 0")
      let stateB = harness.manager.radioWiFiState(for: harness.deviceB.radioID)
      let rejected = try #require(stateB.beginMutation(expectedRevision: nil))
      stateB.settle(rejected, outcome: .rejected)
      #expect(stateB.revision == settings.radioWiFiState.revision)
      guard case let .supported(probe) = try await settings.probeRadioWiFiSettings() else {
        Issue.record("Expected supported WiFi fixture")
        return
      }
      let editorID = UUID()
      let baseline = try #require(try await settings.readRadioWiFiCredentials(editorID: editorID))
      await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
      try await services.nodeConfigService.importConfig(
        MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
        sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
      )
      let canonicalSettings = try #require(harness.manager.services?.settingsService)
      #expect(canonicalSettings !== settings)
      #expect(await settings.readRadioWiFiObservation(probe: probe) == nil)
      #expect(await canonicalSettings.readRadioWiFiObservation(probe: probe) == nil)
      let commandsBefore = await harness.bluetooth.cliCommands.count
      for target in [settings, canonicalSettings] {
        var rejectedOldCertificate = false
        do {
          _ = try await target.saveRadioWiFiCredentials(
            ssid: "fresh-network", password: "fresh-password", baseline: baseline,
            editorID: editorID, ownerIsCurrent: { harness.manager.services?.settingsService === target }
          )
        } catch is CancellationError {
          rejectedOldCertificate = true
        }
        #expect(rejectedOldCertificate)
      }
      #expect(await harness.bluetooth.cliCommands.count == commandsBefore)
      let freshEditor = UUID()
      let fresh = try #require(try await canonicalSettings.readRadioWiFiCredentials(editorID: freshEditor))
      #expect(fresh.radioID == harness.deviceB.radioID)
      let receipt = try await canonicalSettings.saveRadioWiFiCredentials(
        ssid: "fresh-network", password: "fresh-password", baseline: fresh,
        editorID: freshEditor, ownerIsCurrent: { harness.manager.services?.settingsService === canonicalSettings }
      )
      #expect(receipt.ssidAccepted && receipt.passwordAccepted)
      #expect(!receipt.uncertain)
      #expect(stateB.rebootPending)
      #expect(try await canonicalSettings.getTime() > .distantPast)
    }
  }
}
