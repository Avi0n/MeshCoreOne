import Foundation

/// Centralized redaction utility for logging sensitive data.
enum LogRedaction {
  /// Formats public key prefix as hex string.
  /// Public keys are NOT redacted since they're public.
  /// - Parameters:
  ///   - key: The public key data
  ///   - prefixLength: Number of bytes to include (default 6)
  /// - Returns: Hex string representation
  static func publicKeyHex(_ key: Data, prefixLength: Int = 6) -> String {
    key.prefix(prefixLength).map { String(format: "%02x", $0) }.joined()
  }

  /// Redacts a node/room name to first 3 chars + "***".
  /// - Parameter name: The node or room name
  /// - Returns: Redacted name
  static func nodeName(_ name: String) -> String {
    guard name.count > 3 else { return "***" }
    return String(name.prefix(3)) + "***"
  }

  /// Redacts sensitive parts of CLI commands (passwords).
  /// - Parameter command: The CLI command string
  /// - Returns: Command with password values redacted
  static func cliCommand(_ command: String) -> String {
    let lower = command.lowercased()
    // Redact password values in CLI commands like "set password XYZ" or "password XYZ"
    if lower.hasPrefix("password") || lower.contains("set password") {
      let parts = command.split(separator: " ", maxSplits: 2)
      if parts.count >= 2 {
        return parts.dropLast().joined(separator: " ") + " [REDACTED]"
      }
    }
    // Truncate long commands
    return command.count <= 40 ? command : String(command.prefix(40)) + "..."
  }

  /// Placeholder for password values that should never be logged.
  static let passwordPlaceholder = "[REDACTED]"
}
