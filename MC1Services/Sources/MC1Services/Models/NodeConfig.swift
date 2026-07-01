import Foundation

/// JSON-serializable node configuration, compatible with MeshCore companion app format.
public struct MeshCoreNodeConfig: Codable, Sendable, Equatable {
  public var name: String?
  public var publicKey: String?
  public var privateKey: String?
  public var radioSettings: RadioSettings?
  public var positionSettings: PositionSettings?
  public var otherSettings: OtherSettings?
  public var channels: [ChannelConfig]?
  public var contacts: [ContactConfig]?

  public init(
    name: String? = nil,
    publicKey: String? = nil,
    privateKey: String? = nil,
    radioSettings: RadioSettings? = nil,
    positionSettings: PositionSettings? = nil,
    otherSettings: OtherSettings? = nil,
    channels: [ChannelConfig]? = nil,
    contacts: [ContactConfig]? = nil
  ) {
    self.name = name
    self.publicKey = publicKey
    self.privateKey = privateKey
    self.radioSettings = radioSettings
    self.positionSettings = positionSettings
    self.otherSettings = otherSettings
    self.channels = channels
    self.contacts = contacts
  }

  enum CodingKeys: String, CodingKey {
    case name
    case publicKey = "public_key"
    case privateKey = "private_key"
    case radioSettings = "radio_settings"
    case positionSettings = "position_settings"
    case otherSettings = "other_settings"
    case channels
    case contacts
  }
}

// MARK: - Radio Settings

public extension MeshCoreNodeConfig {
  struct RadioSettings: Codable, Sendable, Equatable {
    /// Frequency in kHz (e.g. 910525 = 910.525 MHz)
    public var frequency: UInt32
    /// Bandwidth in Hz (e.g. 62500 = 62.5 kHz)
    public var bandwidth: UInt32
    public var spreadingFactor: UInt8
    public var codingRate: UInt8
    /// Transmit power in dBm (may be negative)
    public var txPower: Int8

    public init(
      frequency: UInt32,
      bandwidth: UInt32,
      spreadingFactor: UInt8,
      codingRate: UInt8,
      txPower: Int8
    ) {
      self.frequency = frequency
      self.bandwidth = bandwidth
      self.spreadingFactor = spreadingFactor
      self.codingRate = codingRate
      self.txPower = txPower
    }

    // swiftlint:disable:next nesting
    enum CodingKeys: String, CodingKey {
      case frequency, bandwidth
      case spreadingFactor = "spreading_factor"
      case codingRate = "coding_rate"
      case txPower = "tx_power"
    }
  }
}

// MARK: - Position Settings

public extension MeshCoreNodeConfig {
  struct PositionSettings: Codable, Sendable, Equatable {
    public var latitude: String
    public var longitude: String

    public init(latitude: String, longitude: String) {
      self.latitude = latitude
      self.longitude = longitude
    }

    /// Both lat and lon are zero (likely unset).
    public var isZero: Bool {
      (Double(latitude) ?? 0) == 0 && (Double(longitude) ?? 0) == 0
    }
  }
}

// MARK: - Other Settings

public extension MeshCoreNodeConfig {
  /// Other device parameters. Only `manual_add_contacts` and `advert_location_policy`
  /// are exported, matching the official companion app format. All 7 fields are decoded
  /// on import for forward compatibility.
  struct OtherSettings: Codable, Sendable, Equatable {
    public var manualAddContacts: UInt8?
    public var advertLocationPolicy: UInt8?
    public var telemetryModeBase: UInt8?
    public var telemetryModeLocation: UInt8?
    public var telemetryModeEnvironment: UInt8?
    public var multiAcks: UInt8?
    public var advertisementType: UInt8?

    public init(
      manualAddContacts: UInt8? = nil,
      advertLocationPolicy: UInt8? = nil,
      telemetryModeBase: UInt8? = nil,
      telemetryModeLocation: UInt8? = nil,
      telemetryModeEnvironment: UInt8? = nil,
      multiAcks: UInt8? = nil,
      advertisementType: UInt8? = nil
    ) {
      self.manualAddContacts = manualAddContacts
      self.advertLocationPolicy = advertLocationPolicy
      self.telemetryModeBase = telemetryModeBase
      self.telemetryModeLocation = telemetryModeLocation
      self.telemetryModeEnvironment = telemetryModeEnvironment
      self.multiAcks = multiAcks
      self.advertisementType = advertisementType
    }

    // swiftlint:disable:next nesting
    enum CodingKeys: String, CodingKey {
      case manualAddContacts = "manual_add_contacts"
      case advertLocationPolicy = "advert_location_policy"
      case telemetryModeBase = "telemetry_mode_base"
      case telemetryModeLocation = "telemetry_mode_location"
      case telemetryModeEnvironment = "telemetry_mode_environment"
      case multiAcks = "multi_acks"
      case advertisementType = "advertisement_type"
    }
  }
}

