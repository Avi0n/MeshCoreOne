import Foundation

/// User's geographic region, used to recommend community-curated radio presets.
/// Distinct from the firmware-mesh-region concept in `RegionDiscoveryService`.
public struct RegionSelection: Codable, Sendable, Equatable {
  public let countryCode: String // ISO-3166 α-2 (e.g. "US")
  public let administrativeAreaCode: String? // ISO 3166-2 (e.g. "US-CA")
  public let countyKey: String? // normalized US county key (e.g. "los angeles")
  public let source: Source

  public enum Source: String, Codable, Sendable {
    // Raw values pinned per backup contract: a future case rename must not silently change the on-disk format.
    case location
    case manual
  }

  public init(
    countryCode: String,
    administrativeAreaCode: String? = nil,
    countyKey: String? = nil,
    source: Source
  ) {
    self.countryCode = countryCode
    self.administrativeAreaCode = administrativeAreaCode
    self.countyKey = countyKey
    self.source = source
  }

  /// `nil` means do not write: the tapped country is already selected.
  public static func afterChoosingCountry(
    _ newCountry: String,
    current: RegionSelection?
  ) -> RegionSelection? {
    if let current, current.countryCode == newCountry {
      return nil
    }
    return RegionSelection(countryCode: newCountry, source: .manual)
  }

  /// `nil` means do not write: the tapped subdivision is already selected.
  public static func afterChoosingSubdivision(
    _ newSubdivision: String,
    current: RegionSelection?
  ) -> RegionSelection? {
    guard let current else { return nil }
    if current.administrativeAreaCode == newSubdivision {
      return nil
    }
    return RegionSelection(
      countryCode: current.countryCode,
      administrativeAreaCode: newSubdivision,
      source: .manual
    )
  }

  private enum CodingKeys: String, CodingKey {
    case countryCode, administrativeAreaCode, countyKey, source
  }

  public init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    countryCode = try c.decode(String.self, forKey: .countryCode)
    administrativeAreaCode = try c.decodeIfPresent(String.self, forKey: .administrativeAreaCode)
    countyKey = try c.decodeIfPresent(String.self, forKey: .countyKey)
    source = try c.decode(Source.self, forKey: .source)
  }
}
