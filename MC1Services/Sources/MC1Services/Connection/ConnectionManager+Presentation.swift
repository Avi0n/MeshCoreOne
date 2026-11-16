import Foundation

public extension ConnectionManager {
  var connectionActivity: ConnectionActivity? {
    guard connectionIntent.wantsConnection, let request = desiredRequest,
          request.target != .disconnected else { return nil }
    let recovering = request.handedOff || request.committedGeneration != nil || request.origin == .recovery
    let phase: ConnectionActivity.Phase = switch connectionState {
    case .disconnected: .waitingToReconnect
    case .connecting: recovering ? .reconnecting : .connecting
    case .connected: .connected
    case .syncing: .syncing
    case .ready: .ready
    }
    return ConnectionActivity(requestID: request.id, targetName: connectionTargetName(request), phase: phase)
  }

  @discardableResult
  func disconnect(requestID: UUID, reason: DisconnectReason = .userInitiated) async -> ConnectionLossReceipt? {
    guard let request = desiredRequest, request.id == requestID, connectionIntent.wantsConnection,
          request.target != .disconnected else { return nil }
    return await disconnect(reason: reason)
  }

  private func connectionTargetName(_ request: ConnectionRequest) -> String? {
    if let link = installedLink, link.requestID == request.id { return link.device.nodeName }
    switch request.target {
    case let .bluetooth(deviceID):
      if lastConnectedDeviceID == deviceID, let name = lastConnectedDeviceName { return name }
      return pairedAccessoryInfos.first { $0.id == deviceID }?.name
    case let .wifi(host, _): return host
    case .simulator: return MockDataProvider.simulatorDevice.nodeName
    case .disconnected: return nil
    }
  }
}
