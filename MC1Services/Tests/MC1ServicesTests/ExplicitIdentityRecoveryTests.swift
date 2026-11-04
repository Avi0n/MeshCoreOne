import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Explicit identity recovery")
@MainActor
struct ExplicitIdentityRecoveryTests {
  @Test
  func `repeated uncertainty and rejected retry preserve the earlier receipt until a deliberate success`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let original = try await IdentityRecoveryFixture.interrupt(harness)
      await harness.bluetooth.holdReplies(for: .importPrivateKey)
      let retrying = harness.track(Task {
        try await harness.manager.retryIdentityImport(confirmation: original, privateKey: CanonicalIdentityFixture.privateKey)
      })
      try await waitUntil("deliberate retry should write exactly one private key") {
        await IdentityRecoveryFixture.keyWriteCount(harness) == 2
      }
      await harness.bluetooth.setSendError(MeshTransportError.notConnected)
      await harness.bluetooth.releaseReplies(for: .importPrivateKey)
      _ = await retrying.result
      await harness.bluetooth.setSendError(nil)
      let repeated = try #require(harness.manager.prepareIdentityImportRecovery(receiptID: original.receiptID))
      #expect(repeated.originalRadioID == original.originalRadioID)
      #expect(repeated.capturedConnectionMethod == original.capturedConnectionMethod)
      await harness.bluetooth.rejectNext(.importPrivateKey)
      await #expect(throws: (any Error).self) {
        _ = try await harness.manager.retryIdentityImport(confirmation: repeated, privateKey: CanonicalIdentityFixture.privateKey)
      }
      let rejected = try #require(harness.manager.prepareIdentityImportRecovery(receiptID: original.receiptID))
      #expect(rejected.receiptID == original.receiptID)
      #expect(try await harness.manager.persistenceStore.fetchDevice(id: harness.deviceA.id)?.radioID == harness.deviceA.radioID)
      _ = try await harness.manager.retryIdentityImport(confirmation: rejected, privateKey: CanonicalIdentityFixture.privateKey)
      #expect(harness.manager.connectedDevice?.radioID == harness.deviceB.radioID)
      #expect(harness.manager.identityImportRecoveries.isEmpty)
      let writes = await IdentityRecoveryFixture.keyWriteCount(harness)
      #expect(writes == 4)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }

  @Test
  func `unchanged original source consumes uncertainty without resending identity`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      _ = try await IdentityRecoveryFixture.interrupt(harness)
      await harness.bluetooth.setPublicKey(harness.deviceA.publicKey, for: harness.deviceA.id)
      _ = try await harness.manager.connect(to: harness.deviceA.id, forceReconnect: true)
      #expect(harness.manager.connectedDevice?.radioID == harness.deviceA.radioID)
      #expect(harness.manager.connectedDevice?.publicKey == harness.deviceA.publicKey)
      #expect(harness.manager.identityImportRecoveries.isEmpty)
      let writes = await IdentityRecoveryFixture.keyWriteCount(harness)
      #expect(writes == 1)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }

  @Test
  func `a stale confirmation and invalid input cannot submit or replace an unresolved receipt`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      let original = try await IdentityRecoveryFixture.interrupt(harness)
      await #expect(throws: CancellationError.self) {
        _ = try await harness.manager.retryIdentityImport(confirmation: original, privateKey: Data())
      }
      await harness.wifi.setDeviceID(harness.deviceB.id)
      await harness.wifi.setPublicKey(harness.deviceB.publicKey, for: harness.deviceB.id)
      _ = try await harness.manager.connectViaWiFi(host: "other.local", port: 4242)
      let successorID = harness.manager.connectionRequestID
      let before = await harness.bluetooth.connectCount
      await #expect(throws: CancellationError.self) {
        _ = try await harness.manager.retryIdentityImport(confirmation: original, privateKey: CanonicalIdentityFixture.privateKey)
      }
      #expect(harness.manager.connectionRequestID == successorID)
      #expect(harness.manager.connectedDevice?.radioID == harness.deviceB.radioID)
      let after = await harness.bluetooth.connectCount
      let writes = await IdentityRecoveryFixture.keyWriteCount(harness)
      #expect(after == before)
      #expect(writes == 1)
      #expect(harness.manager.prepareIdentityImportRecovery(receiptID: original.receiptID) != nil)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }

  @Test
  func `source uncertainty terminates a launch request before its otherwise usable fallback`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      _ = try await IdentityRecoveryFixture.interrupt(harness)
      let before = await harness.wifi.connectCount
      await #expect(throws: ConnectionRequestFailure.self) {
        _ = try await harness.manager.requestConnection(
          targets: [.bluetooth(harness.deviceA.id), .wifi(host: "fallback.local", port: 4242)],
          origin: .launch, forceFullSync: false, forceReconnect: true
        )
      }
      let after = await harness.wifi.connectCount
      #expect(after == before)
      #expect(harness.manager.desiredRequest?.target == .disconnected)
      #expect(!harness.manager.connectionIntent.wantsConnection)
      await harness.manager.checkBLEConnectionHealth()
      await harness.manager.checkWiFiConnectionHealth()
      #expect(await harness.wifi.connectCount == after)
      let recovery = try #require(harness.manager.identityImportRecoveries.first)
      _ = try await harness.manager.retryIdentityImport(confirmation: recovery, privateKey: CanonicalIdentityFixture.privateKey)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }
}
