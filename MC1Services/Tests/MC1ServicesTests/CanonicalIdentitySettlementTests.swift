import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Canonical identity captured settlement")
@MainActor
struct CanonicalIdentitySettlementTests {
  @Test(arguments: [false, true], Outcome.allCases)
  func `actual held mutation settles its captured sink before canonical admission opens`(
    canonicalSink: Bool, outcome: Outcome
  ) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let source = try #require(harness.manager.services)
      let sourceContext = source.settingsService.radioWiFiContextID
      let canonical = harness.manager.radioWiFiState(for: harness.deviceB.radioID)
      let otherWire = ConnectionTestRadioTransport()
      let otherSession = MeshCoreSession(transport: otherWire)
      try await otherSession.start()
      let otherSettings = SettingsService(session: otherSession, radioWiFiState: canonical)
      let settings = canonicalSink ? otherSettings : source.settingsService
      let wire = canonicalSink ? otherWire : harness.bluetooth
      let captured = settings.radioWiFiBinding
      let command = "set wifi.enabled 0"
      await wire.setCLIResponse(command, response: outcome.reply)
      await wire.holdReplies(for: .runCliCommand)
      let mutating = harness.track(Task { try await settings.runCliCommand(command) })
      var importing: Task<Void, Error>?
      var imported = false
      do {
        try await waitUntil("actual WiFi mutation should hold its firmware reply") {
          await wire.cliCommands.contains(command)
        }
        mutating.cancel()
        if canonicalSink {
          _ = harness.track(Task { await otherSettings.retireRadioWiFiOperations() })
        }
        await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
        importing = harness.track(Task {
          try await source.nodeConfigService.importConfig(
            MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
            sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
          )
          imported = true
        })
        if canonicalSink {
          try await waitUntil("canonical transfer should reach the accepted identity observation") {
            harness.manager.pendingIdentityReconciliations[harness.deviceA.id]?.publicKey == CanonicalIdentityFixture.publicKey
          }
          try await Task.sleep(for: .milliseconds(30))
          #expect(!imported)
          #expect(canonical.stableRevision() == nil)
        }
        await wire.releaseReplies(for: .runCliCommand)
        _ = try await mutating.value
        try await importing?.value
        #expect(captured.state.rebootPending == outcome.pending)
        #expect(canonical.rebootPending == outcome.pending)
        let canonicalSettings = try #require(harness.manager.services?.settingsService)
        #expect(canonicalSettings !== source.settingsService)
        #expect(canonicalSettings.radioWiFiBinding.contextID != sourceContext)
        #expect(canonicalSettings.radioWiFiState === canonical)
        #expect(source.settingsService.radioWiFiBinding.contextID == sourceContext)
        if !canonicalSink { #expect(source.settingsService.radioWiFiState === captured.state) }
        #expect(canonical.stableRevision() != nil)
        #expect(try await canonicalSettings.getTime().timeIntervalSince1970 > 0)
      } catch {
        await wire.releaseReplies(for: .runCliCommand)
        _ = await mutating.result
        _ = await importing?.result
        await otherSettings.retireRadioWiFiOperations()
        await otherSession.stop()
        await otherSession.waitForQuiescence()
        throw error
      }
      await otherSettings.retireRadioWiFiOperations()
      await otherSession.stop()
      await otherSession.waitForQuiescence()
    }
  }

  enum Outcome: CaseIterable, Sendable {
    case accepted, rejected, uncertain
    var pending: Bool {
      self != .rejected
    }

    var reply: String {
      switch self {
      case .accepted: "OK"
      case .rejected: "Error: invalid value"
      case .uncertain: "ambiguous reply"
      }
    }
  }
}
