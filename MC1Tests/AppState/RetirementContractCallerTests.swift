import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Synchronization
import Testing
import UserNotifications

@Suite("Retirement contract callers")
@MainActor
struct RetirementContractCallerTests {
  enum Trigger: CaseIterable, Sendable { case disconnect, replacement, wifiLoss, bleLoss }
  @Test(arguments: Trigger.allCases)
  func `captured cleanup survives each terminal caller`(trigger: Trigger) async throws {
    let fixture = try Fixture(bluetoothSource: trigger == .bleLoss)
    var disconnect: Task<Void, Never>?
    var replacement: Task<ConnectionResult, Error>?
    do {
      if trigger == .bleLoss {
        _ = try await fixture.manager.connect(to: fixture.deviceID, forceFullSync: true, forceReconnect: true)
      } else {
        _ = try await fixture.app.connectViaWiFi(host: "first.local", port: 4242)
      }
      let source = try #require(fixture.manager.currentConnection)
      let sourceServices = try #require(fixture.manager.services)
      let frame = ContactFrame(
        publicKey: Data(repeating: 41, count: ProtocolLimits.publicKeySize), type: .chat,
        flags: 0, outPathLength: 0, outPath: Data(), name: "Deleted", lastAdvertTimestamp: 0,
        latitude: 0, longitude: 0, lastModified: 0
      )
      let saved = try await fixture.manager.persistenceStore.saveContact(radioID: source.device.radioID, from: frame)
      fixture.firstNotifications.add(contactID: saved.id, prefix: "source")
      fixture.secondNotifications.add(contactID: UUID(), prefix: "successor")
      fixture.firstNotifications.holdNextSnapshot = true
      fixture.app.advertisementEventsTask?.cancel()
      await fixture.app.advertisementEventsTask?.value
      fixture.app.advertisementEventsTask = nil
      await fixture.first.inject(Data([ResponseCode.contactDeleted.rawValue]) + frame.publicKey)
      try await waitUntil("background deletion should be admitted before retirement") {
        await sourceServices.advertisementService.pendingDeletedKeys.contains(frame.publicKey)
      }
      let sourceBadges = fixture.firstNotifications.badges
      fixture.manager.testNotificationSystemOperations = fixture.secondNotifications.operations
      switch trigger {
      case .disconnect:
        disconnect = Task { await fixture.app.disconnect(reason: .userInitiated) }
        try await waitUntil("disconnect should enter captured system cleanup") {
          fixture.firstNotifications.snapshotIsHeld
        }
        replacement = Task { try await fixture.app.connectViaWiFi(host: "second.local", port: 4242) }
      case .replacement:
        replacement = Task { try await fixture.app.connectViaWiFi(host: "second.local", port: 4242) }
      case .wifiLoss:
        await fixture.first.simulateRemoteLoss()
      case .bleLoss:
        await fixture.first.simulateRemoteLoss()
        try await waitUntil("BLE loss should enter captured system cleanup") {
          fixture.firstNotifications.snapshotIsHeld
        }
        replacement = Task { try await fixture.app.connectViaWiFi(host: "second.local", port: 4242) }
      }
      try await waitUntil("retirement should await captured notification cleanup") {
        fixture.firstNotifications.snapshotIsHeld
      }
      #expect(try await fixture.manager.persistenceStore.fetchContact(id: saved.id) == nil)
      #expect(await fixture.second.connectCount == 0)
      #expect(fixture.manager.currentConnection == nil)
      #expect(fixture.firstNotifications.removed.isEmpty)
      #expect(fixture.app.advertisementEventsTask == nil)
      fixture.app.navigation.selectedTool = .lineOfSight

      fixture.firstNotifications.releaseSnapshot()
      await disconnect?.value
      if let replacement {
        let result = try await replacement.value
        #expect(fixture.manager.isCurrent(result))
      } else {
        try await waitUntil("same-request recovery should establish B after A cleanup") {
          fixture.manager.connectionState == .ready && fixture.manager.connectedDevice?.publicKey == fixture.second.publicKey
        }
      }
      #expect(fixture.manager.connectionState == .ready)
      #expect(fixture.manager.connectedDevice?.publicKey == fixture.second.publicKey)
      #expect(Set(fixture.firstNotifications.removed) == ["source-dm", "source-reaction"])
      #expect(fixture.firstNotifications.badges == sourceBadges)
      #expect(fixture.secondNotifications.removed.isEmpty)
      #expect(fixture.secondNotifications.requests.count == 2)
      #expect(fixture.app.navigation.selectedTool == .lineOfSight)
      #expect(!fixture.app.connectionUI.isBusy)
      let successor = try #require(fixture.manager.session)
      _ = try await successor.getTime()
    } catch {
      fixture.firstNotifications.releaseSnapshot()
      fixture.secondNotifications.releaseSnapshot()
      await disconnect?.value
      _ = await replacement?.result
      await fixture.close()
      throw error
    }
    await fixture.close()
  }

