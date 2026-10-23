import Foundation
import MC1Services

/// The checked link and, while a switch is running, its target.
/// Dial facts stay as they were when the record was created, so a later radio snapshot cannot change the host or the port.
struct ConnectionLinkSwitch: Equatable, Sendable {
  var radioID: UUID
  /// `Device.id` captured for a Wi-Fi failure alert. A Bluetooth alert uses the peripheral id.
  var deviceID: UUID
  var checked: TransportType
  var target: TransportType?
  var token: UUID
  var bluetoothPeripheralID: UUID?
  var wifi: ConnectionLinkWiFiDial?

  var displayHost: String? {
    guard checked == .wifi else { return nil }
    return wifi?.displayHost
  }

  enum Completion: Equatable, Sendable {
    case ignored
    case cleared
    case held(ConnectionLinkSwitch)
  }

  /// Declines when a target is already running, the target is the live transport, or that link has no dial fact.
  /// An existing record keeps its dial facts. An accepted start replaces the token before any connect call.
  static func start(
    existing: ConnectionLinkSwitch?,
    target: TransportType,
    liveTransport: TransportType?,
    radioID: UUID?,
    deviceID: UUID?,
    bluetoothPeripheralID: UUID?,
    wifi: ConnectionLinkWiFiDial?,
    token: UUID
  ) -> ConnectionLinkSwitch? {
    if existing?.target != nil {
      return nil
    }
    if liveTransport == target {
      return nil
    }

    let base: ConnectionLinkSwitch
    if let existing {
      base = existing
    } else {
      guard let radioID, let deviceID, let checked = liveTransport else {
        return nil
      }
      base = ConnectionLinkSwitch(
        radioID: radioID,
        deviceID: deviceID,
        checked: checked,
        target: nil,
        token: token,
        bluetoothPeripheralID: bluetoothPeripheralID,
        wifi: wifi
      )
    }

    let canDial = switch target {
    case .bluetooth:
      base.bluetoothPeripheralID != nil
    case .wifi:
      base.wifi?.canDial == true
    }
    guard canDial else { return nil }

    var started = base
    started.target = target
    started.token = token
    return started
  }

  /// A token from an older attempt writes nothing. A different radio drops the record.
  func complete(
    token: UUID,
    threw: Bool,
    liveTransport: TransportType?,
    connectedRadioID: UUID?
  ) -> Completion {
    guard token == self.token, target != nil else { return .ignored }
    if !threw, liveTransport == target, connectedRadioID == radioID {
      return .cleared
    }
    if let connectedRadioID, connectedRadioID != radioID {
      return .cleared
    }
    var held = self
    held.target = nil
    return .held(held)
  }
}
