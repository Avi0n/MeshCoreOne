import Foundation

public struct DeviceGPSState: Sendable, Equatable {
  public let isSupported: Bool
  public let isEnabled: Bool

  public init(isSupported: Bool, isEnabled: Bool) {
    self.isSupported = isSupported
    self.isEnabled = isEnabled
  }
}
