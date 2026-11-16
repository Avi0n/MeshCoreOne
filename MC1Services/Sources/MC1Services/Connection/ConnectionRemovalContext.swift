import Foundation

/// Captures a removal subject and the request that may retire its connection.
public struct ConnectionRemovalContext: Sendable {
  let identity: ConnectionDeviceIdentity
  let deviceID: UUID
  let requestID: UUID?
  let generation: UUID?

  init(deviceID: UUID, device: DeviceDTO?, requestID: UUID?, generation: UUID?) {
    self.deviceID = deviceID
    identity = device.map(ConnectionDeviceIdentity.init) ?? ConnectionDeviceIdentity(deviceID: deviceID)
    self.requestID = requestID
    self.generation = generation
  }

  init(deviceID: UUID, identity: ConnectionDeviceIdentity, requestID: UUID?, generation: UUID?) {
    self.deviceID = deviceID
    self.identity = identity
    self.requestID = requestID
    self.generation = generation
  }
}
