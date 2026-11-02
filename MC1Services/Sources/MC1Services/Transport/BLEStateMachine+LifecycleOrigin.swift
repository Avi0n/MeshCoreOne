import Foundation

extension BLEStateMachine {
  var lifecycleOrigin: BLELifecycleOrigin? {
    guard let deviceID = phase.deviceID else { return nil }
    return BLELifecycleOrigin(deviceID: deviceID, generation: connectionGeneration, eventTime: connectionGenerationStartTime)
  }

  func setLifecycleEventHandler(_ handler: @escaping @Sendable (BLELifecycleEvent, AsyncStream<Data>?) -> Void) {
    onLifecycleEvent = handler
  }

  func notifyDisconnection(_ deviceID: UUID, _ error: Error?, eventTime: CFAbsoluteTime = CFAbsoluteTimeGetCurrent()) {
    activeSessionBinding?.revoke()
    let origin = BLELifecycleOrigin(deviceID: deviceID, generation: connectionGeneration, eventTime: eventTime)
    let classified = error.map { ($0 as? BLEError) ?? ReconnectPolicy.makeConnectionError($0) }
    onLifecycleEvent?(.disconnected(origin, error: classified), nil)
  }

  func notifyRemoteLinkLost(_ deviceID: UUID, _ eventTime: CFAbsoluteTime) {
    activeSessionBinding?.revoke()
    let origin = BLELifecycleOrigin(deviceID: deviceID, generation: connectionGeneration, eventTime: eventTime)
    onLifecycleEvent?(.remoteLinkLost(origin), nil)
  }

  func notifyAutoReconnecting(_ deviceID: UUID, _ errorInfo: String, eventTime: CFAbsoluteTime = CFAbsoluteTimeGetCurrent()) {
    activeSessionBinding?.revoke()
    let origin = BLELifecycleOrigin(deviceID: deviceID, generation: connectionGeneration, eventTime: eventTime)
    onLifecycleEvent?(.autoReconnecting(origin, errorInfo: errorInfo), nil)
  }

  func notifyReconnection(_ deviceID: UUID, _ stream: AsyncStream<Data>) {
    let origin = BLELifecycleOrigin(deviceID: deviceID, generation: connectionGeneration, eventTime: CFAbsoluteTimeGetCurrent())
    onLifecycleEvent?(.reconnected(origin), stream)
  }
}