// MARK: - Channel Config

public extension MeshCoreNodeConfig {
  struct ChannelConfig: Codable, Sendable, Equatable {
    public var name: String
    /// Hex-encoded 16-byte secret (32 hex characters).
    public var secret: String

    public init(name: String, secret: String) {
      self.name = name
      self.secret = secret
    }
  }
}

// MARK: - Contact Config

public extension MeshCoreNodeConfig {
  struct ContactConfig: Codable, Sendable, Equatable {
    public var type: UInt8
    public var name: String
    /// App-local nickname (not part of wire protocol).
    /// Decoded from JSON for companion app compatibility but not imported to firmware.
    public var customName: String?
    /// Hex-encoded 32-byte public key (64 hex characters)
    public var publicKey: String
    public var flags: UInt8
    public var latitude: String
    public var longitude: String
    public var lastAdvert: UInt32
    /// Read-only; firmware sets this value
    public var lastModified: UInt32
    /// Hex-encoded path data, or nil for no path
    public var outPath: String?
    /// Path hash mode (0=1-byte, 1=2-byte, 2=3-byte). Nil in configs from older versions.
    public var pathHashMode: UInt8?

    public init(
      type: UInt8,
      name: String,
      customName: String? = nil,
      publicKey: String,
      flags: UInt8,
      latitude: String,
      longitude: String,
      lastAdvert: UInt32,
      lastModified: UInt32,
      outPath: String? = nil,
      pathHashMode: UInt8? = nil
    ) {
      self.type = type
      self.name = name
      self.customName = customName
      self.publicKey = publicKey
      self.flags = flags
      self.latitude = latitude
      self.longitude = longitude
      self.lastAdvert = lastAdvert
      self.lastModified = lastModified
      self.outPath = outPath
      self.pathHashMode = pathHashMode
    }

    // swiftlint:disable:next nesting
    enum CodingKeys: String, CodingKey {
      case type, name, flags, latitude, longitude
      case customName = "custom_name"
      case publicKey = "public_key"
      case lastAdvert = "last_advert"
      case lastModified = "last_modified"
      case outPath = "out_path"
      case pathHashMode = "path_hash_mode"
    }

    /// Encodes with explicit `null` for nil `customName` and `outPath`,
    /// matching the official companion app JSON format.
    public func encode(to encoder: Encoder) throws {
      var container = encoder.container(keyedBy: CodingKeys.self)
      try container.encode(type, forKey: .type)
      try container.encode(name, forKey: .name)
      try container.encode(customName, forKey: .customName)
      try container.encode(publicKey, forKey: .publicKey)
      try container.encode(flags, forKey: .flags)
      try container.encode(latitude, forKey: .latitude)
      try container.encode(longitude, forKey: .longitude)
      try container.encode(lastAdvert, forKey: .lastAdvert)
      try container.encode(lastModified, forKey: .lastModified)
      try container.encode(outPath, forKey: .outPath)
      try container.encodeIfPresent(pathHashMode, forKey: .pathHashMode)
    }
  }
}

// MARK: - Section Selection

/// Controls which config sections to include in export/import.
public struct ConfigSections: Sendable, Equatable {
  public var nodeIdentity: Bool
  public var radioSettings: Bool
  public var positionSettings: Bool
  public var otherSettings: Bool
  public var channels: Bool
  public var contacts: Bool

  public init(
    nodeIdentity: Bool = false,
    radioSettings: Bool = false,
    positionSettings: Bool = false,
    otherSettings: Bool = false,
    channels: Bool = false,
    contacts: Bool = false
  ) {
    self.nodeIdentity = nodeIdentity
    self.radioSettings = radioSettings
    self.positionSettings = positionSettings
    self.otherSettings = otherSettings
    self.channels = channels
    self.contacts = contacts
  }

  /// True when all sections are selected.
  public var allSelected: Bool {
    nodeIdentity && radioSettings && positionSettings && otherSettings && channels && contacts
  }

  /// True when at least one section is selected.
  public var anySectionSelected: Bool {
    nodeIdentity || radioSettings || positionSettings || otherSettings || channels || contacts
  }

  public mutating func selectAll() {
    nodeIdentity = true
    radioSettings = true
    positionSettings = true
    otherSettings = true
    channels = true
    contacts = true
  }

  public mutating func deselectAll() {
    nodeIdentity = false
    radioSettings = false
    positionSettings = false
    otherSettings = false
    channels = false
    contacts = false
  }
}
