import MC1Services

/// Decides whether a local Tools CLI line should go to `CMD_RUN_CLI_COMMAND`
/// instead of opcode-mapped verbs or custom vars.
enum RadioCLIPassthrough {
  static let getSetKeys: Set<String> = [
    "af", "dutycycle", "int.thresh", "cad", "radio.rxgain", "radio.rxps", "rxdelay",
    "agc.reset.interval", "txdelay", "direct.txdelay",
    RadioCLIKey.timezoneOffset, RadioCLIKey.wifiSSID, RadioCLIKey.wifiPassword, RadioCLIKey.wifiEnabled,
    "radio.fem.rxgain", "radio.fem.txgain", "fan",
  ]
  static let getOnlyKeys: Set<String> = [RadioCLIKey.wifiStatus, RadioCLIKey.wifiIP]
  static let setOnlyKeys: Set<String> = ["pin", RadioCLIKey.wifiClear, "fan.lo", "fan.hi"]

  /// Power-off verbs stay app-mapped because the radio powers off without a CLI reply.
  private static let mappedFirstWords: Set<String> = [
    "clock", "ver", "board", "advert", "advert.zerohop", "floodadv", "reboot", "poweroff", "shutdown",
    "get", "set", "help", "clear", "session", "login", "logout", "nodes", "channels",
  ]

  static func shouldSend(_ line: String) -> Bool {
    let parts = line.split(whereSeparator: \.isWhitespace).map { $0.lowercased() }
    guard let first = parts.first else { return false }

    if first == "get" {
      guard parts.count >= 2 else { return false }
      let key = parts[1]
      if key.hasPrefix("_") { return false }
      return getSetKeys.contains(key) || getOnlyKeys.contains(key)
    }
    if first == "set" {
      guard parts.count >= 2 else { return false }
      let key = parts[1]
      if key.hasPrefix("_") { return false }
      return getSetKeys.contains(key) || setOnlyKeys.contains(key)
    }
    if mappedFirstWords.contains(first) { return false }
    return true
  }
}
