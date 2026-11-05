import Foundation
@testable import MC1Services
import MeshCore
import Synchronization
import Testing

@Suite("ConnectionManager WiFi loss ownership")
@MainActor
struct ConnectionManagerWiFiLossOwnershipTests {
  @Test
  func `an old loss notification cannot suppress a replacement socket loss`() async throws {
    try await Harness.withHarness { harness in
      _ = try await harness.manager.connectViaWiFi(host: "first.local", port: 4242)
      let first = try #require(harness.manager.currentConnection)
      let notification = harness.gate()
      let replacementNotification = harness.gate()
      var firstNotificationStarted = false
      var replacementNotificationStarted = false
      var firstNotificationReturned = false
      harness.manager.onAutoReconnectStarted = { receipt in
        if receipt.requestID == first.requestID {
          firstNotificationStarted = true
          await notification.wait()
          firstNotificationReturned = true
        } else {
          replacementNotificationStarted = true
          await replacementNotification.wait()
        }
      }
      await harness.first.simulateRemoteLoss()
      try await waitUntil("old loss notification should remain held") { firstNotificationStarted }
      _ = try await harness.manager.connectViaWiFi(host: "second.local", port: 4242)
      let second = try #require(harness.manager.currentConnection)
      #expect(second.device.radioID != first.device.radioID)

      await harness.second.simulateRemoteLoss()
      try await waitUntil("replacement loss must be admitted while the old notification remains held") {
        replacementNotificationStarted
      }
      notification.open()
      try await waitUntil("old notification should return independently") { firstNotificationReturned }
      #expect(harness.manager.isHandlingWiFiDisconnection)
      replacementNotification.open()
      try await waitUntil("replacement loss must remain able to recover") {
        harness.manager.currentConnection?.generation != second.generation
          && harness.manager.connectionState == .ready
      }

      #expect(harness.manager.connectedDevice?.radioID == second.device.radioID)
      #expect(await harness.third.connectCount == 1)
    }
  }

  @Test
  func `a delayed loss callback cannot start recovery for a newer loss in the same request`() async throws {
    try await Harness.withHarness { harness in
      _ = try await harness.manager.connectViaWiFi(host: "first.local", port: 4242)
      let requestID = harness.manager.connectionRequestID
      let setup = harness.gate()
      let olderNotification = harness.gate()
      let newerNotification = harness.gate()
      var setupStarted = false
      var notificationCount = 0
      harness.manager.onConnectionReady = { _ in
        if harness.factory.createdCount == 2 {
          setupStarted = true
          await setup.wait()
        }
      }
      harness.manager.onAutoReconnectStarted = { _ in
        notificationCount += 1
        switch notificationCount {
        case 2: await olderNotification.wait()
        case 3: await newerNotification.wait()
        default: break
        }
      }
      await harness.first.simulateRemoteLoss()
      try await waitUntil("recovery should enter the second socket setup") { setupStarted }
      await harness.second.disconnect()
      let olderLoss = harness.handleLoss(source: harness.second)
      try await waitUntil("second socket loss callback should remain held") { notificationCount == 2 }
      setup.open()
      try await waitUntil("existing retry worker should publish the third socket") {
        harness.factory.createdCount == 3 && harness.manager.connectionState == .ready
          && harness.manager.wifiReconnectTask == nil
      }
      #expect(harness.manager.connectionRequestID == requestID)
      await harness.third.disconnect()
      let newerLoss = harness.handleLoss(source: harness.third)
      try await waitUntil("newer loss callback should own the same request") { notificationCount == 3 }

      olderNotification.open()
      await olderLoss.value

      #expect(harness.manager.isHandlingWiFiDisconnection)
      #expect(harness.manager.wifiReconnectTask == nil)
      #expect(harness.factory.createdCount == 3)
      newerNotification.open()
      await newerLoss.value
      try await waitUntil("only the newer loss should restart recovery") {
        harness.manager.connectionState == .ready && harness.factory.createdCount == 4
      }
    }
  }

