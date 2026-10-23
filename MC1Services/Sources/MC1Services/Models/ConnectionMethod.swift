import Foundation

/// Represents a method for connecting to a MeshCore device.
public enum ConnectionMethod: Codable, Sendable, Identifiable, Hashable {
  /// Bluetooth Low Energy connection
  case bluetooth(peripheralUUID: UUID, displayName: String?)

  /// WiFi TCP connection
  case wifi(host: String, port: UInt16, displayName: String?)

  // MARK: - Identifiable

  public var id: String {
    switch self {
    case let .bluetooth(uuid, _):
      "ble:\(uuid.uuidString)"
    case let .wifi(host, port, _):
      "wifi:\(host):\(port)"
    }
  }

  // MARK: - Convenience

  /// User-assigned display name, if any.
  public var displayName: String? {
    switch self {
    case let .bluetooth(_, name), let .wifi(_, _, name):
      name
    }
  }

  /// Whether this is a Bluetooth connection.
  public var isBluetooth: Bool {
    if case .bluetooth = self { return true }
    return false
  }

  /// Whether this is a WiFi connection.
  public var isWiFi: Bool {
    if case .wifi = self { return true }
    return false
  }

  /// Companion WiFi TCP port. Firmware `TCP_PORT` defaults to this, and the CLI cannot read it.
  public static let defaultWiFiPort: UInt16 = 5000

  /// Keeps the first endpoint of each transport.
  /// A later row for the same transport is a duplicate, not a newer connection.
  static func canonicalized(_ methods: [ConnectionMethod]) -> [ConnectionMethod] {
    var result: [ConnectionMethod] = []
    for method in methods {
      switch method {
      case .bluetooth:
        guard !result.contains(where: \.isBluetooth) else { continue }
      case .wifi:
        guard !result.contains(where: \.isWiFi) else { continue }
      }
      result.append(method)
    }
    return result
  }

  /// Short description for display in lists.
  public var shortDescription: String {
    switch self {
    case .bluetooth:
      return "Bluetooth"
    case let .wifi(host, port, _):
      if port == Self.defaultWiFiPort {
        return host
      }
      return "\(host):\(port)"
    }
  }
}
