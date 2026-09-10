import Foundation

/// Geographic regions for radio preset filtering
public enum RadioRegion: String, CaseIterable, Sendable {
  case northAmerica = "North America"
  case southAmerica = "South America"
  case europe = "Europe"
  case oceania = "Oceania"
  case asia = "Asia"

  /// Regions that should be shown for a given locale
  public static func regionsForLocale(_ locale: Locale = .current) -> [RadioRegion] {
    guard let regionCode = locale.region?.identifier else {
      return RadioRegion.allCases
    }

    switch regionCode {
    case "US", "CA", "CR":
      return [.northAmerica, .europe, .oceania, .asia]
    case "AU", "NZ":
      return [.oceania, .northAmerica, .europe, .asia]
    case "GB", "DE", "FR", "IT", "ES", "PT", "CH", "CZ", "IE", "NL", "BE", "AT", "HU", "SK":
      return [.europe, .northAmerica, .oceania, .asia]
    case "VN", "TH", "MY", "SG", "PH", "ID":
      return [.asia, .oceania, .europe, .northAmerica]
    case "CL", "BR":
      return [.southAmerica, .northAmerica, .europe, .oceania, .asia]
    default:
      return RadioRegion.allCases
    }
  }

  /// Short code for display in compact UI elements
  public var shortCode: String {
    switch self {
    case .northAmerica: "NA"
    case .southAmerica: "SA"
    case .europe: "EU"
    case .oceania: "AU"
    case .asia: "AS"
    }
  }
}

/// Geographic availability tier for a `RadioPreset`. Used by the recommendation
/// algorithm to choose the most-specific community-curated preset that matches
/// the user's `RegionSelection`.
enum PresetAvailability: Equatable {
  case continent(RadioRegion)
  case countries(Set<String>) // ISO-3166 α-2
  case subRegions(country: String, areas: Set<String>) // ISO 3166-2
  case counties(country: String, state: String, keys: Set<String>) // normalized US county names
}

/// Radio configuration preset for common regional settings
public struct RadioPreset: Identifiable, Sendable, Equatable {
  public let id: String
  public let name: String
  public let region: RadioRegion
  public let frequencyMHz: Double
  public let bandwidthKHz: Double
  public let spreadingFactor: UInt8
  public let codingRate: UInt8

  /// Section header for repeat mode presets (e.g., "EU/Asia", "US/AU/NZ")
  public let repeatSectionHeader: String?
  /// Bytes per hop hash (`1`, `2`, or `3`). `nil` leaves the radio's current hash unchanged.
  public let pathHashSize: Int?
  let availability: PresetAvailability
  /// Higher value = preferred within a geographic tier. Standard presets use 100; community-recommended favorites use 110.
  public let recommendationPriority: Int

  /// Frequency in kHz for protocol encoding
  public var frequencyKHz: UInt32 {
    UInt32((frequencyMHz * 1000).rounded())
  }

  /// Bandwidth in Hz for protocol encoding
  public var bandwidthHz: UInt32 {
    UInt32((bandwidthKHz * 1000).rounded())
  }

  /// Firmware `path_hash_mode`, or `nil` when `pathHashSize` is `nil`.
  public var pathHashMode: UInt8? {
    guard let pathHashSize else { return nil }
    return UInt8(pathHashSize - 1)
  }

  init(
    id: String,
    name: String,
    region: RadioRegion,
    frequencyMHz: Double,
    bandwidthKHz: Double,
    spreadingFactor: UInt8,
    codingRate: UInt8,
    repeatSectionHeader: String? = nil,
    pathHashSize: Int? = nil,
    availability: PresetAvailability,
    recommendationPriority: Int = 100
  ) {
    self.id = id
    self.name = name
    self.region = region
    self.frequencyMHz = frequencyMHz
    self.bandwidthKHz = bandwidthKHz
    self.spreadingFactor = spreadingFactor
    self.codingRate = codingRate
    self.repeatSectionHeader = repeatSectionHeader
    self.pathHashSize = pathHashSize
    self.availability = availability
    self.recommendationPriority = recommendationPriority
  }
}

