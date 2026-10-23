import Foundation

// MARK: - Duration

/// Unit a user picks when entering a relative time bound.
enum MapDurationUnit: String, Sendable, Codable, CaseIterable, Hashable {
  case minutes
  case hours
  case days

  var seconds: TimeInterval {
    switch self {
    case .minutes: 60
    case .hours: 3600
    case .days: 86400
    }
  }
}

/// A relative time span that keeps the unit the user entered so it re-displays verbatim.
struct MapDuration: Sendable, Codable, Hashable {
  /// Keeps `seconds` far from overflow while allowing years of history in any unit.
  static let valueRange = 1...9999

  private(set) var value: Int
  private(set) var unit: MapDurationUnit

  init(_ value: Int, _ unit: MapDurationUnit) {
    self.value = value.clamped(to: Self.valueRange)
    self.unit = unit
  }

  init(from decoder: Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    let value = try container.decode(Int.self, forKey: .value)
    let unit = try container.decode(MapDurationUnit.self, forKey: .unit)
    self.init(value, unit)
  }

  var seconds: TimeInterval {
    TimeInterval(value) * unit.seconds
  }
}

// MARK: - Last Heard Range

/// Rolling "last heard between `minAge` and `maxAge` ago" bound. Both ends are inclusive and optional.
struct MapLastHeardRange: Sendable, Codable, Hashable {
  /// Heard at least this long ago (hides nodes heard more recently).
  private(set) var minAge: MapDuration?
  /// Heard at most this long ago (hides nodes quiet for longer).
  private(set) var maxAge: MapDuration?

  static let any = MapLastHeardRange()

  static let presets: [MapLastHeardRange] = [
    .any,
    .within(MapDuration(15, .minutes)),
    .within(MapDuration(1, .hours)),
    .within(MapDuration(2, .hours)),
    .within(MapDuration(6, .hours)),
    .within(MapDuration(24, .hours)),
    .within(MapDuration(3, .days)),
    .within(MapDuration(7, .days))
  ]

  init(minAge: MapDuration? = nil, maxAge: MapDuration? = nil) {
    self.minAge = minAge
    self.maxAge = maxAge
    repairCrossedBounds(keepingMin: true)
  }

  init(from decoder: Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    self.init(
      minAge: try? container.decodeIfPresent(MapDuration.self, forKey: .minAge),
      maxAge: try? container.decodeIfPresent(MapDuration.self, forKey: .maxAge)
    )
  }

  static func within(_ duration: MapDuration) -> MapLastHeardRange {
    MapLastHeardRange(maxAge: duration)
  }

  var isActive: Bool {
    minAge != nil || maxAge != nil
  }

  /// Nil `heardAt` (never heard) fails any active range. Future stamps (clock skew) count as just heard.
  func contains(heardAt: Date?, now: Date) -> Bool {
    guard isActive else { return true }
    guard let heardAt else { return false }
    let age = max(0, now.timeIntervalSince(heardAt))
    if let minAge, age < minAge.seconds { return false }
    if let maxAge, age > maxAge.seconds { return false }
    return true
  }

  /// Sets the minimum; a maximum below it moves up to match.
  func withMinAge(_ duration: MapDuration?) -> MapLastHeardRange {
    var copy = self
    copy.minAge = duration
    copy.repairCrossedBounds(keepingMin: true)
    return copy
  }

  /// Sets the maximum; a minimum above it moves down to match.
  func withMaxAge(_ duration: MapDuration?) -> MapLastHeardRange {
    var copy = self
    copy.maxAge = duration
    copy.repairCrossedBounds(keepingMin: false)
    return copy
  }

  private mutating func repairCrossedBounds(keepingMin: Bool) {
    guard let minAge, let maxAge, minAge.seconds > maxAge.seconds else { return }
    if keepingMin {
      self.maxAge = minAge
    } else {
      self.minAge = maxAge
    }
  }
}

// MARK: - Hop Range

/// Inclusive hop-count bound. `max == nil` means no upper limit.
struct MapHopRange: Sendable, Codable, Hashable {
  /// Path length is a 6-bit field on the wire (`decodePathLen`), so 63 is the protocol ceiling.
  static let hopDomain = 0...63

  private(set) var min: Int
  private(set) var max: Int?

  static let any = MapHopRange()
  static let direct = MapHopRange(min: 0, max: 0)

  static let presets: [MapHopRange] = [
    .any,
    .direct,
    MapHopRange(max: 1),
    MapHopRange(max: 2),
    MapHopRange(max: 3)
  ]

  init(min: Int = 0, max: Int? = nil) {
    self.min = min.clamped(to: Self.hopDomain)
    self.max = max.map { $0.clamped(to: Self.hopDomain) }
    repairCrossedBounds(keepingMin: true)
    collapseFullDomain()
  }

  init(from decoder: Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    self.init(
      min: (try? container.decodeIfPresent(Int.self, forKey: .min)) ?? 0,
      max: try? container.decodeIfPresent(Int.self, forKey: .max)
    )
  }

  var isActive: Bool {
    min > 0 || max != nil
  }

  /// Nil `hops` (unknown) fails any active range.
  func contains(_ hops: Int?) -> Bool {
    guard isActive else { return true }
    guard let hops else { return false }
    if hops < min { return false }
    if let max, hops > max { return false }
    return true
  }

  /// Sets the minimum; a maximum below it moves up to match.
  func withMin(_ value: Int) -> MapHopRange {
    var copy = self
    copy.min = value.clamped(to: Self.hopDomain)
    copy.repairCrossedBounds(keepingMin: true)
    copy.collapseFullDomain()
    return copy
  }

  /// Sets the maximum (nil = no limit); a minimum above it moves down to match.
  func withMax(_ value: Int?) -> MapHopRange {
    var copy = self
    copy.max = value.map { $0.clamped(to: Self.hopDomain) }
    copy.repairCrossedBounds(keepingMin: false)
    copy.collapseFullDomain()
    return copy
  }

  private mutating func repairCrossedBounds(keepingMin: Bool) {
    guard let max, min > max else { return }
    if keepingMin {
      self.max = min
    } else {
      self.min = max
    }
  }

  /// A maximum at the protocol ceiling is the same as no limit.
  private mutating func collapseFullDomain() {
    if max == Self.hopDomain.upperBound {
      max = nil
    }
  }
}

// MARK: - Helpers

private extension Comparable {
  func clamped(to range: ClosedRange<Self>) -> Self {
    Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
  }
}
