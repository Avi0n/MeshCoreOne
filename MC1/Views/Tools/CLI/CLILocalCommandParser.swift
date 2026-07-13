import Foundation

/// The three load-bearing outcomes of parsing a local-session line: a first word
/// that isn't a local radio command (caller falls through to the existing
/// dispatcher), a parsed-and-validated command, or a recognized command with
/// bad input (caller renders usage/error).
enum CLILocalParseResult: Equatable {
  case notLocal
  case command(CLILocalCommand)
  case invalid(CLILocalParseError)
}

/// A recognized-but-rejected local command. Carries only enough to pick a usage
/// or error message; rendering (and L10n) is the executor's job.
enum CLILocalParseError: Equatable {
  case badArguments(CLILocalUsage)
  case valueOutOfRange
  case invalidCustomVarToken
}

/// Which usage line to render for a `.badArguments` result.
enum CLILocalUsage: Equatable {
  case get
  case set
  case setRadio
}

/// Parses a local-session input line into a typed command or error. Pure and
/// dependency-free: no services, no async, no L10n.
enum CLILocalCommandParser {
  // Firmware validation bounds on the companion BLE path (`CMD_SET_RADIO_PARAMS`).
  private static let frequencyRangeMHz = 150.0...2500.0
  private static let bandwidthRangeKHz = 7.0...500.0
  private static let spreadingFactorRange: ClosedRange<UInt8> = 5...12
  private static let codingRateRange: ClosedRange<UInt8> = 5...8
  private static let latitudeRange = -90.0...90.0
  private static let longitudeRange = -180.0...180.0
  private static let multiAcksRange: ClosedRange<UInt8> = 0...1
  private static let pathHashModeRange: ClosedRange<UInt8> = 0...2

  // The `CMD_GET_CUSTOM_VARS` reply concatenates pairs as `name:value` joined by
  // commas, so a key may contain neither separator and a value may not contain a
  // comma; a `:` inside a value is safe because each pair splits at its first `:`.
  private static let customVarReservedInKey: Set<Character> = [":", ","]
  private static let customVarReservedInValue: Set<Character> = [","]

  /// The `get custom` dump verb, and the leading-`_` escape hatch that forces a
  /// key onto the custom-var path even when it collides with a typed key.
  private static let customVarDumpKey = "custom"
  private static let customVarEscapePrefix = "_"

  /// Firmware's per-pair enumeration budget: the `CMD_GET_CUSTOM_VARS` handler
  /// starts a new pair only while the reply offset is under 140 bytes, so a pair
  /// of at most 139 bytes always stays individually enumerable. Chosen over the
  /// 174-byte set-frame limit so a var written here can always be read back by
  /// `get custom`/`get <key>`.
  private static let maxCustomVarPairBytes = 139

  /// Parses a trimmed local-session input line.
  static func parse(_ line: String) -> CLILocalParseResult {
    let trimmed = line.trimmingCharacters(in: .whitespaces)
    guard !trimmed.isEmpty else { return .notLocal }

    let parts = trimmed.split(separator: " ", maxSplits: 1, omittingEmptySubsequences: true).map(String.init)
    let command = parts[0].lowercased()
    let rest = parts.count > 1 ? parts[1] : ""

    switch command {
    case "clock":
      let sub = rest.trimmingCharacters(in: .whitespaces).lowercased()
      return sub == "sync" ? .command(.clockSync) : .command(.clock)
    case "ver":
      return .command(.ver)
    case "board":
      return .command(.board)
    case "advert", "advert.zerohop":
      return .command(.advert(flood: false))
    case "floodadv":
      return .command(.advert(flood: true))
    case "reboot":
      return .command(.reboot)
    case "get":
      return parseGet(rest)
    case "set":
      return parseSet(rest)
    default:
      return .notLocal
    }
  }

  private static func isValidCustomVarKey(_ key: String) -> Bool {
    !key.isEmpty && !key.contains(where: customVarReservedInKey.contains)
  }

  private static func isValidCustomVarValue(_ value: String) -> Bool {
    !value.contains(where: customVarReservedInValue.contains)
  }

  /// The dump verb and typed keys match on the folded first token; anything else
  /// falls through to a companion custom-var read (verbatim key, one leading `_`
  /// stripped). A get never puts an unknown key on the wire; the runner fetches
  /// all vars and looks the key up client-side.
  private static func parseGet(_ rest: String) -> CLILocalParseResult {
    let rawToken = firstToken(rest)
    guard !rawToken.isEmpty else { return .invalid(.badArguments(.get)) }

    let lowered = rawToken.lowercased()
    if lowered == customVarDumpKey { return .command(.getCustomVars) }
    if let key = CLILocalKey(rawValue: lowered) { return .command(.getKey(key)) }

    let key = strippedCustomVarKey(rawToken)
    guard !key.isEmpty else { return .invalid(.badArguments(.get)) }
    guard isValidCustomVarKey(key) else { return .invalid(.invalidCustomVarToken) }
    return .command(.getCustomVar(key))
  }