/// Static collection of all available radio presets
public enum RadioPresets {
  public static let all: [RadioPreset] = [
    // Oceania
    RadioPreset(id: "au-915", name: "Australia", region: .oceania,
                frequencyMHz: 915.800, bandwidthKHz: 250, spreadingFactor: 10, codingRate: 5,
                availability: .countries(["AU"])),
    RadioPreset(id: "au-narrow", name: "Australia (Narrow)", region: .oceania,
                frequencyMHz: 916.575, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 8,
                availability: .countries(["AU"])),
    RadioPreset(id: "au-mid", name: "Australia (Mid)", region: .oceania,
                frequencyMHz: 915.075, bandwidthKHz: 125, spreadingFactor: 9, codingRate: 5,
                availability: .countries(["AU"])),
    RadioPreset(id: "au-sa-wa", name: "Australia: SA, WA", region: .oceania,
                frequencyMHz: 923.125, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 8,
                availability: .subRegions(country: "AU", areas: ["AU-SA", "AU-WA"])),
    RadioPreset(id: "au-qld", name: "Australia: QLD", region: .oceania,
                frequencyMHz: 923.125, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 5,
                availability: .subRegions(country: "AU", areas: ["AU-QLD"])),
    RadioPreset(id: "nz-lr", name: "New Zealand (Gisborne)", region: .oceania,
                frequencyMHz: 917.375, bandwidthKHz: 250, spreadingFactor: 11, codingRate: 5,
                pathHashSize: 1,
                availability: .countries(["NZ"])),
    RadioPreset(id: "nz-narrow", name: "New Zealand (Narrow)", region: .oceania,
                frequencyMHz: 917.375, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
                pathHashSize: 2,
                availability: .countries(["NZ"])),

    // Europe
    RadioPreset(id: "eu-narrow", name: "EU/UK (Narrow)", region: .europe,
                frequencyMHz: 869.618, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 8,
                availability: .continent(.europe), recommendationPriority: 110),
    RadioPreset(id: "eu-lr", name: "EU/UK (Deprecated)", region: .europe,
                frequencyMHz: 869.525, bandwidthKHz: 250, spreadingFactor: 11, codingRate: 5,
                availability: .continent(.europe)),
    RadioPreset(id: "cz-narrow", name: "Czech Republic (Narrow)", region: .europe,
                frequencyMHz: 869.432, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
                availability: .countries(["CZ"])),
    RadioPreset(id: "eu-433-lr", name: "EU 433MHz (Long Range)", region: .europe,
                frequencyMHz: 433.650, bandwidthKHz: 250, spreadingFactor: 11, codingRate: 5,
                availability: .continent(.europe)),
    RadioPreset(id: "eu-433-narrow", name: "EU 433MHz (Narrow)", region: .europe,
                frequencyMHz: 433.650, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 8,
                availability: .continent(.europe)),
    RadioPreset(id: "pt-433", name: "Portugal 433", region: .europe,
                frequencyMHz: 433.375, bandwidthKHz: 62.5, spreadingFactor: 9, codingRate: 6,
                availability: .countries(["PT"])),
    RadioPreset(id: "pt-868", name: "Portugal 868", region: .europe,
                frequencyMHz: 869.618, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 6,
                availability: .countries(["PT"]), recommendationPriority: 110),
    RadioPreset(id: "ch", name: "Switzerland", region: .europe,
                frequencyMHz: 869.618, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 8,
                availability: .countries(["CH"])),
    RadioPreset(id: "hu", name: "Hungary", region: .europe,
                frequencyMHz: 869.618, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
                pathHashSize: 2,
                availability: .countries(["HU"])),
    RadioPreset(id: "nl", name: "Netherlands", region: .europe,
                frequencyMHz: 869.618, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
                availability: .countries(["NL"]), recommendationPriority: 110),
    RadioPreset(id: "nl-li", name: "Netherlands (Limburg)", region: .europe,
                frequencyMHz: 869.618, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 8,
                pathHashSize: 2,
                availability: .countries(["NL"])),
    RadioPreset(id: "sk", name: "Slovakia", region: .europe,
                frequencyMHz: 869.618, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
                pathHashSize: 2,
                availability: .countries(["SK"])),

    // North America
    RadioPreset(id: "us-ca", name: "USA", region: .northAmerica,
                frequencyMHz: 910.525, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
                availability: .countries(["US"]), recommendationPriority: 110),
    RadioPreset(id: "ca", name: "Canada", region: .northAmerica,
                frequencyMHz: 910.525, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
                pathHashSize: 3,
                availability: .countries(["CA"]), recommendationPriority: 110),
    RadioPreset(id: "cr", name: "Costa Rica", region: .northAmerica,
                frequencyMHz: 910.525, bandwidthKHz: 125, spreadingFactor: 11, codingRate: 5,
                availability: .countries(["CR"])),
    RadioPreset(id: "wcmesh", name: "WCMesh (SoCal)", region: .northAmerica,
                frequencyMHz: 927.875, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
                pathHashSize: 3,
                availability: .counties(country: "US", state: "US-CA", keys: [
                  "los angeles", "orange", "san diego", "riverside", "san bernardino",
                  "ventura", "imperial", "kern", "santa barbara", "san luis obispo",
                ])),
    RadioPreset(id: "phillymesh", name: "PhillyMesh", region: .northAmerica,
                frequencyMHz: 902.250, bandwidthKHz: 500, spreadingFactor: 11, codingRate: 5,
                pathHashSize: 2,
                availability: .subRegions(country: "US", areas: ["US-PA", "US-NJ", "US-DE", "US-MD"])),

    // South America
    // Chile: community-standard settings from the MeshChile network (https://meshchile.cl).
    RadioPreset(id: "cl", name: "Chile", region: .southAmerica,
                frequencyMHz: 927.875, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 5,
                availability: .countries(["CL"])),
    // Brazil
    RadioPreset(id: "br", name: "Brazil", region: .southAmerica,
                frequencyMHz: 923.125, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 8,
                availability: .countries(["BR"])),

    // Asia
    RadioPreset(id: "vn-narrow", name: "Vietnam (Narrow)", region: .asia,
                frequencyMHz: 920.250, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 5,
                availability: .countries(["VN"]), recommendationPriority: 110),
    RadioPreset(id: "vn", name: "Vietnam (Deprecated)", region: .asia,
                frequencyMHz: 920.250, bandwidthKHz: 250, spreadingFactor: 11, codingRate: 5,
                availability: .countries(["VN"])),
  ]

