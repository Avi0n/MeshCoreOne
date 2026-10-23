import Foundation
import MC1Services

/// Host and port captured for a Wi-Fi dial. The row shows the host alone.
struct ConnectionLinkWiFiDial: Equatable, Sendable {
  var host: String
  var port: UInt16

  var displayHost: String? {
    let trimmed = WiFiAddressFields.normalizedHost(host)
    guard !trimmed.isEmpty else { return nil }
    return trimmed
  }

  var canDial: Bool {
    displayHost != nil
  }

  /// A live Wi-Fi session keeps its own endpoint. Otherwise the Device's saved host and port stay together.
  /// A reported address is used only when that saved endpoint is missing or invalid, and it uses the default port.
  static func capture(
    liveTransport: TransportType?,
    savedWiFi: ConnectionMethod?,
    snapshotHost: String?
  ) -> ConnectionLinkWiFiDial? {
    if liveTransport == .wifi {
      return endpoint(savedWiFi)
    }
    if let saved = endpoint(savedWiFi) {
      return saved
    }
    return reported(snapshotHost)
  }

  private static func endpoint(_ method: ConnectionMethod?) -> ConnectionLinkWiFiDial? {
    guard case let .wifi(host, port, _) = method else { return nil }
    let normalized = WiFiAddressFields.normalizedHost(host)
    guard WiFiAddressFields.isValidHost(normalized), WiFiAddressFields.isValidPort(String(port)) else {
      return nil
    }
    return ConnectionLinkWiFiDial(host: normalized, port: port)
  }

  private static func reported(_ snapshotHost: String?) -> ConnectionLinkWiFiDial? {
    guard let snapshotHost else { return nil }
    let host = WiFiAddressFields.normalizedHost(snapshotHost)
    guard WiFiAddressFields.isValidHost(host) else { return nil }
    return ConnectionLinkWiFiDial(host: host, port: ConnectionMethod.defaultWiFiPort)
  }
}
