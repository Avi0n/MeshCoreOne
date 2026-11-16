@testable import MC1
import Testing

@Suite("Radio WiFi row presentation contract")
@MainActor
struct RadioWiFiContractPresentationTests {
  @Test(arguments: [
    ("configured", true, "configured", L10n.Settings.RadioWiFi.Status.connected),
    ("configured", false, "configured", L10n.Settings.RadioWiFi.Status.notConnected),
    ("", true, L10n.Settings.RadioWiFi.Network.notSet, L10n.Settings.RadioWiFi.Status.notConnected),
    ("", false, L10n.Settings.RadioWiFi.Network.notSet, L10n.Settings.RadioWiFi.Status.notConnected)
  ])
  func `complete read uses observed presentation`(
    _ ssid: String, _ enabled: Bool, _ expectedNetwork: String, _ expectedStatus: String
  ) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      var firmware = RadioWiFiFirmware(defaultSSID: ssid)
      firmware.enabled = enabled
      firmware.boot()
      await harness.transport.useFirmware(firmware)

      await harness.viewModel.noteSessionReadiness()

      #expect(harness.viewModel.showsSection)
      #expect(harness.viewModel.wifiEnabled == enabled)
      #expect(harness.viewModel.networkValue == expectedNetwork)
      #expect(harness.viewModel.statusValue == expectedStatus)
      #expect(harness.viewModel.errorMessage == nil)
      #expect(await harness.transport.effectiveCommands() == [
        "get wifi.enabled", "get wifi.ssid", "get wifi.status", "get wifi.ip"
      ])
      let settings = try #require(harness.settings)
      #expect(try await settings.getRadioCLIValue("wifi.enabled") != nil)
    }
  }

  @Test(arguments: [
    (false, L10n.Settings.RadioWiFi.Status.notConnected, "0"),
    (true, L10n.Settings.RadioWiFi.Status.connected, "1")
  ])
  func `fresh SSID timeout keeps unread rows unknown`(
    _ enabled: Bool, _ expectedStatus: String, _ expectedEnabled: String
  ) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      var firmware = RadioWiFiFirmware(defaultSSID: "configured")
      firmware.enabled = enabled
      firmware.boot()
      await harness.transport.useFirmware(firmware)
      await harness.transport.holdNext("get wifi.ssid")
      let read = harness.start { await harness.viewModel.noteSessionReadiness() }
      try await waitUntil("the submitted SSID read owns a real exchange timeout") {
        await harness.transport.heldCount("get wifi.ssid") == 1 && harness.clock.sleeperCount > 0
      }

      #expect(harness.viewModel.showsSection)
      #expect(harness.viewModel.wifiEnabled == enabled)
      #expect(harness.viewModel.networkValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.ipAddress == nil)
      #expect(harness.viewModel.errorMessage == nil)
      #expect(await harness.transport.effectiveCommands() == ["get wifi.enabled", "get wifi.ssid"])

      harness.clock.advance(by: .seconds(31))
      await read.value

      #expect(!harness.viewModel.isBusy)
      #expect(harness.viewModel.showsSection)
      #expect(harness.viewModel.wifiEnabled == enabled)
      #expect(harness.viewModel.networkValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.ipAddress == nil)
      #expect(harness.viewModel.errorMessage != nil)
      #expect(await harness.transport.effectiveCommands() == ["get wifi.enabled", "get wifi.ssid"])

      harness.viewModel.errorMessage = nil
      #expect(harness.viewModel.networkValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      #expect(await harness.transport.effectiveCommands() == ["get wifi.enabled", "get wifi.ssid"])
      await harness.transport.release("get wifi.ssid")
      await harness.viewModel.refresh()

      #expect(await harness.transport.heldCount("get wifi.ssid") == 0)
      #expect(harness.viewModel.networkValue == "configured")
      #expect(harness.viewModel.statusValue == expectedStatus)
      #expect(harness.viewModel.errorMessage == nil)
      #expect(harness.viewModel.canRunCommands)
      let settings = try #require(harness.settings)
      #expect(try await settings.getRadioCLIValue("wifi.enabled") == expectedEnabled)
    }
  }

  @Test(arguments: [
    ("configured", "configured", L10n.Settings.RadioWiFi.Status.connected),
    ("", L10n.Settings.RadioWiFi.Network.notSet, L10n.Settings.RadioWiFi.Status.notConnected)
  ])
  func `confirmed SSID survives status timeout`(
    _ ssid: String, _ expectedNetwork: String, _ expectedStatus: String
  ) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      await harness.transport.useFirmware(RadioWiFiFirmware(defaultSSID: ssid))
      await harness.transport.holdNext("get wifi.status")
      let read = harness.start { await harness.viewModel.noteSessionReadiness() }
      try await waitUntil("the submitted status read owns a real exchange timeout") {
        await harness.transport.heldCount("get wifi.status") == 1 && harness.clock.sleeperCount > 0
      }

      #expect(harness.viewModel.showsSection)
      #expect(harness.viewModel.wifiEnabled)
      #expect(harness.viewModel.networkValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.ipAddress == nil)
      #expect(harness.viewModel.errorMessage == nil)
      #expect(await harness.transport.effectiveCommands() == [
        "get wifi.enabled", "get wifi.ssid", "get wifi.status"
      ])

      harness.clock.advance(by: .seconds(31))
      await read.value

      #expect(!harness.viewModel.isBusy)
      #expect(harness.viewModel.showsSection)
      #expect(harness.viewModel.wifiEnabled)
      #expect(harness.viewModel.networkValue == expectedNetwork)
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.ipAddress == nil)
      #expect(harness.viewModel.errorMessage != nil)
      #expect(await harness.transport.effectiveCommands() == [
        "get wifi.enabled", "get wifi.ssid", "get wifi.status"
      ])

      harness.viewModel.errorMessage = nil
      #expect(harness.viewModel.networkValue == expectedNetwork)
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      #expect(await harness.transport.effectiveCommands() == [
        "get wifi.enabled", "get wifi.ssid", "get wifi.status"
      ])
      await harness.transport.release("get wifi.status")
      await harness.viewModel.refresh()

      #expect(await harness.transport.heldCount("get wifi.status") == 0)
      #expect(harness.viewModel.networkValue == expectedNetwork)
      #expect(harness.viewModel.statusValue == expectedStatus)
      #expect(harness.viewModel.errorMessage == nil)
      #expect(harness.viewModel.canRunCommands)
      let settings = try #require(harness.settings)
      #expect(try await settings.getRadioCLIValue("wifi.enabled") == "1")
    }
  }
}
