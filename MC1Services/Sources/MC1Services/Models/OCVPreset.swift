import Foundation

/// Categories for OCV presets
enum OCVPresetCategory {
  /// Generic battery chemistry (Li-Ion, LiFePO4, etc.)
  case batteryChemistry
  /// Specific commercial device
  case deviceSpecific
}

/// Battery OCV (Open Circuit Voltage) presets for accurate percentage calculation.
/// Each preset contains 11 millivolt values mapping to 100%, 90%, 80%... 0%.
///
/// Reference: https://github.com/meshtastic/firmware
public enum OCVPreset: String, CaseIterable, Codable, Sendable {
  case liIon
  case liFePO4
  case leadAcid
  case alkaline
  case niMH
  case lto
  case trackerT1000E
  case heltecPocket5000
  case heltecPocket10000
  case seeedWioTracker
  case seeedSolarNode
  case r1Neo
  case wisMeshTag
  case lilyGoTBeam1W
  case thinkNodeM6
  case custom

  /// Valid range for user-entered OCV voltage values (millivolts).
  /// Upper bound accommodates multi-cell series packs
  public static let validMillivoltRange: ClosedRange<Int> = 1000...99999

  /// The 11-point OCV array in millivolts (100% to 0% in 10% steps)
  public var ocvArray: [Int] {
    switch self {
    case .liIon:
      [4190, 4050, 3990, 3890, 3800, 3720, 3630, 3530, 3420, 3300, 3100]
    case .liFePO4:
      [3400, 3350, 3320, 3290, 3270, 3260, 3250, 3230, 3200, 3120, 3000]
    case .leadAcid:
      [2120, 2090, 2070, 2050, 2030, 2010, 1990, 1980, 1970, 1960, 1950]
    case .alkaline:
      [1580, 1400, 1350, 1300, 1280, 1250, 1230, 1190, 1150, 1100, 1000]
    case .niMH:
      [1400, 1300, 1280, 1270, 1260, 1250, 1240, 1230, 1210, 1150, 1000]
    case .lto:
      [2770, 2650, 2540, 2420, 2300, 2180, 2060, 1940, 1800, 1680, 1550]
    case .trackerT1000E:
      [4190, 4042, 3957, 3885, 3820, 3776, 3746, 3725, 3696, 3644, 3100]
    case .heltecPocket5000:
      [4300, 4240, 4120, 4000, 3888, 3800, 3740, 3698, 3655, 3580, 3400]
    case .heltecPocket10000:
      [4100, 4060, 3960, 3840, 3729, 3625, 3550, 3500, 3420, 3345, 3100]
    case .seeedWioTracker:
      [4200, 3876, 3826, 3763, 3713, 3660, 3573, 3485, 3422, 3359, 3300]
    case .seeedSolarNode:
      [4200, 3986, 3922, 3812, 3734, 3645, 3527, 3420, 3281, 3087, 2786]
    case .r1Neo:
      [4120, 4020, 4000, 3940, 3870, 3820, 3750, 3630, 3550, 3450, 3100]
    case .wisMeshTag:
      [4160, 4020, 3940, 3870, 3810, 3760, 3740, 3720, 3680, 3620, 2990]
    case .lilyGoTBeam1W:
      [7950, 7850, 7750, 7580, 7440, 7310, 7150, 7005, 6860, 6685, 6000]
    case .thinkNodeM6:
      [4080, 3990, 3935, 3880, 3825, 3770, 3715, 3660, 3605, 3550, 3450]
    case .custom:
      OCVPreset.liIon.ocvArray // Fallback, actual custom values stored separately
    }
  }

  /// Human-readable display name
  public var displayName: String {
    switch self {
    case .liIon: "Li-Ion (Default)"
    case .liFePO4: "LiFePO4"
    case .leadAcid: "Lead Acid"
    case .alkaline: "Alkaline"
    case .niMH: "NiMH"
    case .lto: "LTO"
    case .trackerT1000E: "Tracker T1000-E"
    case .heltecPocket5000: "Heltec Pocket 5000"
    case .heltecPocket10000: "Heltec Pocket 10000"
    case .seeedWioTracker: "Seeed WIO Tracker"
    case .seeedSolarNode: "Seeed Solar Node"
    case .r1Neo: "R1 Neo"
    case .wisMeshTag: "WisMesh Tag"
    case .lilyGoTBeam1W: "LilyGo T-Beam 1W"
    case .thinkNodeM6: "ThinkNode M6"
    case .custom: "Custom"
    }
  }

  /// The category of this preset
  var category: OCVPresetCategory {
    switch self {
    case .liIon, .liFePO4, .leadAcid, .alkaline, .niMH, .lto:
      .batteryChemistry
    case .trackerT1000E, .heltecPocket5000, .heltecPocket10000,
         .seeedWioTracker, .seeedSolarNode, .r1Neo, .wisMeshTag,
         .lilyGoTBeam1W, .thinkNodeM6, .custom:
      .deviceSpecific
    }
  }

  /// All presets except custom (for picker display)
  public static var selectablePresets: [OCVPreset] {
    allCases.filter { $0 != .custom }
  }

  /// Battery chemistry presets only (excludes device-specific and custom)
  public static var batteryChemistryPresets: [OCVPreset] {
    allCases.filter { $0.category == .batteryChemistry }
  }

  /// Presets available for remote node configuration.
  /// Includes battery chemistry types plus select device-specific presets.
  public static var nodePresets: [OCVPreset] {
    var presets = batteryChemistryPresets
    presets.append(.seeedSolarNode)
    return presets
  }

  private static let logger = PersistentLogger(subsystem: "com.mc1", category: "OCVPreset")

  /// Returns the OCV preset for a known manufacturer name, or nil if no match.
  ///
  /// Manufacturer names must exactly match the strings returned by `getManufacturerName()`
  /// in the MeshCore firmware's device variant headers (`{device_variant}.h`).
  /// See: https://github.com/meshcore-dev/MeshCore
  public static func preset(forManufacturer name: String) -> OCVPreset? {
    let preset: OCVPreset? = switch name {
    case "Seeed Tracker T1000-e", "Seeed Tracker T1000-E": .trackerT1000E
    case "Seeed Wio Tracker L1": .seeedWioTracker
    case "Seeed SenseCap Solar": .seeedSolarNode
    case "RAK WisMesh Tag": .wisMeshTag
    case "LilyGo T-Beam 1W": .lilyGoTBeam1W
    case "Elecrow ThinkNode M6": .thinkNodeM6
    default: nil
    }
    if preset == nil, !name.isEmpty {
      logger.debug("No OCV preset for manufacturer: \(name)")
    }
    return preset
  }
}