  /// Repeat mode frequency presets with regional grouping. Frequencies must match the
  /// firmware's allowed repeat set exactly. Enabling Repeat Mode applies only the frequency;
  /// the per-entry bandwidth/SF/CR are inert (kept because `RadioPreset`'s fields are non-optional).
  public static let repeatPresets: [RadioPreset] = [
    RadioPreset(id: "repeat-433", name: "433 MHz", region: .europe,
                frequencyMHz: 433.000, bandwidthKHz: 62.5, spreadingFactor: 9, codingRate: 8,
                repeatSectionHeader: "EU/Asia",
                availability: .continent(.europe)),
    RadioPreset(id: "repeat-869", name: "869 MHz", region: .europe,
                frequencyMHz: 869.495, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 8,
                repeatSectionHeader: "EU",
                availability: .continent(.europe)),
    RadioPreset(id: "repeat-918", name: "918 MHz", region: .northAmerica,
                frequencyMHz: 918.000, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 8,
                repeatSectionHeader: "US/AU/NZ",
                availability: .continent(.northAmerica)),
  ]

  /// Get presets filtered and sorted by user's locale
  public static func presetsForLocale(_ locale: Locale = .current) -> [RadioPreset] {
    let preferredRegions = RadioRegion.regionsForLocale(locale)

    return all.sorted { a, b in
      let aIndex = preferredRegions.firstIndex(of: a.region) ?? preferredRegions.count
      let bIndex = preferredRegions.firstIndex(of: b.region) ?? preferredRegions.count
      if aIndex != bIndex {
        return aIndex < bIndex
      }
      return a.name < b.name
    }
  }

