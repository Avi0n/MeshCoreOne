import Foundation

/// Parsed CLI response from repeater
public enum CLIResponse: Sendable, Equatable {
  case ok
  case error(String)
  case unknownCommand(String) // Specific case for "Error: unknown command"
  case version(String)
  case deviceTime(String)
  case name(String)
  case radio(frequency: Double, bandwidth: Double, spreadingFactor: Int, codingRate: Int)
  case txPower(Int)
  case repeatMode(Bool)
  case advertInterval(Int)
  case floodAdvertInterval(Int) // Value is in hours, not minutes
  case floodMax(Int)
  case latitude(Double)
  case longitude(Double)
  case ownerInfo(String)
  case raw(String)

  /// Canonical query strings. `parse`'s query hints and `structuredQueries`
  /// both build on these so a new query can't join one and drift from the other.
  private enum Query {
    static let version = "ver"
    static let name = "get name"
    static let ownerInfo = "get owner.info"
    static let clock = "clock"
    static let radio = "get radio"
    static let txPower = "get tx"
    static let repeatMode = "get repeat"
    static let advertInterval = "get advert.interval"
    static let floodAdvertInterval = "get flood.advert.interval"
    static let floodMax = "get flood.max"
    static let latitude = "get lat"
    static let longitude = "get lon"
  }

  /// Parse a CLI response text into a structured type
  /// Note: Response correlation must be handled by the caller based on pending query tracking
  public static func parse(_ text: String, forQuery query: String? = nil) -> CLIResponse {
    var trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)

    // Strip MeshCore CLI prompt prefix if present
    // Firmware prepends "> " to all CLI command responses
    if trimmed.hasPrefix("> ") {
      trimmed = String(trimmed.dropFirst(2))
    } else if trimmed == ">" {
      trimmed = ""
    }

    // Success responses: "OK" or "OK - clock set: ..." etc.
    if trimmed == "OK" || trimmed.hasPrefix("OK - ") {
      return .ok
    }

    if trimmed.lowercased().hasPrefix("error") || trimmed.hasPrefix("ERR:") {
      // Check for "unknown command" specifically for defensive handling
      if trimmed.lowercased().contains("unknown command") {
        return .unknownCommand(trimmed)
      }
      return .error(trimmed)
    }

    // Firmware version: "MeshCore v1.10.0 (2025-04-18)" or "v1.11.0 (2025-04-18)"
    // Some firmware builds omit "MeshCore " prefix
    if trimmed.hasPrefix("MeshCore v") || (trimmed.hasPrefix("v") && trimmed.contains("(")) {
      return .version(trimmed)
    }

    // Use query hint to match version responses that don't have standard prefix
    if query == Query.version {
      return .version(trimmed)
    }

    // Freeform text fields: any remaining text is the value
    if query == Query.name {
      return .name(trimmed)
    }

    if query == Query.ownerInfo {
      return .ownerInfo(trimmed)
    }

    // Clock response: "06:40 - 18/4/2025 UTC". Gated on the query because the
    // ":" + "/" shape also appears in names and owner info like
    // "Contact: KD7ABC / 145.230".
    if query == Query.clock, trimmed.contains("UTC") || (trimmed.contains(":") && trimmed.contains("/")) {
      return .deviceTime(trimmed)
    }

    // Radio params: "915.000,250.0,10,5" (freq,bw,sf,cr)
    // Use query hint to disambiguate from other comma-separated values
    if query == Query.radio {
      let parts = trimmed.split(separator: ",").map { String($0).trimmingCharacters(in: .whitespaces) }
      if parts.count >= 4,
         let freq = Double(parts[0]),
         let bw = Double(parts[1]),
         let sf = Int(parts[2]),
         let cr = Int(parts[3]) {
        return .radio(frequency: freq, bandwidth: bw, spreadingFactor: sf, codingRate: cr)
      }
    }

    // TX power in dBm, optionally annotated with ZephCore power-control state
    if query == Query.txPower, let power = parseTXPowerDBm(trimmed) {
      return .txPower(power)
    }

