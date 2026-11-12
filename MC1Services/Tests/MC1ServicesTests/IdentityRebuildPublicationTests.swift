import Foundation
@testable import MC1Services
import Testing

@Suite("Identity rebuild publication")
@MainActor
struct IdentityRebuildPublicationTests {
  @Test
  func `an unpublished identity rebuild stays on the request through connecting`() throws {
    let defaults = try #require(UserDefaults(suiteName: "test.\(UUID().uuidString)"))
    let (manager, _) = try ConnectionManager.createForTesting(defaults: defaults)
    manager.setTestState(connectionIntent: .wantsConnection(), identityRebuildUnpublished: true)
    let request = ConnectionRequest(
      targets: [.bluetooth(UUID())], origin: .explicit, forceFullSync: false, forceReconnect: false,
      continuing: nil
    )
    manager.desiredRequest = request

    manager.detachPublication(phase: .connected)
    #expect(manager.identityRebuildUnpublished)

    manager.publishPending(request, phase: .connecting)
    #expect(manager.identityRebuildUnpublished)

    manager.detachPublication(phase: .disconnected)
    #expect(!manager.identityRebuildUnpublished)
  }
}
