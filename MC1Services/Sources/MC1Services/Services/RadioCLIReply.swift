import Foundation

/// One Radio CLI reply, classified after trailing CR and LF are removed.
public enum RadioCLIReply: Sendable, Equatable {
  case unknownCommand
  case error(String)
  case value(String)
  case ok(String)
  case other(String)

  /// Firmware prints this when a WiFi field has no saved value.
  /// An SSID with this exact text is indistinguishable from an empty field.
  public static let notSet = "(not set)"

  /// Firmware prints this when WiFi has no association.
  public static let notConnected = "(not connected)"

  public init(text: String) {
    let text = Self.strippingTrailingLineEndings(text)
    if text == "Unknown command" {
      self = .unknownCommand
      return
    }
    if text.hasPrefix("Error") || text.hasPrefix("ERROR") || text.hasPrefix("ERR") {
      self = .error(text)
      return
    }
    if text.hasPrefix("> ") {
      self = .value(String(text.dropFirst(2)))
      return
    }
    if text.hasPrefix("OK") {
      self = .ok(text)
      return
    }
    self = .other(text)
  }

  /// Drops trailing U+000D and U+000A scalars. `"\r\n"` is one `Character`,
  /// so a character walk would leave that pair in place.
  private static func strippingTrailingLineEndings(_ text: String) -> String {
    var text = text
    while let last = text.unicodeScalars.last, last.value == 0x0D || last.value == 0x0A {
      text.unicodeScalars.removeLast()
    }
    return text
  }
}
