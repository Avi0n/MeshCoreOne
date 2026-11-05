import Foundation

/// Models saved WiFi overrides separately from build defaults and the boot-time association.
struct RadioWiFiFirmware: Sendable {
  var savedSSID = ""
  var savedPassword = ""
  var enabled = true
  let defaultSSID: String
  let defaultPassword: String
  private var associatedSSID = ""
  private var associatedPassword = ""

  init(defaultSSID: String = "", defaultPassword: String = "") {
    self.defaultSSID = defaultSSID
    self.defaultPassword = defaultPassword
    boot()
  }

  mutating func boot() {
    associatedSSID = enabled ? reportedSSID : ""
    associatedPassword = savedSSID.isEmpty ? defaultPassword : savedPassword
  }

  func bootUses(ssid: String, password: String) -> Bool {
    associatedSSID == ssid && associatedPassword == password
  }

  static func reflectedReply(_ reply: String, to command: String) -> String {
    let bytes = Array(command.utf8.drop(while: { $0 == 0x20 }))
    let prefix = bytes.count > 4 && bytes[2] == 0x7C ? String(decoding: bytes.prefix(3), as: UTF8.self) : ""
    return prefix + reply
  }

  mutating func reply(to command: String) -> String? {
    var bytes = Array(command.utf8.drop(while: { $0 == 0x20 }))
    if bytes.count > 4, bytes[2] == 0x7C { bytes.removeFirst(3) }
    let effective = String(decoding: bytes, as: UTF8.self)
    return Self.reflectedReply(replyToEffectiveCommand(effective) ?? "Unknown command", to: command)
  }

  private mutating func replyToEffectiveCommand(_ command: String) -> String? {
    switch command {
    case "get wifi.enabled":
      return enabled ? "> 1" : "> 0"
    case "get wifi.ssid":
      return reportedSSID.isEmpty ? "> (not set)" : "> \(reportedSSID)"
    case "get wifi.pwd":
      return "> \(savedPassword)"
    case "get wifi.status":
      return associatedSSID.isEmpty ? "> disconnected" : "> connected"
    case "get wifi.ip":
      return associatedSSID.isEmpty ? "> (not connected)" : "> 10.0.0.8"
    case "set wifi.clear":
      savedSSID = ""
      savedPassword = ""
      return "> wifi config cleared (reboot to apply)"
    default:
      break
    }
    if command.hasPrefix("set wifi.ssid ") {
      savedSSID = String(command.dropFirst("set wifi.ssid ".count))
      return "> wifi.ssid updated (reboot to apply)"
    }
    if command.hasPrefix("set wifi.pwd ") {
      savedPassword = String(command.dropFirst("set wifi.pwd ".count))
      return "> wifi.pwd updated (reboot to apply)"
    }
    if command.hasPrefix("set wifi.enabled ") {
      enabled = command.dropFirst("set wifi.enabled ".count) != "0"
      return "> wifi.enabled updated (reboot to apply)"
    }
    return nil
  }

  private var reportedSSID: String {
    savedSSID.isEmpty ? defaultSSID : savedSSID
  }
}
