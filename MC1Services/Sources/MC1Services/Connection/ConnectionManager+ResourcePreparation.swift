import Foundation
import MeshCore

extension ConnectionManager {
  func openConnectionResource(
    _ request: ConnectionRequest, attempt: ConnectionAttempt, preservePhysicalBLE: Bool
  ) async throws -> ConnectionResource {
    let binding = attempt.binding
    let physical: any MeshTransport
    let wifi: (any WiFiMeshTransport)?
    let view: SessionBoundTransport
    switch attempt.target {
    case let .bluetooth(deviceID):
      if pairing.isSessionActive, !pairing.isDeviceConnectable(deviceID) { throw ConnectionError.deviceNotFound }
      guard shouldAllowConnection(force: request.forceReconnect) else {
        throw BLEError.connectionFailed("Connection blocked by circuit breaker (cooling down)")
      }
      view = await transport.makeSessionTransport(deviceID: deviceID, binding: binding)
      try requireAttemptOwnership(request, attempt)
      if preservePhysicalBLE { await transport.refreshDataStream(sessionBinding: binding) }
      try requireAttemptOwnership(request, attempt)
      physical = transport
      wifi = nil
    case let .wifi(host, port):
      let socket = makeWiFiTransport()
      await socket.setConnectionInfo(host: host, port: port)
      try requireAttemptOwnership(request, attempt)
      physical = socket
      wifi = socket
      view = SessionBoundTransport(transport: socket, binding: binding)
    case .simulator, .disconnected:
      preconditionFailure("Simulator has its own synthetic preparation")
    }
    let newSession = makeBoundSession(transport: view, scope: request.radioCLICorrelationScope)
    let resource = ConnectionResource(
      binding: binding, transport: physical, session: newSession, wifi: wifi,
      ble: attempt.target.transportType == .bluetooth ? transport : nil
    )
    attempt.resource = resource
    await wireSocketLoss(resource)
    try requireAttemptOwnership(request, attempt)
    publishPending(request, phase: .connecting)
    try await view.connect()
    try requireAttemptOwnership(request, attempt)
    if attempt.target.transportType == .bluetooth {
      let origin = await transport.lifecycleOrigin
      try requireAttemptOwnership(request, attempt)
      request.physicalOrigin = origin
    }
    try requireAttemptOwnership(request, attempt)
    publishPending(request, phase: .connected)
    return resource
  }
}
