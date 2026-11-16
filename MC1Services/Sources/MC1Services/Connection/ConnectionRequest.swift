import Foundation
import MeshCore

@MainActor
final class ConnectionRequest {
  enum Target: Equatable {
    case bluetooth(UUID)
    case wifi(host: String, port: UInt16)
    case simulator
    case disconnected

    var deviceID: UUID? {
      if case let .bluetooth(id) = self { return id }
      if case .simulator = self { return MockDataProvider.simulatorDeviceID }
      return nil
    }

    var transportType: TransportType {
      if case .wifi = self { return .wifi }
      return .bluetooth
    }

    var method: ConnectionMethod? {
      switch self {
      case let .bluetooth(id): .bluetooth(peripheralUUID: id, displayName: nil)
      case let .wifi(host, port): .wifi(host: host, port: port, displayName: nil)
      case .simulator, .disconnected: nil
      }
    }
  }

  enum Origin {
    case explicit
    case pairing
    case launch
    case recovery
    case simulator
  }

  let id = UUID()
  let radioCLICorrelationScope = RadioCLICorrelationScope()
  let admittedAt = CFAbsoluteTimeGetCurrent()
  let targets: [Target]
  let origin: Origin
  var forceFullSync: Bool
  let forceReconnect: Bool
  var targetIndex = 0
  var attempt: ConnectionAttempt?
  var binding: SessionTransportBinding? {
    attempt?.binding
  }

  /// Binding the admission worker installed. Caller cancellation revokes this binding.
  var admissionBinding: SessionTransportBinding?

  var worker: Task<ConnectionResult, Error>?
  var committedGeneration: UUID?
  var physicalOrigin: BLELifecycleOrigin?
  var handedOff = false
  var removedIdentities: [ConnectionDeviceIdentity]

  var target: Target {
    targets[targetIndex]
  }

  init(
    targets: [Target], origin: Origin, forceFullSync: Bool, forceReconnect: Bool,
    continuing previous: ConnectionRequest?
  ) {
    precondition(!targets.isEmpty)
    self.targets = targets
    self.origin = origin
    self.forceFullSync = forceFullSync
    self.forceReconnect = forceReconnect
    // Recovery may replace its request while continuing the same user choice.
    // Removed identities remain excluded until a fresh choice is admitted.
    removedIdentities = previous?.removedIdentities ?? []
  }
}