  private static func parseSet(_ rest: String) -> CLILocalParseResult {
    let trimmed = rest.trimmingCharacters(in: .whitespaces)
    guard !trimmed.isEmpty else { return .invalid(.badArguments(.set)) }

    let parts = trimmed.split(separator: " ", maxSplits: 1, omittingEmptySubsequences: true).map(String.init)
    // Keep the key verbatim so a custom-var fallthrough writes the exact name;
    // typed matching folds case below.
    let keyToken = parts[0]
    // Trim so extra spacing between key and value (e.g. "set tx  22") doesn't
    // leak a leading space into numeric parses or the name; inner spaces survive.
    let value = parts.count > 1 ? parts[1].trimmingCharacters(in: .whitespaces) : ""

    guard let key = CLILocalKey(rawValue: keyToken.lowercased()) else {
      return parseCustomVarSet(keyToken, value: value)
    }

    switch key {
    case .name:
      guard !value.isEmpty else { return .invalid(.badArguments(.set)) }
      return .command(.setName(value))
    case .lat:
      return parseDouble(value, in: latitudeRange).map { .command(.setLatitude($0)) } ?? invalidNumber(value)
    case .lon:
      return parseDouble(value, in: longitudeRange).map { .command(.setLongitude($0)) } ?? invalidNumber(value)
    case .tx:
      guard let raw = Int(value) else { return .invalid(.badArguments(.set)) }
      guard let power = Int8(exactly: raw) else { return .invalid(.valueOutOfRange) }
      return .command(.setTxPower(power))
    case .radio:
      return parseSetRadio(value)
    case .freq:
      return parseDouble(value, in: frequencyRangeMHz).map { .command(.setFrequency($0)) } ?? invalidNumber(value)
    case .multiAcks:
      return parseUInt8(value, in: multiAcksRange).map { .command(.setMultiAcks($0)) } ?? invalidNumber(value)
    case .pathHashMode:
      return parseUInt8(value, in: pathHashModeRange).map { .command(.setPathHashMode($0)) } ?? invalidNumber(value)
    case .publicKey, .bat:
      // Read-only keys: reject with the settable-keys usage line.
      return .invalid(.badArguments(.set))
    }
  }

  /// Routes a `set` whose key is not a typed setting to a companion custom var,
  /// stripping one leading `_` (the escape hatch for a fork var whose name
  /// collides with a typed key). A missing key or value is an arguments problem;
  /// only a reserved char or an over-budget pair is `.invalidCustomVarToken`.
  private static func parseCustomVarSet(_ keyToken: String, value: String) -> CLILocalParseResult {
    let key = strippedCustomVarKey(keyToken)
    guard !key.isEmpty, !value.isEmpty else { return .invalid(.badArguments(.set)) }
    guard isValidCustomVarKey(key),
          isValidCustomVarValue(value),
          "\(key):\(value)".utf8.count <= maxCustomVarPairBytes else {
      return .invalid(.invalidCustomVarToken)
    }
    return .command(.setCustomVar(key: key, value: value))
  }

  private static func parseSetRadio(_ value: String) -> CLILocalParseResult {
    let fields = value.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
    guard fields.count == 4,
          let freq = Double(fields[0]),
          let bandwidth = Double(fields[1]),
          let spreadingFactor = UInt8(fields[2]),
          let codingRate = UInt8(fields[3]) else {
      return .invalid(.badArguments(.setRadio))
    }
    guard frequencyRangeMHz.contains(freq),
          bandwidthRangeKHz.contains(bandwidth),
          spreadingFactorRange.contains(spreadingFactor),
          codingRateRange.contains(codingRate) else {
      return .invalid(.valueOutOfRange)
    }
    return .command(.setRadio(
      frequencyMHz: freq,
      bandwidthKHz: bandwidth,
      spreadingFactor: spreadingFactor,
      codingRate: codingRate
    ))
  }

  // MARK: - Helpers

  /// The first whitespace-delimited token of a string, or empty.
  private static func firstToken(_ string: String) -> String {
    string.split(separator: " ", omittingEmptySubsequences: true).first.map(String.init) ?? ""
  }

  /// Strips one leading `_` so a custom-var name that collides with a typed key
  /// (or the `custom` dump verb) can still be reached; a bare `_` strips to empty.
  private static func strippedCustomVarKey(_ token: String) -> String {
    token.hasPrefix(customVarEscapePrefix) ? String(token.dropFirst()) : token
  }

  /// Parses a `Double` and range-checks it. Returns nil for both non-numeric and
  /// out-of-range input; callers map those to distinct errors via `invalidNumber`.
  private static func parseDouble(_ value: String, in range: ClosedRange<Double>) -> Double? {
    guard let parsed = Double(value), range.contains(parsed) else { return nil }
    return parsed
  }

  private static func parseUInt8(_ value: String, in range: ClosedRange<UInt8>) -> UInt8? {
    guard let parsed = UInt8(value), range.contains(parsed) else { return nil }
    return parsed
  }

  /// Distinguishes non-numeric input (bad arguments) from an out-of-range number.
  private static func invalidNumber(_ value: String) -> CLILocalParseResult {
    Double(value) == nil ? .invalid(.badArguments(.set)) : .invalid(.valueOutOfRange)
  }
}
