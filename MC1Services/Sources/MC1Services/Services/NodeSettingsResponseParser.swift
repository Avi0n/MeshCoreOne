import Foundation

/// Pure parsing of repeater and room CLI responses for the node settings
/// screens: late-response field matching, clock parsing, owner-info wire
/// mapping, and success/error classification. Builds on `CLIResponse` and
/// holds no state, so every function is directly unit-testable.
public enum NodeSettingsResponseParser {
  // MARK: - Settings Fields

  /// A node settings field recoverable from an uncorrelated CLI response.
  public enum SettingsField: Sendable {
    case radio
    case txPower
    case firmwareVersion
    case deviceTime
    case latitude
    case longitude
    case name
    case ownerInfo

    /// The CLI query whose response shape this field matches.
    var query: String {
      switch self {
      case .radio: "get radio"
      case .txPower: "get tx"
      case .firmwareVersion: "ver"
      case .deviceTime: "clock"
      case .latitude: "get lat"
      case .longitude: "get lon"
      case .name: "get name"
      case .ownerInfo: "get owner.info"
      }
    }
  }

  /// A parsed value for one of the shared settings fields.
  public enum SettingsValue: Equatable, Sendable {
    case radio(frequency: Double, bandwidth: Double, spreadingFactor: Int, codingRate: Int)
    case txPower(Int)
    case firmwareVersion(String)
    case deviceTime(String)
    case latitude(Double)
    case longitude(Double)
    case name(String)
    case ownerInfo(String)
  }

  /// Matches an uncorrelated (late) CLI response against the given fields in
  /// order and returns the first field value it parses as.
  ///
  /// Order matters: `name` matches any free-form text, so callers must list
  /// numeric fields (`latitude`, `longitude`) before it to avoid capturing a
  /// stray number as the node name.
  public static func firstSettingsValue(
    in response: String,
    checking fields: [SettingsField]
  ) -> SettingsValue? {
    for field in fields {
      let parsed = CLIResponse.parse(response, forQuery: field.query)
      switch (field, parsed) {
      case let (.radio, .radio(frequency, bandwidth, spreadingFactor, codingRate)):
        return .radio(
          frequency: frequency,
          bandwidth: bandwidth,
          spreadingFactor: spreadingFactor,
          codingRate: codingRate
        )
      case let (.txPower, .txPower(power)):
        return .txPower(power)
      case let (.firmwareVersion, .version(version)):
        return .firmwareVersion(version)
      case let (.deviceTime, .deviceTime(time)):
        return .deviceTime(time)
      case let (.latitude, .latitude(latitude)):
        return .latitude(latitude)
      case let (.longitude, .longitude(longitude)):
        return .longitude(longitude)
      case let (.name, .name(name)):
        return .name(name)
      case let (.ownerInfo, .ownerInfo(info)):
        return .ownerInfo(info)
      default:
        continue
      }
    }
    return nil
  }

  // MARK: - Behavior Fields

  /// A repeater/room behavior field recovered from a late CLI response.
  public enum BehaviorValue: Equatable, Sendable {
    case advertInterval(Int)
    case floodAdvertInterval(Int)
    case floodMax(Int)
  }

  /// Try to parse a late response as one of the shared behavior fields.
  /// Returns `nil` if the response didn't match any field that's still missing.
  public static func behaviorLateResponse(
    _ response: String,
    hasAdvertInterval: Bool,
    hasFloodInterval: Bool,
    hasFloodMaxHops: Bool
  ) -> BehaviorValue? {
    if !hasAdvertInterval {
      if case let .advertInterval(interval) = CLIResponse.parse(response, forQuery: "get advert.interval") {
        return .advertInterval(interval)
      }
    }
    if !hasFloodInterval {
      if case let .floodAdvertInterval(interval) = CLIResponse.parse(
        response, forQuery: "get flood.advert.interval"
      ) {
        return .floodAdvertInterval(interval)
      }
    }
    if !hasFloodMaxHops {
      if case let .floodMax(hops) = CLIResponse.parse(response, forQuery: "get flood.max") {
        return .floodMax(hops)
      }
    }
    return nil
  }

  // MARK: - Device Clock

  private static let clockResponseDateFormat = "HH:mm d/M/yyyy"

  /// Parses a firmware clock response like "06:40 - 18/4/2025 UTC" into a `Date`.
  /// Returns `nil` when the text doesn't carry the expected UTC clock shape.
  public static func utcDate(fromClockResponse response: String) -> Date? {
    // Regex isn't Sendable, so the literal lives here instead of in a static.
    let clockResponseRegex = /(\d{1,2}:\d{2}) - (\d{1,2}\/\d{1,2}\/\d{4}) UTC/
    guard let match = response.firstMatch(of: clockResponseRegex) else { return nil }

    // A fresh formatter per call keeps this Sendable; clock parsing is rare.
    let formatter = DateFormatter()
    formatter.dateFormat = clockResponseDateFormat
    formatter.timeZone = TimeZone(identifier: "UTC")
    return formatter.date(from: "\(match.output.1) \(match.output.2)")
  }

  // MARK: - Clock Sync

  /// Outcome of a `clock sync` command response.
  public enum ClockSyncOutcome: Equatable, Sendable {
    case synced
    /// Firmware refused the sync because its clock is ahead of the phone's.
    case clockAhead
    /// Firmware reported an error; `message` is the response with the
    /// "ERR: " prefix stripped and may be empty.
    case failed(message: String)
    case unexpected
  }

  private static let clockAheadErrorFragment = "clock cannot go backwards"
  private static let cliErrorPrefix = "ERR: "

  /// Classifies a `clock sync` response into a typed outcome.
  public static func classifyClockSyncResponse(_ response: String) -> ClockSyncOutcome {
    switch CLIResponse.parse(response) {
    case .ok:
      return .synced
    case let .error(message):
      if message.contains(clockAheadErrorFragment) {
        return .clockAhead
      }
      return .failed(message: message.replacing(cliErrorPrefix, with: ""))
    default:
      return .unexpected
    }
  }

  // MARK: - Password

  /// Firmware echoes "password now: {pw}" on success instead of "OK".
  private static let passwordChangedPrefix = "password now:"

  /// Whether a `password` command response indicates the change was accepted.
  public static func isPasswordChangeSuccessful(_ response: String) -> Bool {
    switch CLIResponse.parse(response) {
    case .ok:
      true
    case let .raw(text):
      text.hasPrefix(passwordChangedPrefix)
    default:
      false
    }
  }

  // MARK: - Owner Info

  /// Firmware stores owner info as a single line with "|" separating rows.
  private static let ownerInfoWireSeparator = "|"
  private static let ownerInfoDisplaySeparator = "\n"

  /// Maps the wire form ("|"-separated) to the multi-line display form.
  public static func displayOwnerInfo(fromWire wire: String) -> String {
    wire.replacing(ownerInfoWireSeparator, with: ownerInfoDisplaySeparator)
  }

  /// Maps the multi-line display form back to the "|"-separated wire form.
  public static func wireOwnerInfo(fromDisplay display: String) -> String {
    display.replacing(ownerInfoDisplaySeparator, with: ownerInfoWireSeparator)
  }
}
