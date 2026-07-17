import Foundation

/// Pure parsing of repeater and room CLI responses for the node settings
/// screens: clock parsing, owner-info wire mapping, and success/error
/// classification. Builds on `CLIResponse` and holds no state, so every
/// function is directly unit-testable.
public enum NodeSettingsResponseParser {
  // MARK: - Late Reply Recovery

  /// Attribution by elimination for an out-of-band CLI reply. One command is
  /// in flight per node, so a reply that no pending command claimed can only
  /// answer a command that timed out unanswered. Returns the parsed value when
  /// the reply parses to a query-specific shape for exactly one of the given
  /// queries; several matches are ambiguous, so nothing is returned.
  public static func recoveredResponse(
    _ response: String,
    unansweredQueries: Set<String>
  ) -> (query: String, value: CLIResponse)? {
    let matches = unansweredQueries.compactMap { query -> (query: String, value: CLIResponse)? in
      guard CLIResponse.isStructuredQuery(query) else { return nil }
      switch CLIResponse.parse(response, forQuery: query) {
      case .raw, .ok, .error, .unknownCommand, .version:
        // Query-independent shapes match every query equally; not attributable.
        return nil
      case let value:
        return (query, value)
      }
    }
    guard matches.count == 1, let match = matches.first else { return nil }
    return match
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
