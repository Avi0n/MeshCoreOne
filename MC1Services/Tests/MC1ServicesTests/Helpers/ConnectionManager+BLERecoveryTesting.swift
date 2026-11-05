import Foundation
@testable import MC1Services

@MainActor
extension ConnectionManager {
  @discardableResult
  func seedBLERecoveryRequest(deviceID: UUID = UUID()) -> ConnectionRequest {
    if let desiredRequest, desiredRequest.target != .disconnected { return desiredRequest }
    let request = ConnectionRequest(
      targets: [.bluetooth(deviceID)], origin: .recovery,
      forceFullSync: false, forceReconnect: false, continuing: desiredRequest
    )
    desiredRequest = request
    return request
  }

  func finishSeededBLERecovery() async {
    let request = seedBLERecoveryRequest()
    guard let deviceID = request.target.deviceID else { return }
    request.handedOff = true
    let cycle = reconnectionCoordinator.currentCycle
      ?? reconnectionCoordinator.begin(deviceID: deviceID, requestID: request.id)
    await finishBLERecoveryFailure(cycle)
    if reconnectionCoordinator.isCurrent(cycle) { reconnectionCoordinator.clear() }
  }

  func acceptSeededBLELoss(deviceID: UUID? = nil, error: BLEError? = nil) async {
    let request = seedBLERecoveryRequest(deviceID: deviceID ?? connectedDevice?.id ?? UUID())
    let origin = BLELifecycleOrigin(
      deviceID: deviceID ?? request.target.deviceID ?? UUID(),
      generation: request.physicalOrigin?.generation ?? 1, eventTime: CFAbsoluteTimeGetCurrent()
    )
    await handleBLELifecycleEvent(.disconnected(origin, error: error))
  }
}
