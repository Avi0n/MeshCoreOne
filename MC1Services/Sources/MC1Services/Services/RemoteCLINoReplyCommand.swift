import Foundation

/// A repeater or room command that gets no reply.
/// Matched on the lowercased, trimmed verb, or that verb plus a space. `poweroffx` does not match.
public enum RemoteCLINoReplyCommand: Sendable, Equatable {
  case reboot
  case powerOff

  public init?(_ command: String) {
    let normalized = command.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
    if Self.matches(normalized, verb: "reboot") || Self.matches(normalized, verb: "clkreboot") {
      self = .reboot
    } else if Self.matches(normalized, verb: "poweroff") || Self.matches(normalized, verb: "shutdown") {
      self = .powerOff
    } else {
      return nil
    }
  }

  private static func matches(_ normalized: String, verb: String) -> Bool {
    normalized == verb || normalized.hasPrefix(verb + " ")
  }
}
