import Foundation

extension ConnectionManager {
  func lifecycleContext(
    request: ConnectionRequest?, attempt: ConnectionAttempt?, link: ConnectionLink?, resource: ConnectionResource? = nil
  ) -> String {
    let resource = resource ?? link?.resource ?? attempt?.resource
    let attempt = attempt.flatMap { candidate in
      resource == nil || candidate.binding === resource?.binding ? candidate : nil
    }
    let binding = resource?.binding ?? attempt?.binding
    let requestID = request?.id ?? link?.requestID
    let origin = link != nil && attempt != nil ? request?.physicalOrigin : nil
    let cycle = reconnectionCoordinator.currentCycle.flatMap { $0.requestID == requestID ? $0 : nil }
    let deviceID = link?.device.id ?? attempt?.identity.deviceID ?? origin?.deviceID ?? request?.target.deviceID
    let target = link?.target ?? attempt?.target ?? request?.target
    let transport = target == .disconnected ? nil : target?.transportType
    return "request=\(requestID?.uuidString ?? "none"), "
      + "attempt=\(attempt.map { String(describing: ObjectIdentifier($0)) } ?? "none"), "
      + "link=\(link?.generation.uuidString ?? "none"), "
      + "binding=\(binding.map { String(describing: ObjectIdentifier($0)) } ?? "none"), "
      + "bindingActive=\(binding.map { String($0.isActive) } ?? "none"), "
      + "session=\(resource.map { String(describing: ObjectIdentifier($0.session)) } ?? "none"), "
      + "transport=\(transport.map { String(describing: $0) } ?? "none"), "
      + "device=\(deviceID.map { String($0.uuidString.prefix(8)) } ?? "none"), "
      + "physicalGeneration=\(origin.map { String($0.generation) } ?? "none"), "
      + "physicalEventTime=\(origin.map { String($0.eventTime) } ?? "none"), "
      + "reconnectCycle=\(cycle?.id.uuidString ?? "none"), "
      + "reconnectGeneration=\(cycle.map { String($0.generation) } ?? "none")"
  }

  func logLifecycle(_ event: String, context: String, reason: String? = nil) {
    let binding = installedLink?.resource.binding ?? desiredRequest?.binding
    logger.info(
      "[ConnectionLifecycle] event=\(event), \(context), "
        + "currentRequest=\(desiredRequest?.id.uuidString ?? "none"), "
        + "currentLink=\(installedLink?.generation.uuidString ?? "none"), "
        + "currentBinding=\(binding.map { String(describing: ObjectIdentifier($0)) } ?? "none"), "
        + "state=\(String(describing: connectionState))"
        + (reason.map { ", reason=\($0)" } ?? "")
    )
  }
}
