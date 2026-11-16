import Foundation
@testable import MC1
import MC1Services
import Testing

@Suite("Settings route admission")
struct SettingsRouteAdmissionTests {
  @Test
  func `each route stays with its root`() {
    let device = makeDevice(firmware: 11, clientRepeat: false)
    let key = Data([0x01])

    #expect(SettingsSubpage.publicKey(key).isAdmitted(for: .deviceInfo, device: device))
    #expect(SettingsSubpage.configExport.isAdmitted(for: .advanced, device: device))
    #expect(SettingsSubpage.configImport.isAdmitted(for: .advanced, device: device))
    #expect(SettingsSubpage.trustedContacts.isAdmitted(for: .advanced, device: device))
    #expect(!SettingsSubpage.remoteCLIContacts.isAdmitted(for: .advanced, device: device))
    #expect(SettingsSubpage.translateIntoLanguage.isAdmitted(for: .language, device: device))
    #expect(SettingsSubpage.presetLocation.isAdmitted(for: .radio, device: device))
    #expect(SettingsSubpage.offlineMaps.isAdmitted(for: .maps, device: device))
    #expect(SettingsSubpage.manageRegions.isAdmitted(for: .advanced, device: device))
    #expect(SettingsSubpage.support.isAdmitted(for: .appearance, device: device))
    #expect(SettingsSubpage.bluetoothLink.isAdmitted(for: .connection, device: device))
    #expect(SettingsSubpage.wifiLink.isAdmitted(for: .connection, device: device))
    #expect(SettingsSubpage.bluetoothLink.isAdmitted(for: .connection, device: nil))
    #expect(SettingsSubpage.wifiLink.isAdmitted(for: .connection, device: nil))

    #expect(!SettingsSubpage.publicKey(key).isAdmitted(for: .advanced, device: device))
    #expect(!SettingsSubpage.bluetoothLink.isAdmitted(for: .advanced, device: nil))
    #expect(!SettingsSubpage.wifiLink.isAdmitted(for: .deviceInfo, device: device))
    #expect(!SettingsSubpage.offlineMaps.isAdmitted(for: .language, device: device))
    #expect(!SettingsSubpage.support.isAdmitted(for: .support, device: device))
    #expect(!SettingsSubpage.manageRegions.isAdmitted(for: .maps, device: device))
  }

  @Test
  func `blocks and filters routes belong to their own settings page while disconnected`() {
    let path: [SettingsSubpage] = [.recentlyBlocked, .blockedChannelSenders, .blockedContacts, .textRuleChats(.room)]

    #expect(SettingsSubpage.admitted(path, for: .blocksAndFilters, device: nil) == path)
    #expect(SettingsSubpage.admitted(path, for: .chats, device: nil).isEmpty)
  }

  @Test
  func `capability rules drop preset location and regions`() {
    let repeating = makeDevice(firmware: 11, clientRepeat: true)
    let older = makeDevice(firmware: 10, clientRepeat: false)

    #expect(!SettingsSubpage.presetLocation.isAdmitted(for: .radio, device: repeating))
    #expect(!SettingsSubpage.manageRegions.isAdmitted(for: .advanced, device: older))
    #expect(!SettingsSubpage.manageRegions.isAdmitted(for: .advanced, device: nil))
    #expect(SettingsSubpage.presetLocation.isAdmitted(for: .radio, device: nil))

    let radioCLI = makeDevice(firmware: 14, clientRepeat: false)
    #expect(SettingsSubpage.remoteCLIContacts.isAdmitted(for: .advanced, device: radioCLI))
    #expect(!SettingsSubpage.remoteCLIContacts.isAdmitted(for: .chats, device: radioCLI))
    #expect(!SettingsSubpage.remoteCLIContacts.isAdmitted(for: .advanced, device: nil))
  }

  @Test
  func `mismatched path is filtered`() {
    let device = makeDevice(firmware: 11, clientRepeat: false)
    let key = Data([0xAB])
    let path: [SettingsSubpage] = [
      .publicKey(key),
      .translateIntoLanguage,
      .offlineMaps,
      .manageRegions,
      .support,
      .presetLocation
    ]

    #expect(SettingsSubpage.admitted(path, for: .deviceInfo, device: device) == [.publicKey(key)])
    #expect(SettingsSubpage.admitted(path, for: .language, device: device) == [.translateIntoLanguage])
    #expect(SettingsSubpage.admitted(path, for: .maps, device: device) == [.offlineMaps])
    #expect(SettingsSubpage.admitted(path, for: .advanced, device: device) == [.manageRegions])
    #expect(SettingsSubpage.admitted(path, for: .appearance, device: device) == [.support])
    #expect(SettingsSubpage.admitted(path, for: .radio, device: device) == [.presetLocation])
    #expect(SettingsSubpage.admitted(path, for: .radio, device: makeDevice(firmware: 11, clientRepeat: true)).isEmpty)
    #expect(SettingsSubpage.admitted(path, for: nil, device: device).isEmpty)
  }

  @Test
  func `device info telemetry routes stay with the matching key`() {
    let device = makeDevice(firmware: 11, clientRepeat: false)
    let fix = NodeLocationFix(latitude: 47.6, longitude: -122.3, altitude: 12)
    let history = SettingsSubpage.deviceInfoTelemetryHistory(publicKey: device.publicKey)
    let map = SettingsSubpage.deviceInfoLocationMap(publicKey: device.publicKey, fix: fix, name: "Relay")
    let otherKey = Data(repeating: 0x22, count: ProtocolLimits.publicKeySize)
    let wrongHistory = SettingsSubpage.deviceInfoTelemetryHistory(publicKey: otherKey)
    let wrongMap = SettingsSubpage.deviceInfoLocationMap(publicKey: otherKey, fix: fix, name: "Other")

    #expect(history.isAdmitted(for: .deviceInfo, device: device))
    #expect(map.isAdmitted(for: .deviceInfo, device: device))
    #expect(!history.isAdmitted(for: .advanced, device: device))
    #expect(!map.isAdmitted(for: .maps, device: device))
    #expect(!history.isAdmitted(for: .deviceInfo, device: nil))
    #expect(!map.isAdmitted(for: .deviceInfo, device: nil))
    #expect(!wrongHistory.isAdmitted(for: .deviceInfo, device: device))
    #expect(!wrongMap.isAdmitted(for: .deviceInfo, device: device))

    let path: [SettingsSubpage] = [history, map, wrongHistory, wrongMap, .offlineMaps]
    #expect(SettingsSubpage.admitted(path, for: .deviceInfo, device: device) == [history, map])
    #expect(SettingsSubpage.admitted(path, for: .maps, device: device) == [.offlineMaps])
  }
}

private func makeDevice(firmware: UInt8, clientRepeat: Bool) -> DeviceDTO {
  DeviceDTO(
    id: UUID(),
    publicKey: Data(repeating: 0xB1, count: ProtocolLimits.publicKeySize),
    nodeName: "Relay",
    firmwareVersion: firmware,
    firmwareVersionString: "1.15.0",
    manufacturerName: "Test",
    buildDate: "2025-01-01",
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
    clientRepeat: clientRepeat,
    manualAddContacts: false,
    multiAcks: 2,
    telemetryModeBase: 2,
    telemetryModeLoc: 0,
    telemetryModeEnv: 0,
    advertLocationPolicy: 0,
    lastConnected: Date(),
    lastContactSync: 0,
    isActive: true,
    ocvPreset: nil,
    customOCVArrayString: nil
  )
}
