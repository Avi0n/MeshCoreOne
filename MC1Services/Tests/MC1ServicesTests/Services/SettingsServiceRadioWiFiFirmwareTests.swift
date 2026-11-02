import Foundation
@testable import MC1Services
import Testing

@Suite("SettingsService WiFi firmware defaults")
struct SettingsServiceRadioWiFiFirmwareTests {
  @Test
  func `build default SSID and raw empty saved password are independent`() async throws {
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in", defaultPassword: "build-key"))
    let service = SettingsService(session: mock)

    let ssid = try await service.getRadioCLIValue("wifi.ssid")
    let password = try await service.getRadioCLIValue("wifi.pwd")

    #expect(ssid == "built-in")
    #expect(password?.isEmpty == true)
    #expect(await mock.wiFiBootUses(ssid: "built-in", password: "build-key"))
  }

  @Test
  func `a saved password takes effect at boot only after SSID is materialized`() async throws {
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in", defaultPassword: "build-key"))
    let service = SettingsService(session: mock)

    try await service.setRadioCLIValue("wifi.pwd", to: "replacement-key")
    await mock.bootWiFiFirmware()
    #expect(await mock.wiFiBootUses(ssid: "built-in", password: "build-key"))

    try await service.setRadioCLIValue("wifi.ssid", to: "built-in")
    await mock.bootWiFiFirmware()
    #expect(await mock.wiFiBootUses(ssid: "built-in", password: "replacement-key"))
  }

  @Test
  func `clear reveals the build default while association stays unchanged until boot`() async throws {
    let mock = MockConfigurationSession()
    var firmware = RadioWiFiFirmware(defaultSSID: "built-in", defaultPassword: "build-key")
    firmware.savedSSID = "saved"
    firmware.savedPassword = "saved-key"
    firmware.boot()
    await mock.useWiFiFirmware(firmware)
    let service = SettingsService(session: mock)

    try await service.setRadioCLIValue("wifi.clear", to: nil)

    #expect(try await service.getRadioCLIValue("wifi.ssid") == "built-in")
    #expect(try await service.getRadioCLIValue("wifi.pwd")?.isEmpty == true)
    #expect(try await service.getRadioCLIValue("wifi.enabled") == "1")
    #expect(await mock.wiFiBootUses(ssid: "saved", password: "saved-key"))
    await mock.bootWiFiFirmware()
    #expect(await mock.wiFiBootUses(ssid: "built-in", password: "build-key"))
  }
}
