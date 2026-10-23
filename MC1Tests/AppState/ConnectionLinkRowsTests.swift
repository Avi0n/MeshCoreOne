import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Connection link rows")
struct ConnectionLinkRowsTests {
  private static let peripheral = UUID(uuidString: "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")!
  private static let radio = UUID(uuidString: "BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")!
  private static let device = UUID(uuidString: "CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")!

  @Test(arguments: cases)
  func `rows follow the live link the snapshot and the record`(_ row: RowCase) {
    let snapshot = ConnectionLinkRows.make(
      liveTransport: row.live,
      savedPeripheralID: row.peripheral,
      wifiSupport: row.support,
      wifiAssociated: row.associated,
      wifiDial: row.dial,
      activeSwitch: row.record
    )
    #expect(snapshot.rows.map(\.link) == row.links)
    #expect(snapshot.rows.map(\.address) == row.addresses)
    #expect(snapshot.rows.map(\.canSwitch) == row.canSwitch)
    #expect(snapshot.checked == row.checked)
    #expect(snapshot.target == row.target)
    #expect(snapshot.settingsDetail == row.detail)
  }

  @Test
  func `a record hides nothing the tap showed`() {
    let record = Self.heldRecord(checked: .bluetooth, target: .wifi, host: "10.0.0.8")
    let snapshot = ConnectionLinkRows.make(
      liveTransport: nil,
      savedPeripheralID: nil,
      wifiSupport: .unknown,
      wifiAssociated: false,
      wifiDial: nil,
      activeSwitch: record
    )
    #expect(snapshot.rows.map(\.link) == [.bluetooth, .wifi])
    #expect(snapshot.rows.map(\.address) == [nil, "10.0.0.8"])
    #expect(snapshot.rows.map(\.canSwitch) == [true, true])
    #expect(snapshot.checked == .bluetooth)
    #expect(snapshot.target == .wifi)
  }

