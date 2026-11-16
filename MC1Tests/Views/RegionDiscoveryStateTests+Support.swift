import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

extension RegionDiscoveryStateTests {
  @MainActor
  final class CallCount {
    var count = 0
  }

  @MainActor
  final class DiscoveryGate {
    private var waiters: [CheckedContinuation<RegionDiscoveryService.Outcome, Never>] = []

    func wait() async -> RegionDiscoveryService.Outcome {
      await withCheckedContinuation { waiters.append($0) }
    }

    func untilWaiting(_ count: Int) async -> Bool {
      for _ in 0..<Wait.yieldLimit where waiters.count < count {
        await Task.yield()
      }
      return waiters.count >= count
    }

    func resumeFirst(_ outcome: RegionDiscoveryService.Outcome) {
      guard !waiters.isEmpty else { return }
      waiters.removeFirst().resume(returning: outcome)
    }

    func resumeLast(_ outcome: RegionDiscoveryService.Outcome) {
      guard !waiters.isEmpty else { return }
      waiters.removeLast().resume(returning: outcome)
    }

    func resumeAll(_ outcome: RegionDiscoveryService.Outcome) {
      let pending = waiters
      waiters.removeAll()
      for waiter in pending {
        waiter.resume(returning: outcome)
      }
    }
  }

  @MainActor
  final class SessionBox {
    var session: MeshCoreSession

    init(_ session: MeshCoreSession) {
      self.session = session
    }
  }

  @MainActor
  final class DiscoveryFixture {
    let appState: AppState
    let owner = RegionDiscoveryState()
    let radioID = UUID()
    let sessions: SessionBox
    private let defaults: UserDefaults
    private let suiteName: String
    private var containers: [ServiceContainer] = []
    private var ownedSessions: [MeshCoreSession] = []
    private var startups: [Task<Void, Error>] = []

    init() async throws {
      suiteName = "region-discovery-\(UUID().uuidString)"
      guard let defaults = UserDefaults(suiteName: suiteName) else {
        throw DiscoveryFixtureError.defaultsUnavailable
      }
      defaults.removePersistentDomain(forName: suiteName)
      self.defaults = defaults
      let modelContainer = try PersistenceStore.createContainer(inMemory: true)
      let manager = ConnectionManager(
        modelContainer: modelContainer, defaults: defaults, stateMachine: MockBLEStateMachine(), transport: MockMeshTransport()
      )
      appState = AppState(
        modelContainer: modelContainer, isPlaceholder: true, defaults: defaults, injectedConnectionManager: manager
      )
      sessions = SessionBox(MeshCoreSession(transport: MockTransport()))
      do {
        try await install(device: device())
      } catch {
        await tearDown()
        throw error
      }
      let box = sessions
      let state = appState
      owner.configure(
        session: { box.session },
        contactService: { nil },
        connectedDevice: { state.connectedDevice },
        connectionState: { state.connectionState },
        connectionManager: state.connectionManager,
        dataStore: nil
      )
    }

    func install(device: DeviceDTO) async throws {
      let store = appState.connectionManager.persistenceStore
      try await store.saveDevice(device)
      let services = ServiceContainer(session: sessions.session, dataStore: store, radioID: device.radioID)
      containers.append(services)
      appState.connectionManager.setTestState(
        connectionState: .ready,
        services: .some(services),
        session: .some(sessions.session),
        connectedDevice: .some(device),
        currentTransportType: .bluetooth,
        connectionIntent: .wantsConnection()
      )
    }

    func replaceSession() async throws {
      sessions.session = MeshCoreSession(transport: MockTransport())
      try await install(device: #require(appState.connectedDevice))
    }

    func own(session: MeshCoreSession, startup: Task<Void, Error>) {
      ownedSessions.append(session)
      startups.append(startup)
    }

    func device(
      name: String = "Relay",
      radioID: UUID? = nil,
      regions: [String] = []
    ) -> DeviceDTO {
      DeviceDTO(
        id: UUID(),
        radioID: radioID ?? self.radioID,
        publicKey: Data(repeating: 0xB1, count: ProtocolLimits.publicKeySize),
        nodeName: name,
        firmwareVersion: 11,
        firmwareVersionString: "1.15.0",
        manufacturerName: "Test",
        buildDate: "2025-01-01",
        maxContacts: 100,
        maxChannels: 8,
        frequency: 915_000,
        bandwidth: 250_000,
        spreadingFactor: 10,
        codingRate: 5,
        txPower: 20,
        maxTxPower: 20,
        latitude: 0,
        longitude: 0,
        blePin: 0,
        manualAddContacts: false,
        multiAcks: 2,
        telemetryModeBase: 2,
        telemetryModeLoc: 0,
        telemetryModeEnv: 0,
        advertLocationPolicy: 0,
        lastConnected: Date(),
        lastContactSync: 0,
        isActive: true,
        ocvPreset: nil,
        customOCVArrayString: nil,
        knownRegions: regions
      )
    }

    func flush() async {
      for _ in 0..<Wait.yieldLimit {
        await Task.yield()
      }
    }

    func tearDown() async {
      owner.setWorkflowActive(false)
      startups.forEach { $0.cancel() }
      for session in ownedSessions {
        await session.stop()
      }
      for startup in startups {
        _ = await startup.result
      }
      await appState.disconnect(reason: .userInitiated)
      for container in containers {
        await container.tearDown()
        await container.session.stop()
      }
      appState.shutdown()
      defaults.removePersistentDomain(forName: suiteName)
    }
  }

  func withFixture(_ body: @MainActor (DiscoveryFixture) async throws -> Void) async throws {
    let fixture = try await DiscoveryFixture()
    do {
      try await body(fixture)
    } catch {
      await fixture.tearDown()
      throw error
    }
    await fixture.tearDown()
  }

  enum Wait {
    static let yieldLimit = 50
  }

  enum DiscoveryFixtureError: Error {
    case defaultsUnavailable
  }
}