  @MainActor
  private final class Fixture {
    let deviceID = UUID()
    let app: AppState
    let manager: ConnectionManager
    let first = ConnectionTestRadioTransport(publicKey: Data(repeating: 0xA1, count: ProtocolLimits.publicKeySize))
    let second = ConnectionTestRadioTransport(publicKey: Data(repeating: 0xB2, count: ProtocolLimits.publicKeySize))
    let firstNotifications = Recorder()
    let secondNotifications = Recorder()
    let sockets: Sockets
    private let suite = "test.retirement-callers.\(UUID())"

    init(bluetoothSource: Bool) throws {
      let defaults = try #require(UserDefaults(suiteName: suite))
      let container = try PersistenceStore.createContainer(inMemory: true)
      sockets = Sockets(first: bluetoothSource ? second : first, second: second)
      let sockets = sockets
      let pairing = MockAccessorySetupKitService()
      pairing.isSessionActive = false
      manager = ConnectionManager(
        modelContainer: container, defaults: defaults, stateMachine: MockBLEStateMachine(),
        transport: first, pairing: AccessorySetupPairingService(accessorySetupKit: pairing),
        makeWiFiTransport: { sockets.take() }
      )
      manager.testNotificationSystemOperations = firstNotifications.operations
      app = AppState(modelContainer: container, isPlaceholder: true, defaults: defaults, injectedConnectionManager: manager)
      manager.appStateProvider = Background()
    }

    func close() async {
      firstNotifications.releaseSnapshot()
      secondNotifications.releaseSnapshot()
      await first.releaseAllGates()
      await second.releaseAllGates()
      await app.disconnect(reason: .userInitiated)
      app.tearDownAppStateSessionState()
      app.shutdown()
      await manager.retirementTask?.value
      UserDefaults().removePersistentDomain(forName: suite)
    }
  }

  private struct Background: AppStateProvider {
    let isInForeground = false
  }

  private final class Sockets: Sendable {
    private let first: ConnectionTestRadioTransport
    private let second: ConnectionTestRadioTransport
    private let count = Mutex(0)

    init(first: ConnectionTestRadioTransport, second: ConnectionTestRadioTransport) {
      self.first = first
      self.second = second
    }

    func take() -> ConnectionTestRadioTransport {
      count.withLock { count in
        defer { count += 1 }
        return count == 0 ? first : second
      }
    }
  }

  @MainActor
  private final class Recorder {
    var requests: [UNNotificationRequest] = []
    var removed: [String] = []
    var badges: [Int] = []
    var holdNextSnapshot = false
    var snapshotIsHeld = false
    var waiter: CheckedContinuation<Void, Never>?

    func add(contactID: UUID, prefix: String) {
      for type in ["dm", "reaction"] {
        let content = UNMutableNotificationContent()
        content.userInfo = ["contactID": contactID.uuidString, "type": type]
        requests.append(UNNotificationRequest(identifier: "\(prefix)-\(type)", content: content, trigger: nil))
      }
    }

    func releaseSnapshot() {
      waiter?.resume()
      waiter = nil
      snapshotIsHeld = false
      holdNextSnapshot = false
    }

    var operations: NotificationService.SystemOperations {
      .init(
        setBadgeCount: { self.badges.append($0) },
        deliveredRequests: {
          if self.holdNextSnapshot {
            self.holdNextSnapshot = false
            self.snapshotIsHeld = true
            await withCheckedContinuation { self.waiter = $0 }
            self.snapshotIsHeld = false
          }
          return self.requests
        },
        removeDeliveredNotifications: { identifiers in
          self.removed.append(contentsOf: identifiers)
          self.requests.removeAll { identifiers.contains($0.identifier) }
        }
      )
    }
  }
}