  private static let cases: [RowCase] = [
    RowCase(
      name: "Bluetooth before support is known",
      live: .bluetooth,
      peripheral: peripheral,
      support: .unknown,
      links: [.bluetooth],
      addresses: [nil],
      canSwitch: [false],
      checked: .bluetooth,
      detail: .bluetooth
    ),
    RowCase(
      name: "unknown support ignores a saved endpoint",
      live: .bluetooth,
      peripheral: peripheral,
      support: .unknown,
      dial: dial("192.168.1.9"),
      links: [.bluetooth],
      addresses: [nil],
      canSwitch: [false],
      checked: .bluetooth,
      detail: .bluetooth
    ),
    RowCase(
      name: "unavailable support ignores association and an endpoint",
      live: .bluetooth,
      peripheral: peripheral,
      support: .unavailable,
      associated: true,
      dial: dial("10.0.0.8"),
      links: [.bluetooth],
      addresses: [nil],
      canSwitch: [false],
      checked: .bluetooth,
      detail: .bluetooth
    ),
    RowCase(
      name: "supported radio with no association keeps Wi-Fi configuration",
      live: .bluetooth,
      peripheral: peripheral,
      support: .supported,
      links: [.bluetooth, .wifi],
      addresses: [nil, nil],
      canSwitch: [false, false],
      checked: .bluetooth,
      detail: .bluetooth
    ),
    RowCase(
      name: "a saved host stays visible before association",
      live: .bluetooth,
      peripheral: peripheral,
      support: .supported,
      dial: ConnectionLinkWiFiDial(host: "radio.local", port: 4242),
      links: [.bluetooth, .wifi],
      addresses: [nil, "radio.local"],
      canSwitch: [false, false],
      checked: .bluetooth,
      detail: .bluetooth
    ),
    RowCase(
      name: "supported radio can switch after association and an endpoint",
      live: .bluetooth,
      peripheral: peripheral,
      support: .supported,
      associated: true,
      dial: dial("10.0.0.8"),
      links: [.bluetooth, .wifi],
      addresses: [nil, "10.0.0.8"],
      canSwitch: [false, true],
      checked: .bluetooth,
      detail: .bluetooth
    ),
    RowCase(
      name: "association without a usable endpoint stays configuration only",
      live: .bluetooth,
      peripheral: peripheral,
      support: .supported,
      associated: true,
      dial: dial("   "),
      links: [.bluetooth, .wifi],
      addresses: [nil, nil],
      canSwitch: [false, false],
      checked: .bluetooth,
      detail: .bluetooth
    ),
    RowCase(
      name: "Wi-Fi with a host",
      live: .wifi,
      peripheral: peripheral,
      support: .unknown,
      dial: dial("10.0.0.4"),
      links: [.bluetooth, .wifi],
      addresses: [nil, "10.0.0.4"],
      canSwitch: [true, false],
      checked: .wifi,
      detail: .host("10.0.0.4")
    ),
    RowCase(
      name: "Wi-Fi stays available when settings are unavailable",
      live: .wifi,
      peripheral: peripheral,
      support: .unavailable,
      dial: dial("10.0.0.4"),
      links: [.bluetooth, .wifi],
      addresses: [nil, "10.0.0.4"],
      canSwitch: [true, false],
      checked: .wifi,
      detail: .host("10.0.0.4")
    ),
    RowCase(
      name: "Wi-Fi with an empty host",
      live: .wifi,
      peripheral: peripheral,
      support: .unknown,
      dial: dial(""),
      links: [.bluetooth, .wifi],
      addresses: [nil, nil],
      canSwitch: [true, false],
      checked: .wifi,
      detail: .wifi
    ),
    RowCase(
      name: "no peripheral id",
      live: .wifi,
      peripheral: nil,
      support: .unavailable,
      dial: dial("10.0.0.4"),
      links: [.wifi],
      addresses: ["10.0.0.4"],
      canSwitch: [false],
      checked: .wifi,
      detail: .host("10.0.0.4")
    ),
    RowCase(
      name: "nil transport and nil peripheral",
      live: nil,
      peripheral: nil,
      support: .unknown,
      links: [],
      addresses: [],
      canSwitch: [],
      checked: nil,
      detail: .bluetooth
    ),
    RowCase(
      name: "supported configuration remains when the live transport is nil",
      live: nil,
      peripheral: nil,
      support: .supported,
      links: [.wifi],
      addresses: [nil],
      canSwitch: [false],
      checked: nil,
      detail: .bluetooth
    ),
    RowCase(
      name: "held record while the live transport is nil",
      live: nil,
      peripheral: nil,
      support: .unknown,
      record: heldRecord(checked: .bluetooth, target: .wifi, host: "10.0.0.8"),
      links: [.bluetooth, .wifi],
      addresses: [nil, "10.0.0.8"],
      canSwitch: [true, true],
      checked: .bluetooth,
      target: .wifi,
      detail: .bluetooth
    ),
    RowCase(
      name: "held retry keeps the captured Wi-Fi endpoint",
      live: nil,
      peripheral: nil,
      support: .unavailable,
      record: heldRecord(checked: .bluetooth, target: nil, host: "10.0.0.8"),
      links: [.bluetooth, .wifi],
      addresses: [nil, "10.0.0.8"],
      canSwitch: [true, true],
      checked: .bluetooth,
      detail: .bluetooth
    ),
    RowCase(
      name: "no live link makes a captured Wi-Fi check selectable too",
      live: nil,
      peripheral: nil,
      support: .unknown,
      record: heldRecord(checked: .wifi, host: "radio.local"),
      links: [.bluetooth, .wifi],
      addresses: [nil, "radio.local"],
      canSwitch: [true, true],
      checked: .wifi,
      detail: .host("radio.local")
    ),
    RowCase(
      name: "live Bluetooth disables only the Bluetooth row",
      live: .bluetooth,
      peripheral: nil,
      support: .unavailable,
      record: heldRecord(checked: .wifi, host: "radio.local"),
      links: [.bluetooth, .wifi],
      addresses: [nil, "radio.local"],
      canSwitch: [false, true],
      checked: .wifi,
      detail: .host("radio.local")
    ),
    RowCase(
      name: "live Wi-Fi disables only the Wi-Fi row",
      live: .wifi,
      peripheral: peripheral,
      support: .supported,
      associated: true,
      dial: dial("10.9.9.9"),
      record: heldRecord(checked: .bluetooth, host: "radio.local"),
      links: [.bluetooth, .wifi],
      addresses: [nil, "radio.local"],
      canSwitch: [true, false],
      checked: .bluetooth,
      detail: .bluetooth
    ),
    RowCase(
      name: "a missing peripheral stays ineligible",
      live: nil,
      peripheral: peripheral,
      support: .supported,
      record: heldRecord(checked: .bluetooth, host: "radio.local", peripheral: nil),
      links: [.bluetooth, .wifi],
      addresses: [nil, "radio.local"],
      canSwitch: [false, true],
      checked: .bluetooth,
      detail: .bluetooth
    ),
    RowCase(
      name: "a missing Wi-Fi endpoint stays ineligible",
      live: nil,
      peripheral: nil,
      support: .supported,
      associated: true,
      dial: dial("10.9.9.9"),
      record: heldRecord(checked: .wifi, host: nil),
      links: [.bluetooth, .wifi],
      addresses: [nil, nil],
      canSwitch: [true, false],
      checked: .wifi,
      detail: .wifi
    ),
    RowCase(
      name: "held record keeps an empty Wi-Fi host",
      live: nil,
      peripheral: nil,
      support: .supported,
      associated: true,
      dial: dial("10.9.9.9"),
      record: heldRecord(checked: .wifi, target: nil, host: ""),
      links: [.bluetooth, .wifi],
      addresses: [nil, nil],
      canSwitch: [true, false],
      checked: .wifi,
      detail: .wifi
    )
  ]

  private static func dial(_ host: String) -> ConnectionLinkWiFiDial {
    ConnectionLinkWiFiDial(host: host, port: ConnectionMethod.defaultWiFiPort)
  }

  private static func heldRecord(
    checked: TransportType,
    target: TransportType? = nil,
    host: String?,
    peripheral: UUID? = Self.peripheral
  ) -> ConnectionLinkSwitch {
    ConnectionLinkSwitch(
      radioID: radio,
      deviceID: device,
      checked: checked,
      target: target,
      token: UUID(uuidString: "DDDDDDDD-DDDD-DDDD-DDDD-DDDDDDDDDDDD")!,
      bluetoothPeripheralID: peripheral,
      wifi: host.map { ConnectionLinkWiFiDial(host: $0, port: ConnectionMethod.defaultWiFiPort) }
    )
  }
}

struct RowCase: Sendable, CustomTestStringConvertible {
  var name: String
  var live: TransportType?
  var peripheral: UUID?
  var support: ConnectionLinkRows.WiFiSettingsSupport = .unknown
  var associated: Bool = false
  var dial: ConnectionLinkWiFiDial?
  var record: ConnectionLinkSwitch?
  var links: [TransportType]
  var addresses: [String?]
  var canSwitch: [Bool]
  var checked: TransportType?
  var target: TransportType?
  var detail: ConnectionLinkRows.SettingsDetail

  var testDescription: String {
    name
  }
}
