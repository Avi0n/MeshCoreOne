import MeshCore

/// Captures one attempt within a request, including same-request recovery replacements.
@MainActor
final class ConnectionAttempt {
  let target: ConnectionRequest.Target
  var identity: ConnectionDeviceIdentity
  let binding = SessionTransportBinding()
  var resource: ConnectionResource?
  var preparedServices: ServiceContainer?
  var ackEvents: AsyncStream<MeshEvent>?

  init(target: ConnectionRequest.Target) {
    self.target = target
    identity = ConnectionDeviceIdentity(deviceID: target.deviceID)
  }
}
