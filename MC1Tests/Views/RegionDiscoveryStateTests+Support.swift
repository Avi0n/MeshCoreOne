import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore

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

    init() throws {
      suiteName = "region-discovery-\(UUID().uuidString)"
      guard let defaults = UserDefaults(suiteName: suiteName) else {
        throw DiscoveryFixtureError.defaultsUnavailable
      }
      defaults.removePersistentDomain(forName: suiteName)
      self.defaults = defaults
      appState = AppState(defaults: defaults)
      sessions = SessionBox(MeshCoreSession(transport: MockTransport()))
      install(device: device())
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

    func install(device: DeviceDTO) {
      appState.connectionManager.setTestState(
        connectionState: .ready,
        services: .some(nil),
        session: .some(sessions.session),
        connectedDevice: .some(device)
      )
    }

    func replaceSession() {
      sessions.session = MeshCoreSession(transport: MockTransport())
      appState.connectionManager.setTestState(session: .some(sessions.session))
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

    func tearDown() {
      appState.shutdown()
      defaults.removePersistentDomain(forName: suiteName)
    }
  }

  enum Wait {
    static let yieldLimit = 50
  }

  enum DiscoveryFixtureError: Error {
    case defaultsUnavailable
  }
}
