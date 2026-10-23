import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("DeviceSelectionFilter Tests")
struct DeviceSelectionFilterTests {
  @Test
  func `WiFi-capable device is shown regardless of ASK registration`() {
    let device = makeDevice(connectionMethods: [
      .wifi(host: "10.0.0.2", port: 5000, displayName: "Home")
    ])

    #expect(DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: []))
  }

  @Test
  func `BLE device registered with AccessorySetupKit is shown`() {
    let id = UUID()
    let device = makeDevice(
      id: id,
      connectionMethods: [.bluetooth(peripheralUUID: id, displayName: "Radio")]
    )

    #expect(DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [id]))
  }

  @Test
  func `BLE device missing from ASK is hidden (imported shadow)`() {
    let device = makeDevice(connectionMethods: [
      .bluetooth(peripheralUUID: UUID(), displayName: "Radio")
    ])

    #expect(!DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [UUID()]))
  }

  @Test
  func `Device with empty connection methods and no ASK entry is hidden`() {
    let device = makeDevice(connectionMethods: [])

    #expect(!DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: []))
  }

  @Test
  func `Device with empty connection methods is shown when ASK knows its id`() {
    let id = UUID()
    let device = makeDevice(id: id, connectionMethods: [])

    #expect(DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [id]))
  }

  @Test
  func `WiFi+BLE device is shown when its BLE id is missing from ASK`() {
    let device = makeDevice(connectionMethods: [
      .wifi(host: "10.0.0.2", port: 5000, displayName: "Home"),
      .bluetooth(peripheralUUID: UUID(), displayName: "Radio")
    ])

    #expect(DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [UUID()]))
  }

  @Test
  func `macOS: BLE device with a stored bluetooth method is shown (no ASK registry)`() {
    let device = makeDevice(connectionMethods: [
      .bluetooth(peripheralUUID: UUID(), displayName: "Radio")
    ])

    // On macOS pairedAccessoryIDs is always empty; the stored method is the signal.
    #expect(DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [], hasSystemPairingRegistry: false))
  }

  @Test
  func `macOS: ghost/shadow with no bluetooth method is hidden`() {
    let device = makeDevice(connectionMethods: [])

    #expect(!DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [], hasSystemPairingRegistry: false))
  }

  @Test
  func `macOS: a method-less device is hidden even when its id is in the registry set`() {
    let id = UUID()
    let device = makeDevice(id: id, connectionMethods: [])

    // Without a system pairing registry the stored method is the only signal, so a
    // populated pairedAccessoryIDs must not flip a method-less device to connectable.
    #expect(!DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [id], hasSystemPairingRegistry: false))
  }

  @Test
  func `saved radio and unsaved ASK accessory both appear`() {
    let savedID = UUID()
    let askID = UUID()
    let saved = makeDevice(
      id: savedID,
      connectionMethods: [.bluetooth(peripheralUUID: savedID, displayName: "Radio")]
    )

    let result = DeviceSelectionListBuilder.make(
      saved: [saved],
      accessories: [
        (id: savedID, name: "Radio"),
        (id: askID, name: "Stray")
      ],
      needsSetup: [SystemPairedAccessory(id: askID, name: "Stray")],
      hasSystemPairingRegistry: true
    )

    #expect(result.connectable.map(\.id) == [savedID])
    #expect(result.needsSetup.map(\.id) == [askID])
    #expect(result.needsSetup.map(\.name) == ["Stray"])
  }

  @Test
  func `macOS registry-less builder never reports needs-setup`() {
    let askID = UUID()
    let result = DeviceSelectionListBuilder.make(
      saved: [],
      accessories: [(id: askID, name: "Stray")],
      needsSetup: [SystemPairedAccessory(id: askID, name: "Stray")],
      hasSystemPairingRegistry: false
    )

    #expect(result.needsSetup.isEmpty)
  }

  @Test
  func `needs-setup row copy is Set Up VoiceOver and is not a Connect row`() {
    let name = "Radio B"
    let presentation = DeviceSelectionListBuilder.needsSetupPresentation(name: name)

    #expect(presentation.trailingTitle == L10n.Settings.DeviceSelection.setup)
    #expect(presentation.accessibilityLabel == L10n.Settings.DeviceSelection.Accessibility.setupLabel(name))
    #expect(presentation.accessibilityHint == L10n.Settings.DeviceSelection.Accessibility.setupHint)
    #expect(presentation.accessibilityHint != L10n.Settings.DeviceSelection.Accessibility.selectHint)
    #expect(presentation.accessibilityHint != L10n.Settings.DeviceSelection.Accessibility.outOfRangeHint)
  }
}

