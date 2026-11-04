import Foundation

/// Fields a caller means to change in the other-params record.
/// Nil leaves a field untouched. An empty intent sends no command.
public struct OtherParamsIntent: Sendable, Equatable {
  public var manualAddContacts: Bool?
  public var telemetryModeBase: UInt8?
  public var telemetryModeLocation: UInt8?
  public var telemetryModeEnvironment: UInt8?
  public var advertisementLocationPolicy: UInt8?
  public var multiAcks: UInt8?

  public init(
    manualAddContacts: Bool? = nil,
    telemetryModeBase: UInt8? = nil,
    telemetryModeLocation: UInt8? = nil,
    telemetryModeEnvironment: UInt8? = nil,
    advertisementLocationPolicy: UInt8? = nil,
    multiAcks: UInt8? = nil
  ) {
    self.manualAddContacts = manualAddContacts
    self.telemetryModeBase = telemetryModeBase
    self.telemetryModeLocation = telemetryModeLocation
    self.telemetryModeEnvironment = telemetryModeEnvironment
    self.advertisementLocationPolicy = advertisementLocationPolicy
    self.multiAcks = multiAcks
  }

  /// Telemetry mode uses the low two bits. Higher bits are not a mode.
  public static let telemetryModeMask: UInt8 = 0b11

  public var isEmpty: Bool {
    manualAddContacts == nil
      && telemetryModeBase == nil
      && telemetryModeLocation == nil
      && telemetryModeEnvironment == nil
      && advertisementLocationPolicy == nil
      && multiAcks == nil
  }

  /// The telemetry bits the wire keeps. Nil fields stay nil, including after later mutation.
  public func normalized() -> OtherParamsIntent {
    var intent = self
    intent.telemetryModeBase = telemetryModeBase.map { $0 & Self.telemetryModeMask }
    intent.telemetryModeLocation = telemetryModeLocation.map { $0 & Self.telemetryModeMask }
    intent.telemetryModeEnvironment = telemetryModeEnvironment.map { $0 & Self.telemetryModeMask }
    return intent
  }

  /// Applies the supplied fields. Telemetry uses `telemetryModeMask`; the advertisement policy byte is stored as given.
  public func applied(to config: OtherParamsConfig) -> OtherParamsConfig {
    let intent = normalized()
    var updated = config
    if let manualAddContacts = intent.manualAddContacts {
      updated.manualAddContacts = manualAddContacts
    }
    if let telemetryModeBase = intent.telemetryModeBase {
      updated.telemetryModeBase = telemetryModeBase
    }
    if let telemetryModeLocation = intent.telemetryModeLocation {
      updated.telemetryModeLocation = telemetryModeLocation
    }
    if let telemetryModeEnvironment = intent.telemetryModeEnvironment {
      updated.telemetryModeEnvironment = telemetryModeEnvironment
    }
    if let advertisementLocationPolicy = intent.advertisementLocationPolicy {
      updated.advertisementLocationPolicy = advertisementLocationPolicy
    }
    if let multiAcks = intent.multiAcks {
      updated.multiAcks = multiAcks
    }
    return updated
  }
}
