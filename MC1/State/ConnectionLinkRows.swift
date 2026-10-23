import Foundation
import MC1Services

/// What the Connection row and the Connection page show for the phone's link.
enum ConnectionLinkRows {
  /// Whether the current firmware exposes Wi-Fi settings. Unknown includes a probe that has not succeeded.
  enum WiFiSettingsSupport: Equatable, Sendable {
    case unknown
    case supported
    case unavailable
  }

  struct Snapshot: Equatable, Sendable {
    var rows: [Row]
    var checked: TransportType?
    var target: TransportType?
    var settingsDetail: SettingsDetail
  }

  struct Row: Equatable, Sendable, Identifiable {
    var link: TransportType
    /// Host with no port. Nil when the Wi-Fi row has nothing to show under the title.
    var address: String?
    /// The row can start a switch. An in-progress attempt disables the control separately.
    var canSwitch: Bool

    var id: TransportType {
      link
    }
  }

  enum SettingsDetail: Equatable, Sendable {
    case bluetooth
    case host(String)
    case wifi
  }

  /// While `activeSwitch` exists, it supplies the rows, the check, and the host.
  /// Otherwise each row follows the live link, and a Wi-Fi switch needs a usable dial.
  static func make(
    liveTransport: TransportType?,
    savedPeripheralID: UUID?,
    wifiSupport: WiFiSettingsSupport,
    wifiAssociated: Bool,
    wifiDial: ConnectionLinkWiFiDial?,
    activeSwitch: ConnectionLinkSwitch?
  ) -> Snapshot {
    if let activeSwitch {
      return snapshot(from: activeSwitch, liveTransport: liveTransport)
    }

    let checked = liveTransport
    var rows: [Row] = []
    if liveTransport == .bluetooth || savedPeripheralID != nil {
      rows.append(Row(
        link: .bluetooth,
        address: nil,
        canSwitch: savedPeripheralID != nil && liveTransport != .bluetooth
      ))
    }
    let host = wifiDial?.displayHost
    if liveTransport == .wifi || wifiSupport == .supported {
      rows.append(Row(
        link: .wifi,
        address: host,
        canSwitch: liveTransport != .wifi && wifiAssociated && wifiDial?.canDial == true
      ))
    }
    let checkedHost = checked == .wifi ? host : nil
    return Snapshot(
      rows: rows,
      checked: checked,
      target: nil,
      settingsDetail: settingsDetail(checked: checked, displayHost: checkedHost)
    )
  }

  /// Eligibility follows the live transport, so a retained checkmark cannot block recovery.
  private static func snapshot(from record: ConnectionLinkSwitch, liveTransport: TransportType?) -> Snapshot {
    var rows: [Row] = []
    let bluetoothCanSwitch = record.bluetoothPeripheralID != nil && liveTransport != .bluetooth
    let wifiCanSwitch = record.wifi?.canDial == true && liveTransport != .wifi
    if record.checked == .bluetooth || record.bluetoothPeripheralID != nil || record.target == .bluetooth {
      rows.append(Row(link: .bluetooth, address: nil, canSwitch: bluetoothCanSwitch))
    }
    let host = record.wifi?.displayHost
    if record.checked == .wifi || record.wifi != nil || record.target == .wifi {
      rows.append(Row(link: .wifi, address: host, canSwitch: wifiCanSwitch))
    }
    return Snapshot(
      rows: rows,
      checked: record.checked,
      target: record.target,
      settingsDetail: settingsDetail(checked: record.checked, displayHost: record.displayHost)
    )
  }

  private static func settingsDetail(checked: TransportType?, displayHost: String?) -> SettingsDetail {
    switch checked {
    case .bluetooth, nil:
      .bluetooth
    case .wifi:
      if let displayHost {
        .host(displayHost)
      } else {
        .wifi
      }
    }
  }
}
