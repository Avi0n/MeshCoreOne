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
    await installFloodScopeRecovery(on: resource, requestID: request.id)
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

  /// Schedules recovery without awaiting it because retirement waits for the command slot.
  func installFloodScopeRecovery(on resource: ConnectionResource, requestID: UUID) async {
    await resource.session.setFloodScopeRecoveryHandler { [weak self, weak resource] in
      Task { @MainActor [weak self, weak resource] in
        guard let self, let resource else { return }
        await self.recoverFloodScope(resource: resource, requestID: requestID)
      }
    }
  }

  func recoverFloodScope(resource: ConnectionResource, requestID: UUID) async {
    while let link = installedLink,
          link.resource === resource,
          link.requestID == requestID,
          desiredRequest?.id == requestID,
          connectionIntent.wantsConnection {
      if powerOffBlocksReconnect {
        // Power Off owns reconnect until it settles. Revalidate the link if it fails.
        guard let worker = powerOffOperation?.worker, case .failure = await worker.result else { return }
        continue
      }
      do {
        _ = try await requestConnection(
          targets: [link.target], origin: .recovery, forceFullSync: false, forceReconnect: true
        )
      } catch {
        logger.warning("Flood scope recovery failed: \(error.localizedDescription)")
      }
      return
    }
  }
}
