import Foundation

/// A repeater or room command that gets no reply.
/// Matched on the lowercased, trimmed verb, or that verb plus a space. `poweroffx` does not match.
public enum RemoteCLINoReplyCommand: Sendable, Equatable {
  private static let rebootVerb = "reboot"
  private static let clockRebootVerb = "clkreboot"
  private static let powerOffVerb = "poweroff"
  private static let shutdownVerb = "shutdown"
  private static let ignoredSpace: UInt8 = 0x20
  case reboot
  case powerOff

  public init?(_ command: String) {
    let normalized = command.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
    if Self.matches(normalized, verb: Self.rebootVerb) || Self.matches(normalized, verb: Self.clockRebootVerb) {
      self = .reboot
    } else if Self.matches(normalized, verb: Self.powerOffVerb) || Self.matches(normalized, verb: Self.shutdownVerb) {
      self = .powerOff
    } else {
      return nil
    }
  }

  /// Sensor firmware matches lowercase prefixes after removing leading ASCII spaces.
  public static func sensorCommand(_ command: String) -> Self? {
    let wire = String(decoding: command.utf8.drop(while: { $0 == Self.ignoredSpace }), as: UTF8.self)
    if wire.hasPrefix(Self.rebootVerb) || wire.hasPrefix(Self.clockRebootVerb) { return .reboot }
    if wire.hasPrefix(Self.powerOffVerb) || wire.hasPrefix(Self.shutdownVerb) { return .powerOff }
    return nil
  }

  private static func matches(_ normalized: String, verb: String) -> Bool {
    normalized == verb || normalized.hasPrefix(verb + " ")
  }
}
