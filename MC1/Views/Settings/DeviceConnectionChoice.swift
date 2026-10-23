import MC1Services

enum DeviceConnectionChoice: Equatable {
  struct Link: Equatable {
    var target: SavedDeviceConnectTarget
    var isCurrent: Bool
    var isEnabled: Bool
  }

  case unavailable
  case available(bluetooth: Link, wifi: Link)

  static func make(
    device: DeviceDTO,
    bluetoothAdvertising: Bool,
    selected: SavedDeviceConnectTarget
  ) -> DeviceConnectionChoice {
    guard let peripheralID = device.bluetoothPeripheralID,
          case let .wifi(host, port, _) = device.wifiConnectionMethod else {
      return .unavailable
    }
    let bluetoothTarget = SavedDeviceConnectTarget.bluetooth(peripheralID: peripheralID)
    let wifiTarget = SavedDeviceConnectTarget.wifi(host: host, port: port)
    return .available(
      bluetooth: Link(
        target: bluetoothTarget,
        isCurrent: selected == bluetoothTarget,
        isEnabled: bluetoothAdvertising
      ),
      wifi: Link(
        target: wifiTarget,
        isCurrent: selected == wifiTarget,
        isEnabled: true
      )
    )
  }
}