private func makeDevice(
  id: UUID = UUID(),
  connectionMethods: [ConnectionMethod]
) -> DeviceDTO {
  DeviceDTO(
    id: id,
    radioID: id,
    publicKey: Data(repeating: 0x01, count: 32),
    nodeName: "TestDevice",
    firmwareVersion: 9,
    firmwareVersionString: "v1.13.0",
    manufacturerName: "TestMfg",
    buildDate: "01 Jan 2025",
    maxContacts: 100,
    maxChannels: 8,
    frequency: 915_000,
    bandwidth: 250_000,
    spreadingFactor: 10,
    codingRate: 5,
    txPower: 20,
    maxTxPower: 20,
    latitude: 0,
    longitude: 0,
    blePin: 0,
    manualAddContacts: false,
    multiAcks: 2,
    telemetryModeBase: 2,
    telemetryModeLoc: 0,
    telemetryModeEnv: 0,
    advertLocationPolicy: 0,
    lastConnected: Date(),
    lastContactSync: 0,
    isActive: false,
    ocvPreset: nil,
    customOCVArrayString: nil,
    connectionMethods: connectionMethods
  )
}

@Suite("Device connection choice")
struct DeviceConnectionChoiceTests {
  @Test
  func `a radio with one link has no menu`() {
    let peripheralID = UUID()
    let bluetoothOnly = makeDevice(connectionMethods: [
      .bluetooth(peripheralUUID: peripheralID, displayName: nil)
    ])
    let wifiOnly = makeDevice(connectionMethods: [
      .wifi(host: "10.0.0.8", port: 5000, displayName: nil)
    ])
    let empty = makeDevice(connectionMethods: [])

    #expect(DeviceConnectionChoice.make(
      device: bluetoothOnly,
      bluetoothAdvertising: true,
      selected: bluetoothOnly.savedDeviceConnectTarget
    ) == .unavailable)
    #expect(DeviceConnectionChoice.make(
      device: wifiOnly,
      bluetoothAdvertising: true,
      selected: wifiOnly.savedDeviceConnectTarget
    ) == .unavailable)
    #expect(DeviceConnectionChoice.make(
      device: empty,
      bluetoothAdvertising: false,
      selected: empty.savedDeviceConnectTarget
    ) == .unavailable)
  }

  @Test
  func `the check follows the last used link and bluetooth uses the peripheral id`() {
    let deviceID = UUID()
    let peripheralID = UUID()
    let wifi = ConnectionMethod.wifi(host: "10.0.0.8", port: 5000, displayName: nil)
    let bluetooth = ConnectionMethod.bluetooth(peripheralUUID: peripheralID, displayName: nil)

    let bluetoothDevice = makeDevice(id: deviceID, connectionMethods: [wifi, bluetooth])
    let bluetoothLast = DeviceConnectionChoice.make(
      device: bluetoothDevice,
      bluetoothAdvertising: true,
      selected: bluetoothDevice.savedDeviceConnectTarget
    )

    guard case let .available(bluetoothLink, wifiLink) = bluetoothLast else {
      Issue.record("Expected a menu")
      return
    }
    #expect(bluetoothLink.target == .bluetooth(peripheralID: peripheralID))
    #expect(bluetoothLink.target != .bluetooth(peripheralID: deviceID))
    #expect(bluetoothLink.isCurrent)
    #expect(bluetoothLink.isEnabled)
    #expect(wifiLink.target == .wifi(host: "10.0.0.8", port: 5000))
    #expect(!wifiLink.isCurrent)
    #expect(wifiLink.isEnabled)
  }

  @Test
  func `wifi last checks wifi and disables bluetooth when it is not advertising`() {
    let peripheralID = UUID()
    let wifi = ConnectionMethod.wifi(host: "10.0.0.8", port: 4321, displayName: nil)
    let bluetooth = ConnectionMethod.bluetooth(peripheralUUID: peripheralID, displayName: nil)
    let device = makeDevice(connectionMethods: [bluetooth, wifi])
    let choice = DeviceConnectionChoice.make(
      device: device,
      bluetoothAdvertising: false,
      selected: device.savedDeviceConnectTarget
    )

    guard case let .available(bluetoothLink, wifiLink) = choice else {
      Issue.record("Expected a menu")
      return
    }
    #expect(wifiLink.isCurrent)
    #expect(wifiLink.isEnabled)
    #expect(wifiLink.target == .wifi(host: "10.0.0.8", port: 4321))
    #expect(!bluetoothLink.isCurrent)
    #expect(!bluetoothLink.isEnabled)
  }

  @Test
  func `out of range bluetooth stays checked and wifi stays enabled`() {
    let peripheralID = UUID()
    let wifi = ConnectionMethod.wifi(host: "10.0.0.8", port: 5000, displayName: nil)
    let bluetooth = ConnectionMethod.bluetooth(peripheralUUID: peripheralID, displayName: nil)
    let device = makeDevice(connectionMethods: [wifi, bluetooth])
    let choice = DeviceConnectionChoice.make(
      device: device,
      bluetoothAdvertising: false,
      selected: device.savedDeviceConnectTarget
    )

    guard case let .available(bluetoothLink, wifiLink) = choice else {
      Issue.record("Expected a menu")
      return
    }
    #expect(bluetoothLink.isCurrent)
    #expect(!bluetoothLink.isEnabled)
    #expect(!wifiLink.isCurrent)
    #expect(wifiLink.isEnabled)
  }
}
