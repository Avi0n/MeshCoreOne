import Foundation
@testable import MC1Services
import MeshCore
import Testing

@MainActor
enum IdentityRecoveryFixture {
  static func interrupt(_ harness: ConnectionRadioHandoffHarness) async throws -> IdentityImportRecovery {
    let source = try #require(harness.manager.services)
    let radio = harness.manager.currentTransportType == .wifi ? harness.wifi : harness.bluetooth
    await radio.holdReplies(for: .importPrivateKey)
    let importing = harness.track(Task {
      try await source.nodeConfigService.importConfig(
        MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
        sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
      )
    })
    try await waitUntil("initial private-key submission should reach its held response") {
      await radio.sentData.contains { $0.first == CommandCode.importPrivateKey.rawValue }
    }
    await radio.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
    await radio.setSendError(MeshTransportError.notConnected)
    await radio.releaseReplies(for: .importPrivateKey)
    _ = await importing.result
    await radio.setSendError(nil)
    return try #require(harness.manager.identityImportRecoveries.first)
  }

  static func keyWriteCount(_ harness: ConnectionRadioHandoffHarness) async -> Int {
    let bluetooth = await harness.bluetooth.sentData.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count
    let wifi = await harness.wifi.sentData.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count
    return bluetooth + wifi
  }

  static func connectCount(_ harness: ConnectionRadioHandoffHarness) async -> Int {
    let bluetooth = await harness.bluetooth.connectCount
    let wifi = await harness.wifi.connectCount
    return bluetooth + wifi
  }

  @MainActor
  final class Gate {
    var arrived = false
    private var opened = false
    private var continuation: CheckedContinuation<Void, Never>?

    func wait() async {
      arrived = true
      guard !opened else { return }
      await withCheckedContinuation { continuation = $0 }
    }

    func open() {
      opened = true
      continuation?.resume()
      continuation = nil
    }
  }
}
