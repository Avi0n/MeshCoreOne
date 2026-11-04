import Foundation

/// Physical provenance captured when the BLE actor accepts a lifecycle event.
public struct BLELifecycleOrigin: Sendable, Equatable {
  public let deviceID: UUID
  public let generation: UInt64
  public let eventTime: CFAbsoluteTime

  public init(deviceID: UUID, generation: UInt64, eventTime: CFAbsoluteTime) {
    self.deviceID = deviceID
    self.generation = generation
    self.eventTime = eventTime
  }
}