  @Test
  func `a recovery retry waits for retirement of the socket lost during setup`() async throws {
    try await Harness.withHarness { harness in
      _ = try await harness.manager.connectViaWiFi(host: "first.local", port: 4242)
      let setup = harness.gate()
      var setupStarted = false
      harness.manager.onConnectionReady = { snapshot in
        if snapshot.device.publicKey == harness.second.publicKey {
          setupStarted = true
          await setup.wait()
        }
      }
      await harness.second.holdDisconnect()
      await harness.third.holdConnect()
      await harness.first.simulateRemoteLoss()
      try await waitUntil("first recovery should publish and enter its setup callback") { setupStarted }
      #expect(harness.factory.createdCount == 2)
      await harness.second.simulateRemoteLoss()
      try await waitUntil("lost recovery socket should remain in physical close") { await harness.second.disconnectIsHeld }
      setup.open()
      try await waitUntil("existing recovery worker should advance to its next attempt") {
        harness.manager.wifiReconnectAttempt == 2
      }

      #expect(harness.factory.createdCount == 2)
      #expect(await harness.third.connectCount == 0)
      #expect(harness.manager.currentConnection == nil)
      await harness.second.releaseDisconnect()
      try await waitUntil("settled retirement should release the next physical connect") {
        await harness.third.connectIsHeld
      }
      await harness.third.releaseConnect()
      try await waitUntil("the later retry should remain able to become ready") {
        harness.manager.connectionState == .ready && harness.manager.currentConnection != nil
      }
      #expect(harness.manager.connectedDevice?.publicKey == harness.third.publicKey)
      #expect(harness.manager.connectionTeardownCount == 0)
    }
  }

  @MainActor
  private final class Harness {
    let manager: ConnectionManager
    let factory = SocketSequence()
    private let suite: String
    private var gates: [BLEReconnectionTestHarness.Gate] = []
    private var lossWorkers: [Task<Void, Never>] = []
    var first: ConnectionTestRadioTransport {
      factory.sockets[0]
    }

    var second: ConnectionTestRadioTransport {
      factory.sockets[1]
    }

    var third: ConnectionTestRadioTransport {
      factory.sockets[2]
    }

    init() throws {
      suite = "test.wifi-loss-ownership.\(UUID())"
      let defaults = try #require(UserDefaults(suiteName: suite))
      let container = try PersistenceStore.createContainer(inMemory: true)
      let pairing = MockAccessorySetupKitService()
      pairing.isSessionActive = false
      let factory = factory
      manager = ConnectionManager(
        modelContainer: container, defaults: defaults, stateMachine: MockBLEStateMachine(),
        transport: ConnectionTestRadioTransport(),
        pairing: AccessorySetupPairingService(accessorySetupKit: pairing),
        makeWiFiTransport: { factory.make() }
      )
      manager.testWiFiReconnectCooldown = 0
    }

    func gate() -> BLEReconnectionTestHarness.Gate {
      let gate = BLEReconnectionTestHarness.Gate()
      gates.append(gate)
      return gate
    }

    func handleLoss(source: ConnectionTestRadioTransport) -> Task<Void, Never> {
      let worker = Task {
        await manager.handleWiFiDisconnection(error: WiFiTransportError.notConnected, remoteLossAt: nil, source: source)
      }
      lossWorkers.append(worker)
      return worker
    }

    func close() async {
      for gate in gates {
        gate.open()
      }
      for socket in factory.sockets {
        await socket.releaseAllGates()
      }
      _ = await manager.disconnect(reason: .userInitiated)
      for worker in lossWorkers {
        await worker.value
      }
      await manager.retirementTask?.value
      await manager.metadataCommitTask?.value
      UserDefaults().removePersistentDomain(forName: suite)
    }

    static func withHarness(_ body: (Harness) async throws -> Void) async throws {
      let harness = try Harness()
      do { try await body(harness) } catch { await harness.close(); throw error }
      await harness.close()
    }
  }

  private final class SocketSequence: Sendable {
    nonisolated let sockets = [
      ConnectionTestRadioTransport(publicKey: Data(repeating: 0xA1, count: ProtocolLimits.publicKeySize)),
      ConnectionTestRadioTransport(publicKey: Data(repeating: 0xB1, count: ProtocolLimits.publicKeySize)),
      ConnectionTestRadioTransport(publicKey: Data(repeating: 0xB1, count: ProtocolLimits.publicKeySize))
    ]
    private let count = Mutex(0)
    nonisolated var createdCount: Int {
      count.withLock { $0 }
    }

    nonisolated func make() -> any WiFiMeshTransport {
      count.withLock { count in
        let socket = sockets[min(count, sockets.count - 1)]
        count += 1
        return socket
      }
    }
  }
}
