import Foundation

/// Configuration struct for device "other params" settings.
///
/// Used by granular configuration setters to implement read-modify-write pattern.
public struct OtherParamsConfig: Sendable {
  public var manualAddContacts: Bool
  public var telemetryModeBase: UInt8
  public var telemetryModeLocation: UInt8
  public var telemetryModeEnvironment: UInt8
  public var advertisementLocationPolicy: UInt8
  public var multiAcks: UInt8

  /// Creates a new configuration with default values.
  public init(
    manualAddContacts: Bool = false,
    telemetryModeBase: UInt8 = 0,
    telemetryModeLocation: UInt8 = 0,
    telemetryModeEnvironment: UInt8 = 0,
    advertisementLocationPolicy: UInt8 = 0,
    multiAcks: UInt8 = 0
  ) {
    self.manualAddContacts = manualAddContacts
    self.telemetryModeBase = telemetryModeBase
    self.telemetryModeLocation = telemetryModeLocation
    self.telemetryModeEnvironment = telemetryModeEnvironment
    self.advertisementLocationPolicy = advertisementLocationPolicy
    self.multiAcks = multiAcks
  }

  /// Creates a configuration from existing device information.
  init(from selfInfo: SelfInfo) {
    manualAddContacts = selfInfo.manualAddContacts
    telemetryModeBase = selfInfo.telemetryModeBase
    telemetryModeLocation = selfInfo.telemetryModeLocation
    telemetryModeEnvironment = selfInfo.telemetryModeEnvironment
    advertisementLocationPolicy = selfInfo.advertisementLocationPolicy
    multiAcks = selfInfo.multiAcks
  }
}
