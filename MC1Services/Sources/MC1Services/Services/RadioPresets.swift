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
    case "US", "CA":
      return [.northAmerica, .europe, .oceania, .asia]
    case "AU", "NZ":
      return [.oceania, .northAmerica, .europe, .asia]
    case "GB", "DE", "FR", "IT", "ES", "PT", "CH", "CZ", "IE", "NL", "BE", "AT":
      return [.europe, .northAmerica, .oceania, .asia]
    case "VN", "TH", "MY", "SG", "PH", "ID":
      return [.asia, .oceania, .europe, .northAmerica]
    case "CL":
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

  init(
    id: String,
    name: String,
    region: RadioRegion,
    frequencyMHz: Double,
    bandwidthKHz: Double,
    spreadingFactor: UInt8,
    codingRate: UInt8,
    repeatSectionHeader: String? = nil,
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
    RadioPreset(id: "nz-lr", name: "New Zealand", region: .oceania,
                frequencyMHz: 917.375, bandwidthKHz: 250, spreadingFactor: 11, codingRate: 5,
                availability: .countries(["NZ"])),
    RadioPreset(id: "nz-narrow", name: "New Zealand (Narrow)", region: .oceania,
                frequencyMHz: 917.375, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
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
    RadioPreset(id: "nl", name: "Netherlands", region: .europe,
                frequencyMHz: 869.618, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
                availability: .countries(["NL"]), recommendationPriority: 110),

    // North America
    RadioPreset(id: "us-ca", name: "USA/Canada", region: .northAmerica,
                frequencyMHz: 910.525, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
                availability: .countries(["US", "CA"]), recommendationPriority: 110),
    RadioPreset(id: "wcmesh", name: "WCMesh (SoCal)", region: .northAmerica,
                frequencyMHz: 927.875, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
                availability: .counties(country: "US", state: "US-CA", keys: [
                  "los angeles", "orange", "san diego", "riverside", "san bernardino",
                  "ventura", "imperial", "kern", "santa barbara", "san luis obispo",
                ])),

    // South America
    // Chile: community-standard settings from the MeshChile network (https://meshchile.cl).
    RadioPreset(id: "cl", name: "Chile", region: .southAmerica,
                frequencyMHz: 927.875, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 5,
                availability: .countries(["CL"])),

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

  /// Find preset matching current device settings (approximate match)
  public static func matchingPreset(
    frequencyKHz: UInt32,
    bandwidthKHz: UInt32,
    spreadingFactor: UInt8,
    codingRate: UInt8
  ) -> RadioPreset? {
    let freqMHz = Double(frequencyKHz) / 1000.0
    let bwKHz = Double(bandwidthKHz) / 1000.0

    return all.first { preset in
      abs(preset.frequencyMHz - freqMHz) < 0.1 &&
        abs(preset.bandwidthKHz - bwKHz) < 1.0 &&
        preset.spreadingFactor == spreadingFactor &&
        preset.codingRate == codingRate
    }
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
  /// country-level matches exist). The list always includes `.counties` and
  /// `.subRegions` presets for the country regardless of the user's specific
  /// county/state, so a Sacramento user can still pick `wcmesh` manually.
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

  /// Whether `preset` should appear in a manual picker for `region`. Only county-scoped presets are
  /// gated: they appear only when `region` resolves to one of their counties (a nil region hides them).
  /// Every other tier is always selectable.
  public static func isSelectable(_ preset: RadioPreset, in region: RegionSelection?) -> Bool {
    guard case let .counties(country, state, keys) = preset.availability else {
      return true
    }
    guard let region,
          region.countryCode == country,
          region.administrativeAreaCode == state,
          let countyKey = region.countyKey else {
      return false
    }
    return keys.contains(countyKey)
  }
}