  /// Every catalog row whose RF tuple matches, in catalog order. More than one name can share a tuple.
  public static func matchingPresets(
    frequencyKHz: UInt32,
    bandwidthKHz: UInt32,
    spreadingFactor: UInt8,
    codingRate: UInt8
  ) -> [RadioPreset] {
    let freqMHz = Double(frequencyKHz) / 1000.0
    let bwKHz = Double(bandwidthKHz) / 1000.0
    return all.filter { preset in
      abs(preset.frequencyMHz - freqMHz) < 0.1 &&
        abs(preset.bandwidthKHz - bwKHz) < 1.0 &&
        preset.spreadingFactor == spreadingFactor &&
        preset.codingRate == codingRate
    }
  }

  /// Last-applied id if still RF-equal, then recommended-if-in-set, then a unique match; else Custom.
  public static func resolvedPreset(
    frequencyKHz: UInt32,
    bandwidthKHz: UInt32,
    spreadingFactor: UInt8,
    codingRate: UInt8,
    preferredID: String?,
    region: RegionSelection?
  ) -> RadioPreset? {
    let matches = matchingPresets(
      frequencyKHz: frequencyKHz,
      bandwidthKHz: bandwidthKHz,
      spreadingFactor: spreadingFactor,
      codingRate: codingRate
    )
    if let preferredID, let hit = matches.first(where: { $0.id == preferredID }) {
      return hit
    }
    if let region,
       let recommended = recommended(for: region),
       let hit = matches.first(where: { $0.id == recommended.id }) {
      return hit
    }
    return matches.count == 1 ? matches[0] : nil
  }

  /// Unique RF match, or nil when the tuple is unlabeled or collides.
  public static func matchingPreset(
    frequencyKHz: UInt32,
    bandwidthKHz: UInt32,
    spreadingFactor: UInt8,
    codingRate: UInt8
  ) -> RadioPreset? {
    resolvedPreset(
      frequencyKHz: frequencyKHz,
      bandwidthKHz: bandwidthKHz,
      spreadingFactor: spreadingFactor,
      codingRate: codingRate,
      preferredID: nil,
      region: nil
    )
  }

  /// Find the repeat preset for the device's current frequency. Repeat Mode only sets frequency,
  /// so bandwidth/SF/CR are not part of the match.
  public static func matchingRepeatPreset(frequencyKHz: UInt32) -> RadioPreset? {
    repeatPresets.first { $0.frequencyKHz == frequencyKHz }
  }

  /// The repeat preset nearest to a frequency by absolute kHz distance. Enabling Repeat Mode snaps an
  /// off-band frequency to this preset, since the firmware accepts only exact repeat frequencies.
  public static func nearestRepeatPreset(toFrequencyKHz frequencyKHz: UInt32) -> RadioPreset? {
    repeatPresets.min {
      abs(Int($0.frequencyKHz) - Int(frequencyKHz)) < abs(Int($1.frequencyKHz) - Int(frequencyKHz))
    }
  }

  /// Stable recommendation order, computed once. `recommendationPriority` is a
  /// compile-time constant on each preset so the sort output never changes.
  private static let recommendationOrder: [RadioPreset] = all.sorted {
    $0.recommendationPriority != $1.recommendationPriority
      ? $0.recommendationPriority > $1.recommendationPriority
      : $0.id < $1.id
  }

