import Foundation

/// Represents configuration and state information for the local mesh node.
///
/// This information is typically retrieved from the device after the initial application startup sequence.
public struct SelfInfo: Sendable, Equatable {
  /// The type of advertisement used by the device.
  public let advertisementType: UInt8
  /// The current transmit power level in dBm (may be negative).
  public let txPower: Int8
  /// The maximum supported transmit power level in dBm.
  public let maxTxPower: Int8
  /// The node's 32-byte public key.
  public let publicKey: Data
  /// The current latitude coordinate.
  public let latitude: Double
  /// The current longitude coordinate.
  public let longitude: Double
  /// Whether multiple acknowledgments are enabled.
  public let multiAcks: UInt8
  /// The policy for location sharing in advertisements.
  public let advertisementLocationPolicy: UInt8
  /// The environment telemetry reporting mode.
  public let telemetryModeEnvironment: UInt8
  /// The location telemetry reporting mode.
  public let telemetryModeLocation: UInt8
  /// The base telemetry reporting mode.
  public let telemetryModeBase: UInt8
  /// Whether contacts must be added manually.
  public let manualAddContacts: Bool
  /// The radio center frequency in MHz.
  public let radioFrequency: Double
  /// The radio bandwidth in kHz.
  public let radioBandwidth: Double
  /// The radio spreading factor.
  public let radioSpreadingFactor: UInt8
  /// The radio coding rate.
  public let radioCodingRate: UInt8
  /// The user-defined name for this device.
  public let name: String

  /// Initializes a new self-info structure with the specified device parameters.
  public init(
    advertisementType: UInt8,
    txPower: Int8,
    maxTxPower: Int8,
    publicKey: Data,
    latitude: Double,
    longitude: Double,
    multiAcks: UInt8,
    advertisementLocationPolicy: UInt8,
    telemetryModeEnvironment: UInt8,
    telemetryModeLocation: UInt8,
    telemetryModeBase: UInt8,
    manualAddContacts: Bool,
    radioFrequency: Double,
    radioBandwidth: Double,
    radioSpreadingFactor: UInt8,
    radioCodingRate: UInt8,
    name: String
  ) {
    self.advertisementType = advertisementType
    self.txPower = txPower
    self.maxTxPower = maxTxPower
    self.publicKey = publicKey
    self.latitude = latitude
    self.longitude = longitude
    self.multiAcks = multiAcks
    self.advertisementLocationPolicy = advertisementLocationPolicy
    self.telemetryModeEnvironment = telemetryModeEnvironment
    self.telemetryModeLocation = telemetryModeLocation
    self.telemetryModeBase = telemetryModeBase
    self.manualAddContacts = manualAddContacts
    self.radioFrequency = radioFrequency
    self.radioBandwidth = radioBandwidth
    self.radioSpreadingFactor = radioSpreadingFactor
    self.radioCodingRate = radioCodingRate
    self.name = name
  }
}

/// Represents the hardware capabilities and firmware details of the mesh device.
public struct DeviceCapabilities: Sendable, Equatable {
  /// The numeric firmware version.
  public let firmwareVersion: UInt8
  /// The maximum number of contacts that can be stored on the device.
  public let maxContacts: Int
  /// The maximum number of channels supported by the device.
  public let maxChannels: Int
  /// The Bluetooth PIN used for pairing, if applicable.
  public let blePin: UInt32
  /// The firmware build identifier string.
  public let firmwareBuild: String
  /// The hardware model name.
  public let model: String
  /// The semantic version string.
  public let version: String
  /// Whether client repeat mode is enabled (v9+ firmware).
  public let clientRepeat: Bool
  /// The path hash mode (0=1-byte, 1=2-byte, 2=3-byte hashes). Firmware v10+.
  public let pathHashMode: UInt8

  /// The hash size per hop in bytes (1, 2, or 3), derived from ``pathHashMode``.
  public var hashSize: Int {
    Int(pathHashMode) + 1
  }

  /// Initializes a new device capabilities structure.
  public init(
    firmwareVersion: UInt8,
    maxContacts: Int,
    maxChannels: Int,
    blePin: UInt32,
    firmwareBuild: String,
    model: String,
    version: String,
    clientRepeat: Bool = false,
    pathHashMode: UInt8 = 0
  ) {
    self.firmwareVersion = firmwareVersion
    self.maxContacts = maxContacts
    self.maxChannels = maxChannels
    self.blePin = blePin
    self.firmwareBuild = firmwareBuild
    self.model = model
    self.version = version
    self.clientRepeat = clientRepeat
    self.pathHashMode = pathHashMode
  }
}

/// Represents the battery status and storage utilization of the device.
///
/// Per the MeshCore protocol, battery level is reported in millivolts rather than a percentage.
public struct BatteryInfo: Sendable, Equatable {
  /// The raw battery level in millivolts (e.g., 3700 for 3.7V).
  ///
  /// This value should be converted to a percentage based on the specific hardware model's battery curve.
  public let level: Int
  /// The amount of storage currently in use, in kilobytes.
  public let usedStorageKB: Int?
  /// The total storage capacity available on the device, in kilobytes.
  public let totalStorageKB: Int?

  /// Initializes a new battery info structure.
  ///
  /// - Parameters:
  ///   - level: Raw battery level in millivolts.
  ///   - usedStorageKB: Used storage in KB, if available.
  ///   - totalStorageKB: Total storage in KB, if available.
  public init(level: Int, usedStorageKB: Int? = nil, totalStorageKB: Int? = nil) {
    self.level = level
    self.usedStorageKB = usedStorageKB
    self.totalStorageKB = totalStorageKB
  }
}
