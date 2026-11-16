import Foundation

/// A firmware-enumerated setting; values remain verbatim, including empty values.
public struct SensorSetting: Sendable, Equatable, Identifiable {
  public let key: String
  public let value: String
  public var id: String {
    key
  }

  public static let correlationPrefixBytes = 3
  public static let maxCommandBytes = ProtocolLimits.maxMessageLength - correlationPrefixBytes
  private static let setPrefix = "sensor set "

  public init(key: String, value: String) {
    self.key = key
    self.value = value
  }

  public func command(setting value: String) throws -> String {
    guard Self.isToken(key), Self.isToken(value) else {
      throw RemoteNodeError.invalidSensorSetting
    }
    let command = Self.setPrefix + key + " " + value
    try Self.validateCommand(command)
    return command
  }

  public static func validateCommand(_ command: String) throws {
    guard !command.isEmpty, command.utf8.count <= maxCommandBytes,
          !command.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
      throw RemoteNodeError.invalidSensorSetting
    }
  }

  private static func isToken(_ value: String) -> Bool {
    !value.isEmpty && !value.unicodeScalars.contains {
      CharacterSet.whitespacesAndNewlines.contains($0) || CharacterSet.controlCharacters.contains($0)
    }
  }
}
