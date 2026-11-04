import Foundation

/// The installed stack captured by a connection callback or caller.
@MainActor
public struct ConnectionSnapshot {
  public let generation: UUID
  public let requestID: UUID
  public let device: DeviceDTO
  public let services: ServiceContainer
  public let transportType: TransportType
}
