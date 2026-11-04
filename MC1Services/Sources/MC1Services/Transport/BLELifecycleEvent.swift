import Foundation

public enum BLELifecycleEvent: Sendable {
  case autoReconnecting(BLELifecycleOrigin, errorInfo: String)
  case reconnected(BLELifecycleOrigin)
  case disconnected(BLELifecycleOrigin, error: BLEError?)
  case remoteLinkLost(BLELifecycleOrigin)

  public var origin: BLELifecycleOrigin {
    switch self {
    case let .autoReconnecting(origin, _), let .reconnected(origin),
         let .disconnected(origin, _), let .remoteLinkLost(origin):
      origin
    }
  }
}
