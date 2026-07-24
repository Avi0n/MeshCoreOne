import Foundation
import MC1Services

/// Firmware-parity output strings for local radio commands. Deliberately
/// unlocalized: the terminal shows raw device replies on remote sessions, and
/// this parity output is copy-pasted into bug reports and compared against
/// meshcore-cli.
enum LocalCommandOutput {
  static let ok = "OK"
  static let okRebooting = "OK - rebooting"
  static let zeroHopAdvert = "OK - zero-hop advert sent"
  static let floodAdvert = "OK - flood advert sent"

  // Custom-var output mirrors meshcore-cli's bare `get`/`set` and the repeater
  // text CLI: `Var <key> set to <value>` on a write, `Unknown var <key>` on a
  // read miss, `can't find custom var` when the firmware rejects a set.
  static let unknownCustomVar = "can't find custom var"
  static let noCustomVars = "no custom var"

  static func value(_ text: String) -> String {
    "> \(text)"
  }

  static func unknownVar(_ key: String) -> String {
    "Unknown var \(key)"
  }

  static func customVarSet(key: String, value: String) -> String {
    "Var \(key) set to \(value)"
  }

  /// The `get custom` reply: a `N vars` header then one `name=value` line per
  /// var, keys sorted (the dictionary decode loses firmware order), or the
  /// empty-set string.
  static func customVarList(_ vars: [String: String]) -> String {
    guard !vars.isEmpty else { return noCustomVars }
    let lines = vars.keys.sorted().map { "\($0)=\(vars[$0] ?? "")" }
    return "\(vars.count) vars\n" + lines.joined(separator: "\n")
  }

  static func clock(_ date: Date) -> String {
    formatClock(date)
  }

  static func clockSet(_ date: Date) -> String {
    "OK - clock set: \(formatClock(date))"
  }

  static func radio(_ info: SelfInfo) -> String {
    "\(decimal(info.radioFrequency)),\(decimal(info.radioBandwidth)),\(info.radioSpreadingFactor),\(info.radioCodingRate)"
  }

  static func hex(_ data: Data) -> String {
    data.map { String(format: "%02X", $0) }.joined()
  }

  /// kHz value from MHz for `setRadioParamsVerified(frequencyKHz:)`.
  static func freqKHz(_ mhz: Double) -> UInt32 {
    UInt32((mhz * 1000).rounded())
  }

  /// Hz value from kHz for `setRadioParamsVerified(bandwidthKHz:)` (the parameter
  /// is Hz despite its name).
  static func bandwidthHz(_ khz: Double) -> UInt32 {
    UInt32((khz * 1000).rounded())
  }

  static func decimal(_ value: Double) -> String {
    trimmed(String(format: "%.3f", value))
  }

  static func coordinate(_ value: Double) -> String {
    trimmed(String(format: "%.6f", value))
  }

  private static func trimmed(_ formatted: String) -> String {
    guard formatted.contains(".") else { return formatted }
    var result = formatted
    while result.hasSuffix("0") {
      result.removeLast()
    }
    if result.hasSuffix(".") { result.removeLast() }
    return result
  }

  private static func formatClock(_ date: Date) -> String {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "UTC") ?? .gmt
    let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: date)
    return String(
      format: "%02d:%02d - %d/%d/%d UTC",
      parts.hour ?? 0, parts.minute ?? 0, parts.day ?? 0, parts.month ?? 0, parts.year ?? 0
    )
  }
}
