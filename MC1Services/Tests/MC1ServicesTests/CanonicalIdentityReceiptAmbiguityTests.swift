import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Canonical identity receipt ambiguity")
@MainActor
struct CanonicalIdentityReceiptAmbiguityTests {
  @Test
  func `two accepted failed imports of the same key do not choose an arbitrary WiFi source row`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      let keyD = Data(repeating: 0xD7, count: ProtocolLimits.publicKeySize)
      let radioD = DeviceDTO.testDevice(radioID: UUID(), publicKey: keyD, firmwareVersion: 14).copy {
        $0.connectionMethods = [.bluetooth(peripheralUUID: $0.id, displayName: nil)]
      }
      try await harness.manager.persistenceStore.saveDevice(radioD)
      await harness.bluetooth.setPublicKey(keyD, for: radioD.id)
      await harness.manager.persistenceStore.setIdentityReconciliationFaultInjection { throw Failure.save }
      for device in [harness.deviceA, radioD] {
        _ = try await harness.manager.connect(to: device.id, forceReconnect: true)
        let services = try #require(harness.manager.services)
        _ = try await services.settingsService.runCliCommand("set wifi.enabled 0")
        await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: device.id)
        await #expect(throws: (any Error).self) {
          try await services.nodeConfigService.importConfig(
            MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
            sections: ConfigSections(nodeIdentity: true), radioID: device.radioID
          )
        }
        #expect(harness.manager.pendingIdentityReconciliations[device.id]?.publicKey == CanonicalIdentityFixture.publicKey)
      }
      await harness.manager.persistenceStore.setIdentityReconciliationFaultInjection(nil)
      #expect(harness.manager.pendingIdentityReconciliations.count == 2)
      await harness.wifi.setDeviceID(harness.deviceB.id)
      await harness.wifi.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceB.id)
      await #expect(throws: ConnectionRequestFailure.self) {
        _ = try await harness.manager.connectViaWiFi(host: "radio.local", port: 4242)
      }
      #expect(harness.manager.services == nil)
      #expect(try await harness.manager.persistenceStore.fetchDevice(id: harness.deviceA.id)?.radioID == harness.deviceA.radioID)
      #expect(try await harness.manager.persistenceStore.fetchDevice(id: radioD.id)?.radioID == radioD.radioID)
      #expect(try await harness.manager.persistenceStore.fetchDevice(radioID: harness.deviceB.radioID)?.isActive == false)
      #expect(harness.manager.pendingIdentityReconciliations.count == 2)
      #expect(!harness.manager.radioWiFiState(for: harness.deviceB.radioID).rebootPending)

      _ = try await harness.manager.connect(to: harness.deviceA.id, forceReconnect: true)
      #expect(harness.manager.connectedDevice?.id == harness.deviceA.id)
      #expect(harness.manager.connectedDevice?.radioID == harness.deviceB.radioID)
      #expect(harness.manager.pendingIdentityReconciliations[harness.deviceA.id] == nil)
      #expect(harness.manager.pendingIdentityReconciliations[radioD.id]?.publicKey == CanonicalIdentityFixture.publicKey)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }

  private enum Failure: Error { case save }
}