    // Repeat mode: "on" or "off"
    if query == Query.repeatMode {
      if trimmed.lowercased() == "on" {
        return .repeatMode(true)
      } else if trimmed.lowercased() == "off" {
        return .repeatMode(false)
      }
    }

    // Advert interval: integer minutes
    if query == Query.advertInterval, let interval = Int(trimmed) {
      return .advertInterval(interval)
    }

    // Flood advert interval: integer hours
    if query == Query.floodAdvertInterval, let interval = Int(trimmed) {
      return .floodAdvertInterval(interval)
    }

    // Flood max: integer hops
    if query == Query.floodMax, let maxHops = Int(trimmed) {
      return .floodMax(maxHops)
    }

    // Latitude: decimal degrees
    if query == Query.latitude, let lat = Double(trimmed) {
      return .latitude(lat)
    }

    // Longitude: decimal degrees
    if query == Query.longitude, let lon = Double(trimmed) {
      return .longitude(lon)
    }

    return .raw(trimmed)
  }

  /// Reads the configured TX power in dBm from a `get tx` reply. Stock firmware
  /// sends a bare integer ("> 22"); ZephCore appends Adaptive Power Control
  /// state ("> 22dBm (apc=off)"), and while APC is active the leading number is
  /// the reduced live power whereas the `max=` ceiling is what `set tx` writes
  /// back, so `max=` wins when present.
  private static func parseTXPowerDBm(_ text: String) -> Int? {
    if let match = text.firstMatch(of: /max=(-?\d+)/) {
      return Int(match.output.1)
    }
    // Accept only a whole leading integer ("22", "22dBm (apc=off)"); a digit
    // run followed by "." or "," is some other value, such as the leading
    // frequency of a radio CSV, never a power.
    if let match = text.firstMatch(of: /^(-?\d+)(?:dBm|\s|$)/) {
      return Int(match.output.1)
    }
    return nil
  }

  /// Queries whose replies have a machine-checkable shape. Free-form gets and
  /// set/action commands are absent because their success replies are
  /// arbitrary text: firmware answers `password` with "password now:", not
  /// "OK", and letsmesh builds answer `ver` with
  /// "1.11.0-letsmesh.net-dev-... (Build: ...)" that no shape check covers.
  private static let structuredQueries: Set<String> = [
    Query.radio, Query.txPower, Query.repeatMode, Query.advertInterval,
    Query.floodAdvertInterval, Query.floodMax, Query.latitude, Query.longitude,
    Query.clock,
  ]

  /// Whether the query's reply has a machine-checkable shape.
  public static func isStructuredQuery(_ query: String) -> Bool {
    structuredQueries.contains(query)
  }

  /// Whether a reply is plausible for the given pending query. Replies to
  /// structured gets must parse to their typed case (or an error); everything
  /// else matches any text.
  public static func isPlausibleResponse(_ response: String, forQuery query: String) -> Bool {
    guard structuredQueries.contains(query) else { return true }
    if case .raw = parse(response, forQuery: query) {
      return false
    }
    return true
  }

  // MARK: - Wire Prefix Echo

  /// Separator of the optional CLI wire prefix. Repeater and room firmware
  /// reflect a leading "XX|" from the command back at the start of the reply,
  /// giving the otherwise tagless CLI channel a correlation token.
  static let echoPrefixSeparator: Character = "|"

  /// Length of the wire prefix including the separator.
  private static let echoPrefixLength = 3

  /// Splits an echoed wire prefix off a reply. Returns nil when the reply
  /// carries none. Only two hex digits plus the separator qualify, matching
  /// the prefixes this app generates, so ordinary reply text can't be
  /// mistaken for a prefix.
  public static func splitEchoedPrefix(_ text: String) -> (prefix: String, body: String)? {
    guard text.count > echoPrefixLength,
          text.prefix(echoPrefixLength).wholeMatch(of: /[0-9A-F]{2}\|/) != nil else {
      return nil
    }
    let splitIndex = text.index(text.startIndex, offsetBy: echoPrefixLength)
    return (String(text[..<splitIndex]), String(text[splitIndex...]))
  }
}