  /// Returns the most-specific community-curated preset for `region`.
  /// Tier 0 (county) → Tier 1 (sub-region) → Tier 2 (country) → Tier 3 (continent).
  /// Returns nil for regions not covered by any tier (e.g. Bermuda).
  public static func recommended(for region: RegionSelection) -> RadioPreset? {
    let stable = recommendationOrder

    // Tier 0: counties
    if let adminCode = region.administrativeAreaCode,
       let countyKey = region.countyKey,
       let preset = stable.first(where: {
         if case let .counties(c, s, keys) = $0.availability {
           return c == region.countryCode && s == adminCode && keys.contains(countyKey)
         }
         return false
       }) { return preset }

    // Tier 1: sub-regions
    if let adminCode = region.administrativeAreaCode,
       let preset = stable.first(where: {
         if case let .subRegions(c, areas) = $0.availability {
           return c == region.countryCode && areas.contains(adminCode)
         }
         return false
       }) { return preset }

    // Tier 2: countries
    if let preset = stable.first(where: {
      if case let .countries(codes) = $0.availability {
        return codes.contains(region.countryCode)
      }
      return false
    }) { return preset }

    // Tier 3: continent
    if let continent = RegionalAreas.continents[region.countryCode],
       let preset = stable.first(where: {
         if case let .continent(r) = $0.availability { return r == continent }
         return false
       }) { return preset }

    return nil
  }

  /// Returns the alternatives list for the region's country (or continent if no
  /// country-level matches exist). Membership includes every `.counties` and
  /// `.subRegions` preset for that country; pickers then hide out-of-area
  /// presets via `isSelectable`.
  public static func presets(for region: RegionSelection) -> [RadioPreset] {
    let countryAndBelow = all.filter { preset in
      switch preset.availability {
      case let .counties(c, _, _): c == region.countryCode
      case let .subRegions(c, _): c == region.countryCode
      case let .countries(codes): codes.contains(region.countryCode)
      case .continent: false
      }
    }
    if !countryAndBelow.isEmpty { return countryAndBelow }
    guard let continent = RegionalAreas.continents[region.countryCode] else { return [] }
    return all.filter { preset in
      if case let .continent(r) = preset.availability { return r == continent }
      return false
    }
  }

  /// Whether `preset` should appear in a manual picker for `region`.
  /// County presets appear only when `region` resolves to one of their counties.
  /// Sub-region presets appear only when `region` matches the preset country and one of its areas.
  /// A nil region hides both. Country and continent presets are always selectable.
  public static func isSelectable(_ preset: RadioPreset, in region: RegionSelection?) -> Bool {
    switch preset.availability {
    case let .counties(country, state, keys):
      guard let region,
            region.countryCode == country,
            region.administrativeAreaCode == state,
            let countyKey = region.countyKey else {
        return false
      }
      return keys.contains(countyKey)
    case let .subRegions(country, areas):
      guard let region,
            region.countryCode == country,
            let admin = region.administrativeAreaCode else {
        return false
      }
      return areas.contains(admin)
    case .countries, .continent:
      return true
    }
  }

  /// Presets shown in Settings → Radio for `region`, plus the radio's current preset when
  /// that id is not already in the regional list (traveler exception).
  public static func visiblePresets(
    for region: RegionSelection?,
    activeID: String?
  ) -> [RadioPreset] {
    let start: [RadioPreset]
    if let region {
      let regional = presets(for: region)
      start = regional.isEmpty ? presetsForLocale() : regional
    } else {
      start = presetsForLocale()
    }

    var result = start.filter { isSelectable($0, in: region) || $0.id == activeID }
    if let activeID,
       !result.contains(where: { $0.id == activeID }),
       let active = all.first(where: { $0.id == activeID }) {
      result.append(active)
    }
    return result
  }
}
