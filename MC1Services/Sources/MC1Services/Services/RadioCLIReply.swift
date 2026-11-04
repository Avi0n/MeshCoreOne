import Foundation

/// A framed Radio CLI value preserves its payload; reply markers ignore trailing CR and LF.
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
    let marker = Self.strippingTrailingLineEndings(text)
    if marker == "Unknown command" {
      self = .unknownCommand
      return
    }
    if marker.hasPrefix("Error") || marker.hasPrefix("ERROR") || marker.hasPrefix("ERR") {
      self = .error(marker)
      return
    }
    if text.hasPrefix("> ") {
      self = .value(String(text.dropFirst(2)))
      return
    }
    if marker.hasPrefix("OK") {
      self = .ok(marker)
      return
    }
    self = .other(marker)
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
