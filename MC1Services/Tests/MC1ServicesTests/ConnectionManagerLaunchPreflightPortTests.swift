import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Launch activation preflight ownership")
@MainActor
struct ConnectionManagerLaunchPreflightPortTests {
  enum Outcome: CaseIterable {
    case success
    case failure
    case cancellation
  }

  enum Choice: CaseIterable {
    case sameRadio
    case differentRadio
    case disconnect
  }

  @Test(.timeLimit(.minutes(1)), arguments: Outcome.allCases, Choice.allCases)
  func `held launch activation result cannot dispatch after a newer explicit choice`(outcome: Outcome, choice: Choice) async throws {
    let suite = "test.launch.preflight.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suite))
    let pairing = Pairing(outcome: outcome)
    let ble = ConnectionTestRadioTransport()
    let stateMachine = MockBLEStateMachine()
    let manager = try ConnectionManager(
      modelContainer: PersistenceStore.createContainer(inMemory: true), defaults: defaults,
      stateMachine: stateMachine, transport: ble, pairing: pairing
    )
    let saved = DeviceDTO.testDevice().copy {
      $0.connectionMethods = [.bluetooth(peripheralUUID: $0.id, displayName: nil)]
    }
    try await manager.persistenceStore.saveDevice(saved)
    manager.testLastConnectedDeviceID = saved.id
    manager.setTestState(connectionIntent: .wantsConnection())
    let activation = Task { await manager.activate() }
    do {
      try await waitUntil("launch must suspend in its actual pairing activation preflight") { pairing.isWaiting }
      let current: ConnectionSnapshot?
      switch choice {
      case .sameRadio, .differentRadio:
        let target = choice == .sameRadio ? saved.id : UUID()
        if choice == .differentRadio { await ble.setPublicKey(Data(repeating: 2, count: 32), for: target) }
        let result = try await manager.connect(to: target, forceFullSync: true, forceReconnect: true)
        #expect(manager.isCurrent(result))
        current = try #require(manager.currentConnection)
        #expect((current?.device.radioID == saved.radioID) == (choice == .sameRadio))
      case .disconnect:
        _ = await manager.disconnect(reason: .userInitiated)
        current = nil
      }
      let expectedRequest = manager.connectionRequestID
      let expectedState = manager.connectionState
      let expectedIntent = manager.connectionIntent
      let connects = await ble.connectCount
      let closes = await ble.disconnectCount
      if outcome == .cancellation { activation.cancel() }
      pairing.release()
      await activation.value
      #expect(manager.connectionRequestID == expectedRequest)
      #expect(manager.connectionState == expectedState)
      #expect(manager.connectionIntent == expectedIntent)
      #expect(await ble.connectCount == connects)
      #expect(await ble.disconnectCount == closes)
      #expect(!manager.isReconnectionWatchdogRunning)
      if let current {
        #expect(manager.isCurrent(current))
        #expect(try await current.services.session.getTime().timeIntervalSince1970 > 0)
      } else {
        #expect(manager.currentConnection == nil)
        #expect(manager.connectionIntent.isUserDisconnected)
        #expect(await ble.sentData.isEmpty)
      }
    } catch {
      pairing.release()
      activation.cancel()
      await activation.value
      await ble.releaseAllGates()
      _ = await manager.disconnect(reason: .userInitiated)
      await manager.retirementTask?.value
      await manager.metadataCommitTask?.value
      UserDefaults().removePersistentDomain(forName: suite)
      throw error
    }
    _ = await manager.disconnect(reason: .userInitiated)
    await manager.retirementTask?.value
    await manager.metadataCommitTask?.value
    UserDefaults().removePersistentDomain(forName: suite)
  }

  @MainActor
  private final class Pairing: DevicePairingService {
    enum Failure: Error { case unavailable }
    weak var delegate: (any DevicePairingDelegate)?
    let isSessionActive = false
    let registeredDeviceCount = 0
    let hasSystemPairingRegistry = false
    let supportsSystemRename = false
    private let outcome: Outcome
    private var continuation: CheckedContinuation<Void, Never>?
    private var isOpen = false
    var isWaiting: Bool {
      continuation != nil
    }

    init(outcome: Outcome) {
      self.outcome = outcome
    }

    func activate() async throws {
      if !isOpen { await withCheckedContinuation { continuation = $0 } }
      switch outcome {
      case .success: return
      case .failure: throw Failure.unavailable
      case .cancellation: throw CancellationError()
      }
    }

    func release() {
      isOpen = true
      continuation?.resume()
      continuation = nil
    }

    func discoverDevice() async throws -> UUID {
      throw CancellationError()
    }

    func isDeviceConnectable(_ id: UUID) -> Bool {
      true
    }

    func registeredDeviceInfos() -> [(id: UUID, name: String)] {
      []
    }

    func removeDevice(_ id: UUID) async throws {}
    func renameDevice(_ id: UUID) async throws {}
    func clearStaleRegistrations() async {}
  }
}
