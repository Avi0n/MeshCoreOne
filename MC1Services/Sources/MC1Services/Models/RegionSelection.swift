import Foundation

/// User's geographic region, used to recommend community-curated radio presets.
/// Distinct from the firmware-mesh-region concept in `RegionDiscoveryService`.
public struct RegionSelection: Codable, Sendable, Equatable {
  public let countryCode: String // ISO-3166 α-2 (e.g. "US")
  public let administrativeAreaCode: String? // ISO 3166-2 (e.g. "US-CA")
  public let countyKey: String? // normalized US county key (e.g. "los angeles")
  public let source: Source

  public enum Source: String, Codable, Sendable {
    // swiftlint:disable redundant_string_enum_value
    // Raw values pinned per backup contract: a future case rename must not silently change the on-disk format.
    case location
    case manual
    // swiftlint:enable redundant_string_enum_value
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
