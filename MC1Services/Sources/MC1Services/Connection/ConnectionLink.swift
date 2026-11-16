import Foundation

@MainActor
final class ConnectionLink {
  let generation = UUID()
  let requestID: UUID
  let target: ConnectionRequest.Target
  let resource: ConnectionResource
  let services: ServiceContainer
  var device: DeviceDTO
  let synthetic: Bool
  var setupWorker: Task<Void, Never>?
  var completionWorker: Task<Bool, Never>?
  var listenerRepairWorker: Task<Bool, Never>?
  var completionID: UUID?
  var usableSyncResult: FullSyncResult?
  var timeEffectsClaimed = false
  var didCompleteSync = false

  var snapshot: ConnectionSnapshot {
    ConnectionSnapshot(
      generation: generation,
      requestID: requestID,
      device: device,
      services: services,
      transportType: target.transportType
    )
  }

  init(
    requestID: UUID,
    target: ConnectionRequest.Target,
    resource: ConnectionResource,
    services: ServiceContainer,
    device: DeviceDTO,
    synthetic: Bool = false
  ) {
    self.requestID = requestID
    self.target = target
    self.resource = resource
    self.services = services
    self.device = device
    self.synthetic = synthetic
  }
}
